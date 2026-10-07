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
    /**
     * 摇晃放大（{@link CursorShake}）。
     *
     * <p>这几项是 system_server 里最热的一条读路径：鼠标事件可达每秒上千次，
     * 判定循环里只读这些 volatile，绝不碰 SharedPreferences。
     */
    private static volatile boolean cursorShakeEnabled = false;
    private static volatile float cursorShakeBoost = PrefKeys.CURSOR_SHAKE_BOOST_DEFAULT / 100f;
    private static volatile int cursorShakeReversals = PrefKeys.CURSOR_SHAKE_REVERSALS_DEFAULT;
    private static volatile int cursorShakeFrames = PrefKeys.CURSOR_SHAKE_FRAMES_DEFAULT;
    private static volatile long cursorShakeHoldMs = PrefKeys.CURSOR_SHAKE_HOLD_DEFAULT;
    private static volatile boolean embeddingEnabled = false;
    private static volatile boolean folmeDisabled = true;
    private static volatile boolean mergeDisabled = true;
    private static volatile boolean jumpCutDisabled = true;
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

    private HookPrefs() {}

    /** 每个进程绑定一次。可重复调用。 */
    public static synchronized void bind(XposedInterface module) {
        if (bound) {
            return;
        }
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
        if (p == null) {
            return;
        }
        try {
            cursorEnabled = p.getBoolean(PrefKeys.CURSOR_ENABLED, false);
        } catch (Throwable ignored) {
        }
        try {
            cursorShakeEnabled = p.getBoolean(PrefKeys.CURSOR_SHAKE_ENABLED, false);
        } catch (Throwable ignored) {
        }
        try {
            cursorShakeBoost = p.getInt(
                    PrefKeys.CURSOR_SHAKE_BOOST, PrefKeys.CURSOR_SHAKE_BOOST_DEFAULT) / 100f;
        } catch (Throwable ignored) {
        }
        try {
            cursorShakeReversals = p.getInt(
                    PrefKeys.CURSOR_SHAKE_REVERSALS, PrefKeys.CURSOR_SHAKE_REVERSALS_DEFAULT);
        } catch (Throwable ignored) {
        }
        try {
            cursorShakeFrames = p.getInt(
                    PrefKeys.CURSOR_SHAKE_FRAMES, PrefKeys.CURSOR_SHAKE_FRAMES_DEFAULT);
        } catch (Throwable ignored) {
        }
        try {
            cursorShakeHoldMs = p.getInt(
                    PrefKeys.CURSOR_SHAKE_HOLD_MS, PrefKeys.CURSOR_SHAKE_HOLD_DEFAULT);
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
    }

    public static boolean cursorEnabled() {
        return cursorEnabled;
    }

    // ---- 摇晃放大 ----

    public static boolean cursorShakeEnabled() {
        return cursorShakeEnabled;
    }

    /** 放大倍数（1.0 = 不放大）。*/
    public static float cursorShakeBoost() {
        return cursorShakeBoost;
    }

    /** 触发灵敏度：需要完成的换向次数。*/
    public static int cursorShakeReversals() {
        return cursorShakeReversals;
    }

    /** 放大/缩回动画的帧数：1 = 不播动画、一次到位。*/
    public static int cursorShakeFrames() {
        return cursorShakeFrames;
    }

    /** 放大后保持的时长（毫秒）。*/
    public static long cursorShakeHoldMs() {
        return cursorShakeHoldMs;
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
