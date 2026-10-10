package io.github.skyshadowhero.fancypad;

import android.content.SharedPreferences;

import io.github.libxposed.api.XposedInterface;

/**
 * Hook 进程侧的开关缓存。
 *
 * <p>三个功能域分布在三个进程里（{@code com.xiaomi.type} / {@code system} /
 * {@code com.android.systemui}），每边都通过这里拿到同一份 RemotePreferences 的快照。
 *
 * <p>为什么不做「每次调用都读一遍偏好」：光标渲染在 {@code PointerIconCache} 的热点路径上，
 * 平行窗口的跳切判定也在动画路径上；RemotePreferences 取值虽然多数命中本地缓存，
 * 但第一次取仍要走 binder。这里的做法是——
 * <ol>
 *   <li>{@link #bind} 时读一次快照；</li>
 *   <li>注册一个共享偏好监听器，之后改动即时刷新这几个 volatile 字段；</li>
 *   <li>Hook 侧只读 volatile，不做取偏好动作。</li>
 * </ol>
 *
 * <p>失败一律回退成「默认关闭」，与开关的默认值一致：
 * 读不到偏好时模块什么都不做（所有功能默认关闭），而不是静默生效。
 */
public final class HookPrefs {

    private static volatile boolean bound;

    @SuppressWarnings("unused")
    private static volatile SharedPreferences prefs;
    /** 监听器必须持强引用，否则会被 GC 回收导致回调静默失效。 */
    @SuppressWarnings("unused")
    private static volatile SharedPreferences.OnSharedPreferenceChangeListener listener;

    // ---- 快照（Hook 侧只读这些字段） ----
    private static volatile boolean cursorEnabled = false;
    private static volatile boolean embeddingEnabled = false;
    private static volatile boolean folmeDisabled = true;
    private static volatile boolean mergeDisabled = true;
    private static volatile boolean jumpCutDisabled = true;
    /** 小窗控制器总开关；关掉后这一域所有 Hook 直接放行。 */
    private static volatile boolean captionEnabled = true;
    /** 小窗控制菜单里「关闭」按钮的图标 × → −。 */
    private static volatile boolean captionCloseAsMinus = false;
    /** 小窗控制菜单里追加红色「彻底关闭」（forceStop）按钮。 */
    private static volatile boolean captionForceClose = false;
    /** 控制菜单里隐藏的按钮（逗号分隔的 PrefKeys.CB_*）；空串 = 全显示。 */
    private static volatile String captionButtonHidden = "";
    /** 控制菜单的按钮顺序（逗号分隔的 PrefKeys.CB_*）。 */
    private static volatile String captionButtonOrder = PrefKeys.CAPTION_BUTTON_DEFAULT_ORDER;
    /** 隐藏「当前状态对应的按钮」（全屏藏全屏 / 小窗藏小窗）。 */
    private static volatile boolean captionHideCurrentState = false;
    /** 始终显示「新窗口」按钮（框架只在支持多实例时才加）。 */
    private static volatile boolean captionAlwaysNewWindow = false;
    /** 隐藏三个控制点（不画，但点击区域还在）。 */
    private static volatile boolean captionHideDots = false;
    private static volatile boolean toolbarEnabled = false;
    private static volatile float toolbarCornerDp = PrefKeys.TOOLBAR_CORNER_DEFAULT;
    private static volatile float toolbarTextSp = PrefKeys.TOOLBAR_TEXT_DEFAULT;
    private static volatile boolean appMenuEnabled = false;
    private static volatile boolean appMenuWebviewEnabled = true;
    /** 生效白名单（空串 = 不限制）。只读字符串，判断时按逗号切分。 */
    private static volatile String appMenuApps = "";
    private static volatile float appMenuCornerDp = PrefKeys.APPMENU_CORNER_DEFAULT;
    private static volatile float appMenuTextSp = PrefKeys.APPMENU_TEXT_DEFAULT;
    private static volatile float appMenuPaddingHDp = PrefKeys.APPMENU_PADDING_H_DEFAULT;
    private static volatile float appMenuPaddingVDp = PrefKeys.APPMENU_PADDING_V_DEFAULT;
    /** 随手写总开关（system_server 与 com.xiaomi.type 两个进程都读）。 */
    private static volatile boolean stylusEnabled = false;
    /** 停笔识别延迟（毫秒）：停笔多久之后把攒下的笔迹送去识别。 */
    private static volatile float stylusDelayMs = PrefKeys.STYLUS_DELAY_DEFAULT;
    /**
     * 让小爱进入随手写白名单。默认**开** —— 缺键必须走"开"：
     * 否则系统设置里的随手写开关会因为「小爱不支持」而打不开（死锁）。
     */
    private static volatile boolean stylusWhitelist = true;
    /** 是否显示笔迹（输入法进程的画布用）。 */
    private static volatile boolean stylusInkEnabled = true;
    /** 书写手势总开关（圈选/尖尖插入/划掉删除）。 */
    private static volatile boolean stylusGestureEnabled = false;
    /** 是否优先用讯飞 HCR 引擎识别（而不是系统笔引擎）。默认开。 */
    private static volatile boolean stylusIflytek = true;
    /** 手写工具条开关（默认关，见 PrefKeys.STYLUS_TOOLBAR 说明）。 */
    private static volatile boolean stylusToolbar = false;
    /** 笔迹颜色（ARGB）。 */
    private static volatile int stylusInkColor = PrefKeys.STYLUS_INK_COLOR_DEFAULT;
    /** 笔迹线宽（px）。 */
    private static volatile float stylusInkWidthPx = PrefKeys.STYLUS_INK_WIDTH_DEFAULT;
    /** 上次刷新的时刻（uptimeMillis），供 {@link #refreshIfStale} 判断快照新旧。 */
    private static volatile long lastRefreshAt = 0L;

    /** 随手写偏好的低频自愈刷新间隔（毫秒）。 */
    private static final long STYLUS_REFRESH_MS = 3000L;

    /** 同一时刻只允许有一次刷新在途，避免 Hook 热点把 binder 调用堆起来。 */
    private static final java.util.concurrent.atomic.AtomicBoolean refreshInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 刷新专用后台线程（守护线程，只在真的需要刷新时才建）。 */
    private static volatile java.util.concurrent.Executor refreshExecutor;

    /**
     * 绑定偏好（首次调用）：读一次快照并注册变更监听。
     */
    public static synchronized void bind(XposedInterface module) {
        bound = true;
        try {
            SharedPreferences p = module.getRemotePreferences(PrefKeys.GROUP);
            prefs = p;
            refresh(p);
            SharedPreferences.OnSharedPreferenceChangeListener l = (sp, key) -> refresh(sp);
            listener = l;
            p.registerOnSharedPreferenceChangeListener(l);
        } catch (Throwable ignored) {
            // 读不到偏好就按默认值（全开）工作
        }
    }

    /**
     * 强制从框架重读一遍偏好。
     *
     * 为什么不只靠 {@link #bind} 时的快照 + 变更监听：实测**已注入的应用进程**不一定能收到
     * 框架的偏好变更通知，于是 App 里关掉开关后，Hook 侧仍拿着旧快照（表现为「关了没生效」）。
     * 菜单弹出是低频事件，这里重新走一次 getRemotePreferences 的代价可接受。
     */
    public static synchronized void rebind(XposedInterface module) {
        try {
            SharedPreferences p = module.getRemotePreferences(PrefKeys.GROUP);
            prefs = p;
            bound = true;
            refresh(p);
        } catch (Throwable ignored) {
        }
    }

    private static void refresh(SharedPreferences p) {
        lastRefreshAt = android.os.SystemClock.uptimeMillis();
        if (p == null) {
            return;
        }
        try {
            cursorEnabled = p.getBoolean(PrefKeys.CURSOR_ENABLED, false);
        } catch (Throwable ignored) {
        }
        try {
            embeddingEnabled = p.getBoolean(PrefKeys.EMBEDDING_ENABLED, false);
        } catch (Throwable ignored) {
        }
        try {
            folmeDisabled = p.getBoolean(PrefKeys.EMBEDDING_FOLME_DISABLE, true);
        } catch (Throwable ignored) {
        }
        try {
            mergeDisabled = p.getBoolean(PrefKeys.EMBEDDING_MERGE_DISABLE, true);
        } catch (Throwable ignored) {
        }
        try {
            jumpCutDisabled = p.getBoolean(PrefKeys.EMBEDDING_JUMPCUT_DISABLE, true);
        } catch (Throwable ignored) {
        }
        try {
            captionEnabled = p.getBoolean(PrefKeys.CAPTION_ENABLED, true);
        } catch (Throwable ignored) {
        }
        try {
            captionCloseAsMinus = p.getBoolean(PrefKeys.CAPTION_CLOSE_AS_MINUS, false);
        } catch (Throwable ignored) {
        }
        try {
            captionForceClose = p.getBoolean(PrefKeys.CAPTION_FORCE_CLOSE, false);
        } catch (Throwable ignored) {
        }
        try {
            captionButtonHidden = p.getString(PrefKeys.CAPTION_BUTTON_HIDDEN, "");
        } catch (Throwable ignored) {
        }
        try {
            captionButtonOrder = p.getString(
                    PrefKeys.CAPTION_BUTTON_ORDER, PrefKeys.CAPTION_BUTTON_DEFAULT_ORDER);
        } catch (Throwable ignored) {
        }
        try {
            captionHideCurrentState = p.getBoolean(PrefKeys.CAPTION_HIDE_CURRENT_STATE, false);
        } catch (Throwable ignored) {
        }
        try {
            captionAlwaysNewWindow = p.getBoolean(PrefKeys.CAPTION_ALWAYS_NEW_WINDOW, false);
        } catch (Throwable ignored) {
        }
        try {
            captionHideDots = p.getBoolean(PrefKeys.CAPTION_HIDE_DOTS, false);
        } catch (Throwable ignored) {
        }
        try {
            toolbarEnabled = p.getBoolean(PrefKeys.TOOLBAR_ENABLED, false);
        } catch (Throwable ignored) {
        }
        try {
            toolbarCornerDp = p.getFloat(PrefKeys.TOOLBAR_CORNER_DP, PrefKeys.TOOLBAR_CORNER_DEFAULT);
        } catch (Throwable ignored) {
        }
        try {
            toolbarTextSp = p.getFloat(PrefKeys.TOOLBAR_TEXT_SP, PrefKeys.TOOLBAR_TEXT_DEFAULT);
        } catch (Throwable ignored) {
        }
        try {
            appMenuEnabled = p.getBoolean(PrefKeys.APPMENU_ENABLED, false);
        } catch (Throwable ignored) {
        }
        try {
            appMenuWebviewEnabled = p.getBoolean(PrefKeys.APPMENU_WEBVIEW_ENABLED, true);
        } catch (Throwable ignored) {
        }
        try {
            appMenuApps = p.getString(PrefKeys.APPMENU_APPS, "");
        } catch (Throwable ignored) {
        }
        try {
            appMenuCornerDp = p.getFloat(PrefKeys.APPMENU_CORNER_DP, PrefKeys.APPMENU_CORNER_DEFAULT);
        } catch (Throwable ignored) {
        }
        try {
            appMenuTextSp = p.getFloat(PrefKeys.APPMENU_TEXT_SP, PrefKeys.APPMENU_TEXT_DEFAULT);
        } catch (Throwable ignored) {
        }
        try {
            appMenuPaddingHDp = p.getFloat(
                    PrefKeys.APPMENU_PADDING_H_DP, PrefKeys.APPMENU_PADDING_H_DEFAULT);
        } catch (Throwable ignored) {
        }
        try {
            appMenuPaddingVDp = p.getFloat(
                    PrefKeys.APPMENU_PADDING_V_DP, PrefKeys.APPMENU_PADDING_V_DEFAULT);
        } catch (Throwable ignored) {
        }
        try {
            stylusEnabled = p.getBoolean(PrefKeys.STYLUS_ENABLED, false);
        } catch (Throwable ignored) {
        }
        try {
            stylusDelayMs = p.getFloat(PrefKeys.STYLUS_DELAY_MS, PrefKeys.STYLUS_DELAY_DEFAULT);
        } catch (Throwable ignored) {
        }
        // 注意默认值是 true：缺键时必须走"开"，否则系统设置里的随手写会因为
        // 「小爱不支持」而根本打不开（死锁），详见 PrefKeys.STYLUS_WHITELIST。
        try {
            stylusWhitelist = p.getBoolean(PrefKeys.STYLUS_WHITELIST, true);
        } catch (Throwable ignored) {
        }
        try {
            stylusInkEnabled = p.getBoolean(PrefKeys.STYLUS_INK_ENABLED, true);
        } catch (Throwable ignored) {
        }
        try {
            stylusGestureEnabled = p.getBoolean(PrefKeys.STYLUS_GESTURE_ENABLED, false);
        } catch (Throwable ignored) {
        }
        try {
            stylusIflytek = p.getBoolean(PrefKeys.STYLUS_IFLYTEK, true);
        } catch (Throwable ignored) {
        }
        try {
            stylusToolbar = p.getBoolean(PrefKeys.STYLUS_TOOLBAR, false);
        } catch (Throwable ignored) {
        }
        try {
            stylusInkColor = p.getInt(PrefKeys.STYLUS_INK_COLOR, PrefKeys.STYLUS_INK_COLOR_DEFAULT);
        } catch (Throwable ignored) {
        }
        try {
            stylusInkWidthPx = p.getFloat(PrefKeys.STYLUS_INK_WIDTH_PX, PrefKeys.STYLUS_INK_WIDTH_DEFAULT);
        } catch (Throwable ignored) {
        }
    }

    public static boolean cursorEnabled() {
        return cursorEnabled;
    }

    public static boolean embeddingEnabled() {
        return embeddingEnabled;
    }

    public static boolean folmeDisabled() {
        return folmeDisabled;
    }

    public static boolean mergeDisabled() {
        return mergeDisabled;
    }

    public static boolean jumpCutDisabled() {
        return jumpCutDisabled;
    }

    // ---- 小窗控制菜单（SystemUI 侧） ----

    /** 小窗控制器总开关。 */
    public static boolean captionEnabled() {
        return captionEnabled;
    }

    /** 控制菜单里「关闭」按钮的图标 × → −。 */
    public static boolean captionCloseAsMinus() {
        return captionCloseAsMinus;
    }

    /** 控制菜单里追加红色「彻底关闭」按钮。 */
    public static boolean captionForceClose() {
        return captionForceClose;
    }

    /** 控制菜单里隐藏的按钮（逗号分隔的 key）。 */
    public static String captionButtonHidden() {
        return captionButtonHidden;
    }

    /** 控制菜单的按钮顺序（逗号分隔的 key）。 */
    public static String captionButtonOrder() {
        return captionButtonOrder;
    }

    /** 隐藏「当前状态对应的按钮」。 */
    public static boolean captionHideCurrentState() {
        return captionHideCurrentState;
    }

    /** 始终显示「新窗口」按钮。 */
    public static boolean captionAlwaysNewWindow() {
        return captionAlwaysNewWindow;
    }

    /** 隐藏三个控制点。 */
    public static boolean captionHideDots() {
        return captionHideDots;
    }


    // ---- 文本选择菜单（SystemUI 侧） ----

    public static boolean toolbarEnabled() {
        return toolbarEnabled;
    }

    public static float toolbarCornerDp() {
        return toolbarCornerDp;
    }

    public static float toolbarTextSp() {
        return toolbarTextSp;
    }

    // ---- 随手写（system_server + com.xiaomi.type） ----

    /**
     * 随手写总开关。关掉时两处 hook 都直接放行，等于模块不碰随手写。
     *
     * <p>带低频自愈刷新：注入进程不一定收得到偏好变更通知，见 {@link #STYLUS_REFRESH_MS}。
     */
    public static boolean stylusEnabled(XposedInterface module) {
        refreshIfStale(module, STYLUS_REFRESH_MS);
        return stylusEnabled;
    }

    /**
     * 距上次刷新超过 {@code maxAgeMs} 时，安排一次**后台**重读。
     *
     * <p>为什么必须放后台：这个方法的调用点在 system_server 的
     * {@code InputMethodInfo.supportsStylusHandwriting()} 与
     * {@code InputMethodBindingController.getSupportsStylusHandwriting()} 里面 ——
     * 前者是 MIUI 设置页与 {@code HandwritingConfigManager} 都会调的方法，可能是主线程。
     * 而 {@link #rebind} 要 {@code getRemotePreferences()} 走一次 binder 到 lspd；
     * 在 system_server 主线程上等 binder，一旦 lspd 卡住就是整机卡顿（甚至 watchdog）。
     *
     * <p>所以这里只**触发**刷新：立刻打时间戳（避免每次调用都往下走）、
     * 同一时刻只允许一次在途（避免热点路径把调用堆起来），真正的 binder 读放到独立线程。
     * 代价是调用方这一次读到的仍是上一份快照 —— 快照本来就是低频自愈用的，可接受。
     */
    public static void refreshIfStale(final XposedInterface module, long maxAgeMs) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastRefreshAt < maxAgeMs) {
            return;
        }
        lastRefreshAt = now;
        if (!refreshInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            executor().execute(() -> {
                try {
                    rebind(module);
                } catch (Throwable ignored) {
                    // rebind 内部已各自兜底；这里只保证 in-flight 标志一定被放开
                } finally {
                    refreshInFlight.set(false);
                }
            });
        } catch (Throwable t) {
            // 线程建不出来（极端情况）也不能让标志卡住，否则以后再也不刷新
            refreshInFlight.set(false);
        }
    }

    /**
     * 显式触发一次后台刷新，不等节流。
     *
     * <p>给「会话开始」这类低频时机用：注入进程不一定收得到偏好变更通知，
     * 靠 Hook 热点里的节流刷新又可能刚好卡在两次之间。
     */
    public static void refreshSoon(XposedInterface module) {
        refreshIfStale(module, 0L);
    }

    /** 刷新线程：单线程、守护线程，名字带 fancypad 便于在 ps/日志里认出来。 */
    private static java.util.concurrent.Executor executor() {
        java.util.concurrent.Executor e = refreshExecutor;
        if (e != null) {
            return e;
        }
        synchronized (HookPrefs.class) {
            if (refreshExecutor == null) {
                refreshExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "fancypad-prefs");
                    t.setDaemon(true);
                    return t;
                });
            }
            return refreshExecutor;
        }
    }

    /** 随手写总开关（读快照，不触发刷新）。 */
    public static boolean stylusEnabled() {
        return stylusEnabled;
    }


    /** 停笔后触发识别的延迟（毫秒，clamp 到输入法自己的 50~1000 量程）。 */
    public static float stylusDelayMs() {
        return stylusDelayMs;
    }

    /**
     * 让小爱进入随手写白名单（system_server 侧两个「声明」hook）。默认**开**。
     *
     * <p>带低频自愈刷新：system_server 是开机注入的，注入进程不一定收得到偏好变更通知。
     */
    public static boolean stylusWhitelist(XposedInterface module) {
        refreshIfStale(module, STYLUS_REFRESH_MS);
        return stylusWhitelist;
    }

    // ---- 随手写 · 笔迹显示（输入法进程的画布读这些） ----

    /** 是否显示笔迹。 */
    public static boolean stylusInkEnabled() {
        return stylusInkEnabled;
    }

    /** 书写手势是否开启。 */
    public static boolean stylusGestureEnabled() {
        return stylusGestureEnabled;
    }

    /** 是否优先用讯飞 HCR 引擎识别。 */
    public static boolean stylusIflytek() {
        return stylusIflytek;
    }

    /**
     * 手写工具条是否启用。
     *
     * **当前硬性关闭（实验特性，暂停）**：它连续四轮都让手写会话出问题
     * （最近一次真机数据：两轮会话连一个笔事件都收不到、`INTERCEPTS_STYLUS` 残留），
     * 而我在没有可用触控笔的情况下无法自行验证。代码与开关位保留，
     * 等能在设备上闭环验证之后再打开。
     *
     * 之所以直接返回 false 而不是读偏好：要保证**任何设置都不可能**让这条路复活，
     * 否则手写会再次被拖垮。
     */
    public static boolean stylusToolbarEnabled() {
        return false;
    }

    /** 笔迹颜色（ARGB）。 */
    public static int stylusInkColor() {
        return stylusInkColor;
    }

    /** 笔迹线宽（px，直接用，不乘 density）。 */
    public static float stylusInkWidthPx() {
        return stylusInkWidthPx;
    }




    // ---- 右键菜单（目标应用进程侧） ----

    public static boolean appMenuEnabled() {
        return appMenuEnabled;
    }

    public static boolean appMenuWebviewEnabled() {
        return appMenuWebviewEnabled;
    }

    /** 白名单为空 = 不限制；否则要求包名在列表里。 */
    public static boolean appMenuAppAllowed(String pkg) {
        String list = appMenuApps;
        // **默认关闭**：没选任何应用 = 谁都不生效（用户要求）。
        if (list == null || list.trim().isEmpty()) {
            return false;
        }
        // 注意分隔符：App 侧用 MaterialPackages 编码，是**换行**分隔；这里兼容逗号与换行
        for (String part : list.split("[,\\n]")) {
            if (part.trim().equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    public static float appMenuCornerDp() {
        return appMenuCornerDp;
    }

    public static float appMenuTextSp() {
        return appMenuTextSp;
    }

    public static float appMenuPaddingHDp() {
        return appMenuPaddingHDp;
    }

    public static float appMenuPaddingVDp() {
        return appMenuPaddingVDp;
    }
}
