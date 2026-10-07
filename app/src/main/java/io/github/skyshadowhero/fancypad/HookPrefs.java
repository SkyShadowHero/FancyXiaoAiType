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
    private static volatile boolean toolbarEnabled = false;
    private static volatile float toolbarCornerDp = PrefKeys.TOOLBAR_CORNER_DEFAULT;
    private static volatile float toolbarTextSp = PrefKeys.TOOLBAR_TEXT_DEFAULT;
    private static volatile boolean rightClickAsLongPress = false;

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

    private static void refresh(SharedPreferences p) {
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
            rightClickAsLongPress = p.getBoolean(PrefKeys.RIGHTCLICK_AS_LONGPRESS, false);
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

    // ---- 右键改长按（应用进程侧） ----

    public static boolean rightClickAsLongPress() {
        return rightClickAsLongPress;
    }
}
