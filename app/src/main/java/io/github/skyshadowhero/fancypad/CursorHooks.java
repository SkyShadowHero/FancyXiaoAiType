package io.github.skyshadowhero.fancypad;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.LruCache;
import android.util.SparseArray;
import android.view.PointerIcon;

import androidx.annotation.NonNull;

import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedInterface;



/**
 * FancyPad · 光标主题（原 os4光标主题模块）—— 接管光标渲染，作用域 {@code system}。
 *
 * <p>反编译证据（HyperOS 4.0 / Android 17）：
 * {@code InputSettingsObserver.updatePointer*FromSettings()} 和
 * {@code PointerIcon.getLoadedSystemIcon()} 的矢量/位图选择都被只读 aconfig flag
 * {@code enableVectorCursorA11ySettings} / {@code enableVectorCursors} 挡住。
 *
 * <p>模块做三件事：
 * <ol>
 *   <li>[system] 两个 flag 强制 true（恢复系统自带的大小/填充/描边链路）；</li>
 *   <li>[system] hook {@code PointerIconCache.getLoadedPointerIcon()}，按偏好渲染光标：
 *       AOSP 预设用官方矢量拆出的「填充层 + 描边层」，颜色任选；GoogleDot 用 mask 两层染色；
 *       Material / MacOS / BreezeX 用各仓库原色图，缺图统一 AOSP 兜底；</li>
 *   <li>[system] 监听偏好变化 → 清光标缓存 + {@code mNative.reloadPointerIcons()}，即时换肤。</li>
 * </ol>
 *
 * <p>总开关 {@code cursor_enabled}（UI：光标页顶部）为 false 时全部 hook 都走原逻辑，
 * 等于模块在这台设备上不接管光标。
 *
 * <p>偏好（Remote Preferences，组名见 {@link PrefKeys#GROUP}）：
 * preset 见 {@link CursorIcons}；scale 百分比（30~300）；fill_&lt;主题&gt; / stroke_&lt;主题&gt;。
 */
public final class CursorHooks {

    private static final String CLS_FLAGS =
            "com.android.internal.hidden_from_bootclasspath.android.view.flags.Flags";
    private static final String CLS_POINTER_CACHE = "com.android.server.input.PointerIconCache";

    /** 由 XposedEntry 传入的模块实例：hook / 偏好 / Remote Files 都走它。 */
    private final XposedInterface module;

    // preset：0=AOSP 1=Material 2=Apple(MacOS) 3=GoogleDot 4=BreezeX 9=自定义(逐项选择) ≥10=导入的主题
    private static final int PRESET_AOSP = 0;
    private static final int PRESET_MATERIAL = 1;
    private static final int PRESET_APPLE = 2;
    private static final int PRESET_GOOGLEDOT = 3;
    private static final int PRESET_BREEZEX = 4;
    private static final int PRESET_CUSTOM = 9;
    /** ≥ 该值表示「导入的主题」：主题名取自 preferences 的 themes（用 | 分隔） */
    private static final int PRESET_THEME_BASE = 10;
    private static final int TYPE_WAIT = 1004;
    /** 三种 material 变体的（填充色, 描边色）—— 用于 material 缺图的类型做兜底染色 */
    private static final int[][] MATERIAL_COLORS = {
            {0xFFE4E4E4, 0xFF010101},   // light
            {0xFF526D78, 0xFF010101},   // default
            {0xFF4F4F4F, 0xFF010101},   // dark
    };

    /** 排查用：打开后每 500 次渲染打一行日志，用来核对 native 到底多久来要一次图标。 */
    private static final boolean DEBUG_STATS = false;
    /** 渲染尺寸上限，避免 scale × density 组合出超大位图。 */
    private static final int MAX_ICON_SIZE = 320;
    /** 偏好变化的合并窗口：窗口内的连续变化只做一次 native 重载（拖滑块时很关键）。 */
    private static final long RELOAD_COALESCE_MS = 150L;
    private static final int MASK_CACHE_BYTES = 8 * 1024 * 1024;
    private static final int CUSTOM_CACHE_BYTES = 16 * 1024 * 1024;

    private volatile boolean systemHooksInstalled;
    private volatile Resources moduleResources;
    private volatile SharedPreferences prefs;
    private volatile boolean prefsListenerBound;

    private volatile SparseArray<?> iconCacheMap;
    private volatile Object nativeService;
    private volatile Method nativeReload;
    private volatile Context cacheContext;
    /** 反射绑定次数上限：失败时不要每次请求都重做 getDeclaredField/setAccessible。 */
    private volatile int bindAttempts;

    /**
     * 最终图标缓存：key = (displayId << 32) | type。
     *
     * <p>平台自己就是靠 PointerIconCache.mLoadedPointerIconsByDisplayAndType 做这件事的：
     * 只有 miss 才渲染并把结果 put 回去。我们的拦截器不调用 chain.proceed()，
     * 平台那段 put 永远不会执行，所以必须自己缓存 —— 否则 native 每次来要图标
     * 都会重新走一遍「建位图 + 栅格化矢量 + 造 BitmapDrawable/PointerIcon + JNI 转 sprite」。
     */
    private final Map<Long, Object> iconCache = new ConcurrentHashMap<>();
    /** 渲染代际：偏好一变就 +1，用于丢弃并发渲染中的旧结果。 */
    private volatile int themeGeneration;
    private final AtomicBoolean reloadScheduled = new AtomicBoolean();
    private volatile ScheduledExecutorService reloadExecutor;
    /** 导入的光标位图缓存：key = 文件名。按字节数做 LRU 上限，避免 system_server 常驻内存无上限。 */
    private final LruCache<String, Bitmap> customCache =
            new LruCache<String, Bitmap>(CUSTOM_CACHE_BYTES) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };
    /** mask 位图缓存（resId → 已解码位图），避免每次重建都 decode */
    private final LruCache<Integer, Bitmap> maskCache =
            new LruCache<Integer, Bitmap>(MASK_CACHE_BYTES) {
                @Override
                protected int sizeOf(Integer key, Bitmap value) {
                    return value.getByteCount();
                }
            };
    /** 负缓存：Remote File 不存在的名字，避免每次都为 25 种类型反复走 binder 查询 */
    private final Set<String> missingRemote = ConcurrentHashMap.newKeySet();
    private static final AtomicLong BUILD_COUNT = new AtomicLong();
    /** 反射结果只查一次（material 的 wait 动画用） */
    private static volatile Constructor<PointerIcon> sWaitCtor;
    private static volatile Method sWaitLoad;
    private static volatile boolean sWaitReflectFailed;

    // ------------------------------------------------------------ 生命周期

    public CursorHooks(XposedInterface module) {
        this.module = module;
    }

    // -------------------------------------------------------- system_server

    /** 由 {@code XposedEntry} 在 system_server（scope: system）里调用。 */
    public synchronized void installSystemHooks(ClassLoader cl) {
        if (systemHooksInstalled) {
            return;
        }
        // 1) 只读 aconfig flag 放行（总开关关掉时交回系统原值）
        try {
            Class<?> flags = cl.loadClass(CLS_FLAGS);
            for (String name : new String[]{"enableVectorCursorA11ySettings", "enableVectorCursors"}) {
                try {
                    module.hook(flags.getDeclaredMethod(name))
                            .setId(name)
                            .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                            .intercept(chain -> HookPrefs.cursorEnabled() ? Boolean.TRUE : chain.proceed());
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        // 2) 接管光标图标
        try {
            Class<?> cacheCls = cl.loadClass(CLS_POINTER_CACHE);
            Method getIcon = null;
            for (Method m : cacheCls.getDeclaredMethods()) {
                if ("getLoadedPointerIcon".equals(m.getName()) && m.getParameterCount() == 2) {
                    getIcon = m;
                    break;
                }
            }
            if (getIcon != null) {
                getIcon.setAccessible(true);
                module.hook(getIcon)
                        .setId("getLoadedPointerIcon")
                        .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                        .intercept(chain -> {
                            if (!HookPrefs.cursorEnabled()) {
                                return chain.proceed();     // 总开关关闭：交回平台
                            }
                            try {
                                bindCache(chain.getThisObject());
                                int displayId = (Integer) chain.getArg(0);
                                int type = (Integer) chain.getArg(1);
                                long key = iconKey(displayId, type);
                                Object cached = iconCache.get(key);
                                if (cached != null) {
                                    return cached;              // 稳态：一次查表，不再重绘
                                }
                                int generation = themeGeneration;
                                Object custom = buildIcon(type);
                                if (custom != null) {
                                    if (generation == themeGeneration) {
                                        iconCache.putIfAbsent(key, custom);
                                    }
                                    return custom;
                                }
                            } catch (Throwable ignored) {
                            }
                            return chain.proceed();
                        });
            }
        } catch (Throwable ignored) {
        }

        systemHooksInstalled = true;
    }

    private static long iconKey(int displayId, int type) {
        return ((long) displayId << 32) | (type & 0xffffffffL);
    }

    private void bindCache(Object self) {
        if (self == null || nativeReload != null) {
            return;
        }
        // 反射绑定最多试几次：失败时不要每次图标请求都重做一遍 getDeclaredField/setAccessible
        if (bindAttempts >= 4) {
            return;
        }
        bindAttempts++;
        try {
            Class<?> cls = self.getClass();
            Field fMap = cls.getDeclaredField("mLoadedPointerIconsByDisplayAndType");
            fMap.setAccessible(true);
            iconCacheMap = (SparseArray<?>) fMap.get(self);

            Field fNative = cls.getDeclaredField("mNative");
            fNative.setAccessible(true);
            nativeService = fNative.get(self);
            if (nativeService != null) {
                nativeReload = nativeService.getClass().getMethod("reloadPointerIcons");
            }
            Field fCtx = cls.getDeclaredField("mContext");
            fCtx.setAccessible(true);
            cacheContext = (Context) fCtx.get(self);

            if (!prefsListenerBound) {
                SharedPreferences p = module.getRemotePreferences(PrefKeys.GROUP);
                prefs = p;
                p.registerOnSharedPreferenceChangeListener((sp, key) -> {
                    // 只对真正影响渲染的键重载 native：导入计数、标签之类不该触发全量 sprite 重填
                    if (key == null || key.startsWith("fill_") || key.startsWith("stroke_")
                            || "preset".equals(key) || "scale".equals(key)
                            || "themes".equals(key) || "theme_labels".equals(key)) {
                        refreshCursor();
                    }
                });
                prefsListenerBound = true;
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 偏好变了：立刻丢弃我们自己的渲染缓存，并（合并后）让 native 重新拉图标 → 即时换肤。
     *
     * <p>拖滑块时偏好会连续变化，这里用 {@link #RELOAD_COALESCE_MS} 的窗口合并请求，
     * 否则 native 会跟着做很多次「清 sprite + 逐个类型重新要图标」。
     * 缓存清空是立即的，native 重载延迟 150ms 不影响观感。
     */
    private void refreshCursor() {
        try {
            iconCache.clear();
            customCache.evictAll();
            missingRemote.clear();
            themeGeneration++;
        } catch (Throwable ignored) {
        }
        if (!reloadScheduled.compareAndSet(false, true)) {
            return;                     // 已经排队，合并掉
        }
        try {
            reloadExecutor().schedule(this::doReload, RELOAD_COALESCE_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            reloadScheduled.set(false);
            doReload();
        }
    }

    private synchronized ScheduledExecutorService reloadExecutor() {
        ScheduledExecutorService executor = reloadExecutor;
        if (executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "dsh-cursor-reload");
                t.setDaemon(true);
                return t;
            });
            reloadExecutor = executor;
        }
        return executor;
    }

    private void doReload() {
        reloadScheduled.set(false);
        try {
            SparseArray<?> map = iconCacheMap;
            if (map != null) {
                synchronized (map) {
                    map.clear();
                }
            }
            Method reload = nativeReload;
            if (reload != null && nativeService != null) {
                reload.invoke(nativeService);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 画光标

    private Object buildIcon(int type) {
        int[] row = rowFor(type);
        if (row == null) {
            return null;
        }
        Context ctx = cacheContext;
        Resources res = moduleResources;
        if (res == null) {
            res = loadModuleResources(ctx);
            moduleResources = res;
        }
        if (ctx == null || res == null) {
            return null;
        }
        SharedPreferences p = prefs;
        if (p == null) {
            try {
                p = prefs = module.getRemotePreferences(PrefKeys.GROUP);
            } catch (Throwable t) {
                return null;
            }
        }

        if (DEBUG_STATS) {
            long n = BUILD_COUNT.incrementAndGet();
            if ((n % 500) == 0) {
                module.log(Log.INFO, "dsh-cursor", "buildIcon x" + n);
            }
        }
        int preset = p.getInt("preset", 0);
        if (type == TYPE_WAIT && preset == PRESET_MATERIAL) {
            Object anim = animatedWait(res, scaleOf(p));
            if (anim != null) {
                return anim;
            }
        }
        if (!hasContent(row, preset)) {
            return null;        // 这个预设下该类型没有可画内容，别白建一张位图再丢掉
        }
        float scale = Math.max(0.3f, Math.min(3.0f, p.getInt("scale", 100) / 100f));
        float density = ctx.getResources().getDisplayMetrics().density;
        int size = Math.max(4, Math.min(MAX_ICON_SIZE, Math.round(24f * density * scale)));

        try {
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG |
                    Paint.DITHER_FLAG);
            Rect bounds = new Rect(0, 0, size, size);

            String themeKey;
            int themeIdx;
            // 热点（0.1dp）：默认用 AOSP 官方热点，可改色/原图主题各用自己仓库里的热点
            int hotX10 = row[3];
            int hotY10 = row[4];
            if (preset == PRESET_CUSTOM || preset >= PRESET_THEME_BASE) {
                // 逐项选择 / 导入的主题：直接用 PNG 原图，不染色
                Bitmap imported = preset == PRESET_CUSTOM ? importedBitmap(type, null)
                        : importedBitmap(type, themeNameOf(p, preset));
                if (imported != null) {
                    canvas.drawBitmap(imported, null, bounds, paint);
                } else if (row[1] != 0 || row[2] != 0) {
                    int[] c = themeColors("aosp", -1, p);
                    if (row[1] != 0) {
                        drawLayer(canvas, res, row[1], c[0], bounds);
                    }
                    if (row[2] != 0) {
                        drawLayer(canvas, res, row[2], c[1], bounds);
                    }
                } else {
                    return null;
                }
            } else if (preset == PRESET_MATERIAL || preset == PRESET_APPLE
                    || preset == PRESET_BREEZEX) {
                // 这三套用仓库原色图（material 带投影、apple=BreezeX 的成图色），不可改色
                int img, hx, hy;
                if (preset == PRESET_MATERIAL) {
                    img = row[9]; hx = row[10]; hy = row[11];
                } else if (preset == PRESET_APPLE) {
                    img = row[12]; hx = row[13]; hy = row[14];
                } else {
                    img = row[15]; hx = row[16]; hy = row[17];
                }
                if (img != 0) {
                    drawPlain(canvas, res, img, bounds, paint);
                    hotX10 = hx;
                    hotY10 = hy;
                } else {
                    // 该主题没有这个类型 → 用 AOSP 官方矢量层兜底（热点也用 AOSP 的）
                    if (row[1] == 0 && row[2] == 0) {
                        return null;
                    }
                    int[] c = themeColors("aosp", -1, p);
                    if (row[1] != 0) {
                        drawLayer(canvas, res, row[1], c[0], bounds);
                    }
                    if (row[2] != 0) {
                        drawLayer(canvas, res, row[2], c[1], bounds);
                    }
                }
            } else if ((themeIdx = themeIndexFor(preset)) >= 0) {
                // GoogleDot：主体/描边两层 mask + 可改色
                themeKey = CursorIcons.THEMES[themeIdx];
                int fillRes = row[5];
                int strokeRes = row[6];
                if (fillRes == 0 && strokeRes == 0) {
                    if (row[1] == 0 && row[2] == 0) {
                        return null;
                    }
                    int[] ca = themeColors("aosp", -1, p);
                    if (row[1] != 0) {
                        drawLayer(canvas, res, row[1], ca[0], bounds);
                    }
                    if (row[2] != 0) {
                        drawLayer(canvas, res, row[2], ca[1], bounds);
                    }
                } else {
                    int[] c = themeColors(themeKey, themeIdx, p);
                    // 原图里描边在主体下面，主体 mask 已把"该压在上面的描边"掏成洞
                    if (strokeRes != 0) {
                        drawMask(canvas, res, strokeRes, c[1], bounds, paint);
                    }
                    if (fillRes != 0) {
                        drawMask(canvas, res, fillRes, c[0], bounds, paint);
                    }
                    hotX10 = row[7];
                    hotY10 = row[8];
                }
            } else {
                // AOSP：官方矢量拆层 + 任意配色
                if (row[1] == 0 && row[2] == 0) {
                    return null;    // wait 之类没有矢量层的 → 交回平台
                }
                int[] c = themeColors("aosp", -1, p);
                if (row[1] != 0) {
                    drawLayer(canvas, res, row[1], c[0], bounds);
                }
                if (row[2] != 0) {
                    drawLayer(canvas, res, row[2], c[1], bounds);
                }
            }

            float hotX = hotX10 / 10f * density * scale;
            float hotY = hotY10 / 10f * density * scale;
            return PointerIcon.create(out, hotX, hotY);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 预设 → mask 列所属的主题下标（CursorIcons.THEMES），非 mask 主题返回 -1。 */
    private static int themeIndexFor(int preset) {
        switch (preset) {
            case PRESET_GOOGLEDOT: return 0;
            default: return -1;
        }
    }

    /** 该类型在该预设下是否有可画内容（wait 在 AOSP/GoogleDot 下没有矢量层，直接交回平台）。 */
    private static boolean hasContent(int[] row, int preset) {
        if (preset == PRESET_CUSTOM || preset >= PRESET_THEME_BASE) {
            return true;        // 要查导入的 Remote File，交给 buildIcon 判断
        }
        if (preset == PRESET_MATERIAL) {
            return row[9] != 0 || row[1] != 0 || row[2] != 0;
        }
        if (preset == PRESET_APPLE) {
            return row[12] != 0 || row[1] != 0 || row[2] != 0;
        }
        if (preset == PRESET_BREEZEX) {
            return row[15] != 0 || row[1] != 0 || row[2] != 0;
        }
        return row[1] != 0 || row[2] != 0 || row[5] != 0 || row[6] != 0;
    }

    /** 主题配色：优先读该主题自己的 fill_<key>/stroke_<key>，没有就用主题默认色。 */
    private static int[] themeColors(String themeKey, int index, SharedPreferences p) {
        int[] def = index >= 0 ? CursorIcons.THEME_COLORS[index]
                : new int[]{0xFF000000, 0xFFFFFFFF};
        return new int[]{
                p.getInt("fill_" + themeKey, def[0]),
                p.getInt("stroke_" + themeKey, def[1]),
        };
    }

    /** 把 mask 位图按指定颜色染色后绘制（SRC_IN 只替换颜色，保留 alpha）。 */
    private void drawMask(Canvas canvas, Resources res, int resId, int color, Rect bounds, Paint paint) {
        Bitmap mask = maskCache.get(resId);
        if (mask == null) {
            mask = BitmapFactory.decodeResource(res, resId);
            if (mask == null) {
                return;
            }
            maskCache.put(resId, mask);
        }
        paint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(mask, null, bounds, paint);
        paint.setColorFilter(null);
    }

    /** 原色位图直接绘制（不染色），material 的插图细节用。 */
    private void drawPlain(Canvas canvas, Resources res, int resId, Rect bounds, Paint paint) {
        Bitmap bmp = maskCache.get(resId);
        if (bmp == null) {
            bmp = BitmapFactory.decodeResource(res, resId);
            if (bmp == null) {
                return;
            }
            maskCache.put(resId, bmp);
        }
        canvas.drawBitmap(bmp, null, bounds, paint);
    }

    /** material 的 wait 是 24 帧动画：借框架 loadResource + AnimationDrawable 生成多帧图标。 */
    private Object animatedWait(Resources res, float scale) {
        try {
            Constructor<PointerIcon> ctor = sWaitCtor;
            Method load = sWaitLoad;
            if (ctor == null || load == null) {
                if (sWaitReflectFailed) {
                    return null;            // 反射查过一次就够了，别每次重来
                }
                try {
                    ctor = PointerIcon.class.getDeclaredConstructor(int.class);
                    ctor.setAccessible(true);
                    load = PointerIcon.class.getDeclaredMethod("loadResource",
                            Resources.class, int.class, Resources.Theme.class, float.class);
                    load.setAccessible(true);
                } catch (Throwable t) {
                    sWaitReflectFailed = true;
                    return null;
                }
                sWaitCtor = ctor;
                sWaitLoad = load;
            }
            PointerIcon icon = ctor.newInstance(TYPE_WAIT);
            load.invoke(icon, res, R.drawable.cursor_wait_icon, null, scale);
            return icon;
        } catch (Throwable t) {
            return null;
        }
    }

    private static float scaleOf(SharedPreferences p) {
        return Math.max(0.3f, Math.min(3.0f, p.getInt("scale", 100) / 100f));
    }

    /** 当前预设对应的导入主题名（非主题预设返回 null）。 */
    private static String themeNameOf(SharedPreferences p, int preset) {
        if (preset < PRESET_THEME_BASE) {
            return null;
        }
        String[] names = p.getString("themes", "").split("\\|");
        int idx = preset - PRESET_THEME_BASE;
        return (idx >= 0 && idx < names.length && !names[idx].isEmpty()) ? names[idx] : null;
    }

    private static void drawLayer(Canvas canvas, Resources res, int resId, int color, Rect bounds) {
        Drawable d = res.getDrawable(resId, null);
        if (d == null) {
            return;
        }
        d.setTint(color);
        d.setBounds(bounds);
        d.draw(canvas);
    }

    /** 读模块 App 导入的光标（Remote Files，Hook 侧只读）；没有就返回 null 走兜底。 */
    private Bitmap importedBitmap(int type, String theme) {
        int idx = rowIndex(type);
        if (idx < 0 || idx >= CursorIcons.KEYS.length) {
            return null;
        }
        String key = CursorIcons.KEYS[idx];
        String file = theme != null ? "cust_" + theme + "_" + key + ".png" : "cust_" + key + ".png";
        if (missingRemote.contains(file)) {
            return null;
        }
        Bitmap cached = customCache.get(file);
        if (cached != null) {
            return cached;
        }
        try (ParcelFileDescriptor pfd = module.openRemoteFile(file)) {
            if (pfd == null) {
                missingRemote.add(file);    // 不存在也要记下来，否则每次请求都要再走一次 binder
                return null;
            }
            try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
                Bitmap bmp = BitmapFactory.decodeStream(in);
                if (bmp != null) {
                    customCache.put(file, bmp);
                }
                return bmp;
            }
        } catch (Throwable t) {
            missingRemote.add(file);
            return null;
        }
    }

    private static int rowIndex(int type) {
        int[][] table = CursorIcons.TABLE;
        for (int i = 0; i < table.length; i++) {
            if (table[i][0] == type) {
                return i;
            }
        }
        return -1;
    }

    private static int[] rowFor(int type) {
        for (int[] row : CursorIcons.TABLE) {
            if (row[0] == type) {
                return row;
            }
        }
        return null;
    }

    private Resources loadModuleResources(Context ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            return ctx.getPackageManager().getResourcesForApplication(module.getModuleApplicationInfo());
        } catch (Throwable t) {
            return null;
        }
    }

}
