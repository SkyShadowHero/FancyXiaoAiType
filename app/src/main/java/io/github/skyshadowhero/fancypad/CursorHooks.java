package io.github.skyshadowhero.fancypad;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
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
import java.util.concurrent.atomic.AtomicInteger;
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
 *   <li>[system] 摇晃放大：{@link CursorShake} 观测指针位置流判定「摇晃」，这里把放大的
 *       **目标倍数**一档档往上抬，再由动画线程逐帧逼近目标并重拉图标 —— 见本类的「摇晃放大」段。</li>
 * </ol>
 *
 * <p>总开关 {@code cursor_enabled}（UI：光标页顶部）为 false 时全部 hook 都走原逻辑，
 * 等于模块在这台设备上不接管光标。
 *
 * <p>偏好（Remote Preferences，组名见 {@link PrefKeys#GROUP}）：
 * preset 见 {@link CursorIcons}；scale 百分比（30~300）；fill_&lt;主题&gt; / stroke_&lt;主题&gt;；
 * 摇晃放大见 {@code cursor_shake_*}。
 *
 * <p><b>为什么摇晃放大不能用框架的缩放</b>：{@code PointerIconCache} 自带
 * {@code setPointerScale(float)}，但那只影响 {@code PointerIcon.getLoadedSystemIcon()} 内部的
 * 缩放；本模块已经接管了 {@code getLoadedPointerIcon()}、不调用 {@code chain.proceed()}，
 * 框架那条链路根本不会执行。放大的唯一落点是本模块自己的 {@link #buildIcon(int)}。
 */
public final class CursorHooks {

    private static final String CLS_FLAGS =
            "com.android.internal.hidden_from_bootclasspath.android.view.flags.Flags";
    private static final String CLS_POINTER_CACHE = "com.android.server.input.PointerIconCache";

    /** 由 XposedEntry 传入的模块实例：hook / 偏好 / Remote Files 都走它。 */
    private final XposedInterface module;

    /** 摇晃放大：只读观测指针位置流，命中后回调 {@link #onShake()} / {@link #onShakeIdle()}。 */
    private final CursorShake shake;

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
    /**
     * 渲染缩放的硬上限（用户设定 × 摇晃放大）。
     *
     * <p>用户设定本身最大 3 倍；持续摇晃会一档档往上加，这里兜住总缩放。
     * 6 倍在 2.0 density 下是 24dp × 6 = 288px，仍在 {@link #MAX_ICON_SIZE} 之内；
     * density 更高时会先被位图上限截断（热点按截断后的实际缩放算，不会跑偏）。
     */
    private static final float MAX_RENDERED_SCALE = 6.0f;
    /** 偏好变化的合并窗口：窗口内的连续变化只做一次 native 重载（拖滑块时很关键）。 */
    private static final long RELOAD_COALESCE_MS = 150L;
    private static final int MASK_CACHE_BYTES = 8 * 1024 * 1024;
    private static final int CUSTOM_CACHE_BYTES = 16 * 1024 * 1024;
    /**
     * 矢量超采样栅格缓存的容量。单张 320² × 4B ≈ 410KB，6MB 大约能放 15 个图层，
     * 覆盖实际会同时用到的光标类型绰绰有余。
     */
    private static final int LAYER_CACHE_BYTES = 6 * 1024 * 1024;

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
     *
     * <p>缓存的值还记着「它是按哪个放大系数渲染的」：放大动画每一帧系数都在变，
     * 靠这个校验就**只需要重画 native 真正来要的那几种类型**，而不是每种都重画。
     */
    private final Map<Long, CachedIcon> iconCache = new ConcurrentHashMap<>();
    /** 最近一次被 native 要过的图标 key；只用于诊断。 */
    private volatile long lastIconKey;
    /** 放大系数按浮点比较的容差。 */
    private static final float BOOST_EPSILON = 1e-4f;

    /** 已渲染的光标图标 + 它当时的放大系数。 */
    private static final class CachedIcon {
        final Object icon;
        final float boost;

        CachedIcon(Object icon, float boost) {
            this.icon = icon;
            this.boost = boost;
        }
    }
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
    /**
     * 矢量层（AOSP 的填充层 / 描边层）的**超采样栅格缓存**：resId → 已按固定尺寸栅格化并染色的位图。
     *
     * <p>这是「摇晃放大动画卡顿」的根因所在：{@link #drawLayer} 原本每次都用
     * {@code res.getDrawable()} 现场把矢量画进目标尺寸的画布 —— 每张图都要解析矢量 XML +
     * 重新栅格化。放大动画每一帧都要把当时用到的光标图标全部重建，这一步的开销被放大十几倍，
     * 直接把 system_server 拖住。
     *
     * <p>现在矢量只按固定超采样尺寸栅格化一次（带 tint），之后任何目标尺寸都退化成
     * {@code drawBitmap} 缩放，比矢量栅格化快一到两个数量级；顺带让平时的换肤/拖动滑块也变便宜。
     *
     * <p>颜色被烘进位图，所以**换色必须清这个缓存** —— 见 {@link #refreshCursor()}。
     */
    private final LruCache<Integer, Bitmap> layerCache =
            new LruCache<Integer, Bitmap>(LAYER_CACHE_BYTES) {
                @Override
                protected int sizeOf(Integer key, Bitmap value) {
                    return value.getByteCount();
                }
            };
    /** 画缓存层用的画笔：必须开 FILTER_BITMAP_FLAG，缩放才平滑。 */
    private final Paint layerPaint = new Paint(
            Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    /** {@link #layerRasterSize()} 的记忆值（0 = 还没算）。 */
    private volatile int layerRasterSizePx;
    /**
     * 每个光标类型一张「sprite 画布」位图，**尺寸在整段动画里恒定**。
     *
     * <p>这是让放大动画变便宜的关键：动画的每一帧都只是把图案在这张画布上重画大一点，
     * 画布本身既不改尺寸也不重新分配 —— 于是
     * <ol>
     *   <li>native 那边的 sprite 尺寸从头到尾不变，重载不需要重建/缩放 sprite；</li>
     *   <li>每帧的生产垃圾为 0（原来每帧每个类型都要 {@code Bitmap.createBitmap} 一张
     *       几百 KB 的位图，那是 system_server 里实打实的 GC 压力）。</li>
     * </ol>
     *
     * <p>画布尺寸取「本轮动画的最大尺寸」；静止时等于图案尺寸，没有额外内存开销。
     * 尺寸变了（动画档位变化）由 {@link #spriteFor} 自动换新。
     */
    private final Map<Integer, Bitmap> spriteCache = new ConcurrentHashMap<>();
    /** 串行化「取画布 + 重绘」：动画期间同一张画布会被反复重画，不能两个线程同时画。 */
    private final Object spriteLock = new Object();
    /**
     * material 的 wait 图标（24 帧动画）按「scale × 4」粒度缓存的小 LRU。
     * 条目数按 8 个封顶：每个条目是一整套 24 帧位图，不按字节算也得有上限。
     */
    private final LruCache<Integer, Object> waitIconCache = new LruCache<Integer, Object>(8);
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
        this.shake = new CursorShake(module, this);
    }

    // -------------------------------------------------------- 摇晃放大

    /**
     * 摇晃放大的「目标」系数：一次摇晃命中就抬一档，停手后回到 1.0。
     *
     * <p>1.0 = 不放大。由 {@link CursorShake} 的事件线程写，动画线程读。
     */
    private volatile float targetBoost = 1.0f;

    /**
     * 当前**实际用于渲染**的系数：由动画逐帧逼近 {@link #targetBoost}。
     *
     * <p>{@link #buildIcon(int)} 在热点路径上只读这个 volatile。
     */
    private volatile float currentBoost = 1.0f;

    /**
     * 动画帧间隔（毫秒）。帧**数**由偏好的「动画帧数」决定，帧间隔固定。
     *
     * <p>光标图标是烘焙进位图的，「改大小」只能是离散帧 —— 每一帧都要让 native 重拉一次 sprite。
     * 所以「平滑程度」和「开销」是同一件事的两面，直接交给用户在界面上选（1 帧 = 不播动画）。
     */
    /**
     * 动画帧间隔：固定 60fps。**帧数只决定动画时长**（帧数 × 这个间隔），不决定平滑度上限 ——
     * 平滑度取决于每帧那次 native 重拉能不能在这个间隔内跑完。
     */
    private static final long BOOST_STEP_MS = 16L;

    /**
     * 持续摇晃时的每档增幅：每再命中一次摇晃，目标倍数就乘这个系数。
     *
     * <p>所以「一直摇」会一直变大，直到 {@link #MAX_RENDERED_SCALE}（或位图上限）兜住。
     */
    private static final float PROGRESSIVE_GROWTH = 1.35f;

    private final AtomicBoolean boostAnimating = new AtomicBoolean();
    private volatile ScheduledExecutorService boostExecutor;
    /** 串行化 native 重载：动画帧与偏好变更的合并重载可能同时到。 */
    private final Object reloadLock = new Object();

    // ---- 动画时间轴（{@link #retargetBoost} 重置，动画线程读取）----
    /** 本次动画的起点系数。 */
    private volatile float boostAnimFrom = 1.0f;
    /** 本次动画的起点时刻（uptimeMillis）。 */
    private volatile long boostAnimStartMs;
    /** 本次动画的总时长（毫秒）= 帧数 × {@link #BOOST_STEP_MS}。 */
    private volatile long boostAnimDurationMs = BOOST_STEP_MS;

    /**
     * 诊断：一次动画期间重建了多少张图标、每帧的重载耗时。
     *
     * <p>{@code builds} 反映 native 每次重拉会来要多少种图标；{@code reloadMs} 是
     * {@code native.reloadPointerIcons()} 的累计耗时 —— 如果 builds 很小而 reloadMs 很大，
     * 瓶颈就在 native 侧，只能靠减少帧数。
     */
    private final AtomicInteger boostAnimBuilds = new AtomicInteger();
    private final AtomicInteger boostAnimTicks = new AtomicInteger();
    private volatile boolean boostAnimCounting;
    private final AtomicLong boostAnimReloadNanos = new AtomicLong();

    /**
     * 一次摇晃命中：抬高目标倍数并启动动画。
     *
     * <p>首次给用户配置的倍数（`放大倍数`）；之后每命中一次再乘 {@link #PROGRESSIVE_GROWTH}，
     * 所以一直摇会持续变大。上限是「渲染总缩放不超过 {@link #MAX_RENDERED_SCALE}」，
     * 也就是用户把「光标大小」调得越大，留给摇晃放大的余量越小。
     */
    void onShake() {
        float base = HookPrefs.cursorShakeBoost();
        if (!(base > 1f)) {
            return;
        }
        float max = maxBoost();
        float next = targetBoost <= 1.001f ? base : targetBoost * PROGRESSIVE_GROWTH;
        next = Math.min(next, max);
        // 只在「摇晃命中」时打一行，调手感时用它可以看清阶梯走到哪一档了
        Log.i("FancyPad", "event=shake_boost target=" + next + " max=" + max
                + " current=" + currentBoost);
        retargetBoost(next);
    }

    /** 摇晃停手、保持时长走完：目标回到用户设定的大小，同样走动画。 */
    void onShakeIdle() {
        if (targetBoost == 1.0f && currentBoost == 1.0f) {
            return;
        }
        retargetBoost(1.0f);
    }

    /** 关掉开关 / 关掉光标接管：立刻回到底，不播动画（用户明确要求"别放了"）。 */
    void resetShakeBoost() {
        targetBoost = 1.0f;
        if (currentBoost == 1.0f) {
            return;
        }
        currentBoost = 1.0f;
        invalidateRenderedIcons();
    }

    /** 在「渲染总缩放不超过 MAX_RENDERED_SCALE」的前提下，摇晃放大最多还能乘多少。 */
    private float maxBoost() {
        SharedPreferences p = prefs;
        float userScale = p == null ? 1.0f : userScaleOf(p);
        return Math.max(1.0f, MAX_RENDERED_SCALE / userScale);
    }

    /**
     * 重新设定放大目标并启动/续播动画。
     *
     * <p>动画按**时间轴**推进，不按「还差几帧」：每次改目标都把起点重置为当前值，
     * 然后按 {@code 帧数 × BOOST_STEP_MS} 的时长、用缓出曲线走完。
     * 这样做的好处是 —— 就算某一帧的 native 重拉慢了，后面那一帧会按真实流逝时间**直接追上进度**，
     * 动画总时长不受影响，只是那一瞬间掉一点平滑度；不会像"上一帧跑完再排下一帧"那样越拖越长。
     *
     * <p>模块侧每帧的开销已经被压到接近 0（复用固定尺寸画布、零分配、只重画被要到的类型、
     * 矢量预栅格化），所以帧数可以按正常动画给足；真正每帧都跑不掉的只有一次
     * {@code native.reloadPointerIcons()}。
     */
    private void retargetBoost(float next) {
        targetBoost = next;
        boostAnimFrom = currentBoost;
        boostAnimStartMs = SystemClock.uptimeMillis();
        boostAnimDurationMs = Math.max(1L,
                Math.max(1, HookPrefs.cursorShakeFrames()) * BOOST_STEP_MS);
        if (boostAnimating.compareAndSet(false, true)) {
            boostAnimBuilds.set(0);
            boostAnimReloadNanos.set(0L);
            boostAnimCounting = true;
            boostExecutor().schedule(this::boostStep, BOOST_STEP_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void boostStep() {
        try {
            boostAnimTicks.incrementAndGet();
            long elapsed = SystemClock.uptimeMillis() - boostAnimStartMs;
            long duration = boostAnimDurationMs;
            float target = targetBoost;
            float from = boostAnimFrom;
            float progress = duration <= 0L ? 1f : Math.min(1f, (float) elapsed / duration);
            boolean done = progress >= 1f;
            // 缓出：1-(1-p)² —— 起步快、收尾软
            float eased = 1f - (1f - progress) * (1f - progress);
            float next = done ? target : from + (target - from) * eased;
            if (next != currentBoost) {
                currentBoost = next;
                // 动画帧直接重载，不走 scheduleReload 的 150ms 合并窗口，
                // 否则整段动画会被压成两三帧、看起来还是跳变。
                invalidateFrameNow();
            }
            if (done) {
                boostAnimating.set(false);
                boostAnimCounting = false;
                Log.i("FancyPad", "event=boost_anim_done builds=" + boostAnimBuilds.get()
                        + " reload_ms=" + (boostAnimReloadNanos.get() / 1_000_000L)
                        + " ticks=" + boostAnimTicks.get()
                        + " final=" + currentBoost);
                return;
            }
            boostExecutor().schedule(this::boostStep, BOOST_STEP_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            boostAnimating.set(false);
            boostAnimCounting = false;
        }
    }

    /**
     * 动画帧：请 native 重拉 sprite。
     *
     * <p>**不清图标缓存**：缓存里每条都记着自己当时的放大系数，native 来要时发现系数对不上才重画，
     * 于是每帧只重画「真正被要到的」那几种类型，而不是全部。
     */
    private void invalidateFrameNow() {
        long t0 = SystemClock.uptimeMillis();
        try {
            doReload();
        } finally {
            if (boostAnimCounting) {
                boostAnimReloadNanos.addAndGet((SystemClock.uptimeMillis() - t0) * 1_000_000L);
            }
        }
    }

    private ScheduledExecutorService boostExecutor() {
        ScheduledExecutorService e = boostExecutor;
        if (e != null) {
            return e;
        }
        synchronized (this) {
            if (boostExecutor == null) {
                boostExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "dsh-cursor-boost");
                    t.setDaemon(true);
                    return t;
                });
            }
            return boostExecutor;
        }
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
                                lastIconKey = key;
                                float boost = currentBoost;
                                CachedIcon cached = iconCache.get(key);
                                // 只有「按同一个放大系数渲染过」的才算命中。
                                // 放大动画每一帧系数都在变，于是只有 native 真正来要的那几种类型会重画，
                                // 而不是（像原来那样清空整个缓存）每种类型都重画一遍。
                                if (cached != null && Math.abs(cached.boost - boost) < BOOST_EPSILON) {
                                    return cached.icon;         // 稳态：一次查表，不再重绘
                                }
                                int generation = themeGeneration;
                                Object custom = buildIcon(type);
                                if (custom != null) {
                                    if (generation == themeGeneration) {
                                        iconCache.put(key, new CachedIcon(custom, boost));
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

        // 3) 摇晃放大：观测指针位置流（只读，不拦截事件）
        try {
            shake.install(cl);
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
                    // 摇晃放大被关掉（或光标接管被关掉）：立刻还原，不等保持时长走完
                    if ((PrefKeys.CURSOR_SHAKE_ENABLED.equals(key)
                            && !sp.getBoolean(PrefKeys.CURSOR_SHAKE_ENABLED, false))
                            || (PrefKeys.CURSOR_ENABLED.equals(key)
                            && !sp.getBoolean(PrefKeys.CURSOR_ENABLED, false))) {
                        resetShakeBoost();
                    }
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
        invalidateRenderedIcons();
        try {
            customCache.evictAll();
            missingRemote.clear();
            // 矢量层的颜色是烘进位图里的，换预设/换色必须连它一起丢，否则颜色不跟着变
            layerCache.evictAll();
            // wait 动画图标只在 material 预设用到，换预设时没必要留着
            waitIconCache.evictAll();
            // density 可能随显示变化，栅格尺寸重算
            layerRasterSizePx = 0;
        } catch (Throwable ignored) {
        }
    }

    /**
     * 只让「已渲染的图标」失效，并请求 native 重拉 —— 预设与配色都没变时用这个。
     *
     * <p>摇晃放大走这条：导入的位图缓存（{@link #customCache}，上限 16MB）与
     * Remote File 的负缓存都没必要跟着丢，否则每次摇晃都要重走 binder 把 25 张图再读一遍。
     */
    private void invalidateRenderedIcons() {
        try {
            iconCache.clear();
            themeGeneration++;
        } catch (Throwable ignored) {
        }
        scheduleReload();
    }

    /**
     * 合并 native 重载请求：拖滑块 / 连续摇晃时只做一次「清 sprite + 逐个类型重新要图标」。
     */
    private void scheduleReload() {
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
        // 动画帧（dsh-cursor-boost）与偏好变更的合并重载（dsh-cursor-reload）在不同线程上，
        // 都要求 native 重填 sprite；串行化，避免两处同时清 native 缓存。
        synchronized (reloadLock) {
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
        if (boostAnimCounting) {
            boostAnimBuilds.incrementAndGet();
        }
        int preset = p.getInt("preset", 0);
        if (type == TYPE_WAIT && preset == PRESET_MATERIAL) {
            Object anim = animatedWait(res, renderScaleOf(p));
            if (anim != null) {
                return anim;
            }
        }
        if (!hasContent(row, preset)) {
            return null;        // 这个预设下该类型没有可画内容，别白建一张位图再丢掉
        }
        // 本次「图案」要画多大（含摇晃放大的动画系数）。
        float scale = renderScaleOf(p);
        float density = ctx.getResources().getDisplayMetrics().density;
        float baseSize = 24f * density;

        // sprite 画布边长：取「本轮动画的最大尺寸」与「当前图案尺寸」的较大者。
        // 动画期间它恒定不变 —— 于是 native 那边的 sprite 尺寸从头到尾不变，每帧只是在同一张
        // 画布上把图案重画大一点；静止时它就等于图案尺寸，没有额外开销。
        float canvasScale = spriteCanvasScale(p);
        int size = Math.max(4, Math.min(MAX_ICON_SIZE, Math.round(baseSize * canvasScale)));
        // 图案画在画布左上角：热点就是从图案左上角量的，所以热点不受画布尺寸影响
        int artSize = Math.max(1, Math.min(size, Math.round(baseSize * scale)));
        // 只有真的被位图上限截断时才改用截断后的缩放来算热点，否则沿用 scale
        float effScale = baseSize * scale > size ? artSize / baseSize : scale;

        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG |
                Paint.DITHER_FLAG);
        Rect bounds = new Rect(0, 0, artSize, artSize);

        try {
            String themeKey;
            int themeIdx;
            // 热点（0.1dp）：默认用 AOSP 官方热点，可改色/原图主题各用自己仓库里的热点
            int hotX10 = row[3];
            int hotY10 = row[4];
            Bitmap out;
            // 同类型复用同一张画布、就地重绘：动画每帧都要重建，若每帧新建位图，
            // system_server 会持续产生「几百 KB × 类型数」的垃圾，GC 抖动本身就是卡顿来源。
            // 整段绘制串行化，避免两个线程同时往同一张画布上画。
            synchronized (spriteLock) {
                out = spriteFor(type, size);
                out.eraseColor(Color.TRANSPARENT);
                Canvas canvas = new Canvas(out);
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
            }

            float hotX = hotX10 / 10f * density * effScale;
            float hotY = hotY10 / 10f * density * effScale;
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

    /**
     * material 的 wait 是 24 帧动画：借框架 loadResource + AnimationDrawable 生成多帧图标。
     *
     * <p>{@code loadResource} 会把 24 帧全部解出来按 scale 缩放，单次就很贵 —— 放到放大动画的
     * 逐帧重建里就是灾难（每帧 24 张图）。所以按 scale 的 0.25 粒度做个小 LRU：
     * 阶梯式摇晃会反复经过同一批倍数，第二次起直接命中。
     */
    private Object animatedWait(Resources res, float scale) {
        Integer bucket = Math.round(scale * 4f);
        Object cached = waitIconCache.get(bucket);
        if (cached != null) {
            return cached;
        }
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
            load.invoke(icon, res, R.drawable.cursor_wait_icon, null, bucket / 4f);
            waitIconCache.put(bucket, icon);
            return icon;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 取该类型的 sprite 画布：尺寸相同就复用同一张位图（动画每帧都重画它，不重新分配）。
     * 尺寸变了（动画档位上升/回落）才换一张新的。
     */
    private Bitmap spriteFor(int type, int size) {
        Bitmap cached = spriteCache.get(type);
        if (cached != null && !cached.isRecycled() && cached.getWidth() == size) {
            return cached;
        }
        Bitmap fresh = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        spriteCache.put(type, fresh);
        return fresh;
    }

    /**
     * sprite 画布的渲染缩放：取「本轮动画的峰值」与「当前值」的较大者。
     *
     * <p>放大时峰值 = 目标，缩回时峰值 = 当前 —— 两种情况画布都够大，
     * 所以图案从小长到大（或反过来）的整个过程中画布尺寸不变。
     * 没开摇晃放大时峰值恒为 1，画布就等于图案尺寸，与改动前的行为一致。
     */
    private float spriteCanvasScale(SharedPreferences p) {
        float userScale = userScaleOf(p);
        float peak = Math.max(currentBoost, targetBoost);
        return Math.max(0.3f, Math.min(MAX_RENDERED_SCALE, userScale * peak));
    }

    /** 用户自己设定的大小（0.3~3.0），不含摇晃放大。 */
    private static float userScaleOf(SharedPreferences p) {
        return Math.max(0.3f, Math.min(3.0f, p.getInt("scale", 100) / 100f));
    }

    /**
     * 实际用于渲染的缩放 = 用户设定 × 当前动画系数，再夹到 {@link #MAX_RENDERED_SCALE}。
     *
     * <p>摇晃放大只是临时乘一个系数，用户设置本身始终不动 —— 动画回落到 1.0 后，
     * 光标自然回到用户设定的大小。
     */
    private float renderScaleOf(SharedPreferences p) {
        return Math.max(0.3f, Math.min(MAX_RENDERED_SCALE, userScaleOf(p) * currentBoost));
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

    /**
     * 画一层矢量（AOSP 的填充层 / 描边层）。
     *
     * <p>不再每次现场栅格化矢量：改成从 {@link #layerCache} 取「按固定超采样尺寸栅格化好、
     * 且已染好色」的位图，再 {@code drawBitmap} 缩放到目标 bounds —— 这是放大动画不卡的关键。
     */
    private void drawLayer(Canvas canvas, Resources res, int resId, int color, Rect bounds) {
        Bitmap layer = layerCache.get(resId);
        if (layer == null) {
            layer = rasterizeLayer(res, resId, color);
            if (layer == null) {
                return;
            }
            layerCache.put(resId, layer);
        }
        canvas.drawBitmap(layer, null, bounds, layerPaint);
    }

    /** 把矢量层按固定超采样尺寸栅格化一次（带 tint）。失败返回 null，交回调用方的兜底逻辑。 */
    private Bitmap rasterizeLayer(Resources res, int resId, int color) {
        try {
            Drawable d = res.getDrawable(resId, null);
            if (d == null) {
                return null;
            }
            int size = layerRasterSize();
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            d.setTint(color);
            d.setBounds(0, 0, size, size);
            d.draw(new Canvas(out));
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 矢量层的栅格化边长：按「最大渲染尺寸」算，即 {@code 24dp × density × MAX_RENDERED_SCALE}，
     * 上限 {@link #MAX_ICON_SIZE}。比它小的目标尺寸都是降采样（清晰），只有摇到顶才是 1:1。
     */
    private int layerRasterSize() {
        int size = layerRasterSizePx;
        if (size > 0) {
            return size;
        }
        float density = 1f;
        try {
            Context ctx = cacheContext;
            if (ctx != null) {
                density = ctx.getResources().getDisplayMetrics().density;
            }
        } catch (Throwable ignored) {
        }
        size = Math.max(4, Math.min(MAX_ICON_SIZE,
                Math.round(24f * density * MAX_RENDERED_SCALE)));
        layerRasterSizePx = size;
        return size;
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
