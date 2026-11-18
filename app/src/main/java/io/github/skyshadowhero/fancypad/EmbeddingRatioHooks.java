package io.github.skyshadowhero.fancypad;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * FancyPad · 平行视界「分栏无级」域：作用域 = 目标应用自身。
 *
 * <p>两件事，都作用在 app 进程里的 {@code androidx.window.extensions.jar}
 * （反编译 /system_ext/framework/androidx.window.extensions.jar 证实）：
 *
 * <ol>
 *   <li><b>松手不吸附</b>：拖中间分隔线时比例本来是连续的，但松手会被吸附回固定档位
 *       （0.35 / 0.5 / 0.65），所以拖到哪儿都停不住。切点是
 *       {@code MiuiDividerPresenter.dividerPositionForSnapPoints(int, float)} ——
 *       {@code onFinishDragging} 里唯一的位置决策入口，arg0 就是松手瞬间的连续像素位置。
 *       直接返回它（自己 clamp 到系统 min/max）即「无级」，但必须保留 clamp，
 *       否则越界位置会把一侧压成 0 宽。</li>
 *   <li><b>拖动上下限 0.35/0.65 → 1/8、7/8</b>：系统的上下限来自
 *       {@code DividerAttributes.getPrimaryMinRatio()/getPrimaryMaxRatio()}。
 *       声明 {@code uses-library} 的 app 一般传 {@code RATIO_SYSTEM_DEFAULT(-1f)}，
 *       由 {@code DividerPresenter.sanitizeDividerAttributes()} 换成
 *       {@code DEFAULT_MIN_RATIO=0.35f / DEFAULT_MAX_RATIO=0.65f}。
 *       在这两个 getter 上把「系统默认」换成 0.125 / 0.875，
 *       sanitize 之后写回 {@code mProperties.mDividerAttributes} 的字段就是新范围，
 *       拖动 clamp、{@code calculateNewSplitRatio} 保存的比例、渲染层的手柄范围全都跟着变。</li>
 * </ol>
 *
 * <p>只改「可拖动分栏」（{@code DIVIDER_TYPE_DRAGGABLE}）：固定分栏的构造器要求上下限
 * 必须是 -1，硬改会抛 {@code IllegalStateException}，所以按类型放过。
 * app 自己声明过上下限（非 -1）时也放过，尊重应用的选择。
 */
public final class EmbeddingRatioHooks {

    private static final String CLS_DIVIDER =
            "androidx.window.extensions.embedding.MiuiDividerPresenter";
    private static final String CLS_ATTRS =
            "androidx.window.extensions.embedding.DividerAttributes";

    /** (int dividerPosition, float velocity) → int，松手停靠点决策。 */
    private static final String METHOD_SNAP_POINTS = "dividerPositionForSnapPoints";
    /** 无参实例方法，snap hook 里反射读边界做 clamp。 */
    private static final String METHOD_MIN = "calculateMinPosition";
    private static final String METHOD_MAX = "calculateMaxPosition";

    /** {@code DividerAttributes.RATIO_SYSTEM_DEFAULT}：交给系统决定，sanitize 后是 0.35/0.65。 */
    private static final float RATIO_SYSTEM_DEFAULT = -1.0f;
    /** {@code DividerAttributes.DIVIDER_TYPE_DRAGGABLE}：只有可拖动分栏才允许自定义上下限。 */
    private static final int DIVIDER_TYPE_DRAGGABLE = 2;
    /** 一侧最窄 1/8。 */
    private static final float MIN_RATIO = 0.125f;
    /** 一侧最宽 7/8。 */
    private static final float MAX_RATIO = 0.875f;

    /**
     * 偏好快照的自愈节流：已注入的应用进程不一定收得到框架的变更通知（与右键菜单同款问题），
     * 所以借「松手 / 分栏重算」这类低频时机在后台重读一次。
     */
    private static final long PREF_REFRESH_MS = 2000L;

    private final XposedInterface module;
    private volatile boolean installed;
    /**
     * 当前进程的**宿主**包名，来自 XposedEntry 的 package_ready；白名单按它判断。
     *
     * <p>只在第一次 install 时赋值：同一个宿主进程里，LSPosed 还会为它加载的共享库
     * （实机是 {@code com.google.android.webview}）再回调一次 package_ready，
     * 拿那次回调的包名去比白名单会永远对不上（表现为「开关是开的，但钩子全放行」）。
     */
    private volatile String appPkg;
    /** {@code DividerAttributes#getDividerType()}，安装时解析一次。 */
    private volatile Method dividerTypeMethod;

    /** 日志只留前几条做取证，避免逐次拖动刷屏。 */
    private final AtomicInteger snapLogs = new AtomicInteger();
    private final AtomicInteger boundLogs = new AtomicInteger();

    public EmbeddingRatioHooks(XposedInterface module) {
        this.module = module;
    }

    /**
     * 在目标应用进程安装。返回 true = 已装好（或这个 app 根本不适用）；
     * false = 目标类在、但一个钩子都没挂上，交给外层重试。
     * 任何异常都只记日志、返回 false，绝不抛给宿主应用。
     */
    public synchronized boolean install(ClassLoader classLoader, String pkg) {
        if (appPkg == null && pkg != null) {
            appPkg = pkg;
        }
        if (installed) {
            return true;
        }
        Class<?> divider;
        Class<?> attrs;
        try {
            divider = classLoader.loadClass(CLS_DIVIDER);
            attrs = classLoader.loadClass(CLS_ATTRS);
        } catch (ClassNotFoundException e) {
            // 没声明 uses-library=androidx.window.extensions 的 app 拿不到这两类，
            // 它的分栏不在 app 侧画，本域对它不适用。返回 true 让外层别再按
            // 「没装上」重试三轮 —— 作用域里绝大多数 app 都走这条。
            L.INSTANCE.i("event=embedding_ratio_skipped reason=target_absent pkg=" + appPkg);
            return true;
        }
        try {
            try {
                dividerTypeMethod = attrs.getDeclaredMethod("getDividerType");
                dividerTypeMethod.setAccessible(true);
            } catch (Throwable ignored) {
            }

            int ok = 0;
            int total = 0;

            // 1) 松手不吸附
            total++;
            if (hookIfPresent(divider, METHOD_SNAP_POINTS, 2, chain -> {
                // 松手 = 低频时机：顺手把偏好快照刷新一下，开关改完不用重启 app。
                HookPrefs.refreshIfStale(module, PREF_REFRESH_MS);
                if (!enabled() || !appAllowed()) {
                    return chain.proceed();
                }
                // arg0 = mPreDividerPosition，松手瞬间的连续像素位置。原实现会 snap 到
                // 1/2（居中吸附）或 min/max（甩动）；去掉吸附但必须保留 clamp。
                Object raw = chain.getArg(0);
                if (!(raw instanceof Integer)) {
                    return chain.proceed();
                }
                int pos = (Integer) raw;
                Object presenter = chain.getThisObject();
                int min = callInt(presenter, METHOD_MIN);
                int max = callInt(presenter, METHOD_MAX);
                if (min == Integer.MIN_VALUE || max == Integer.MIN_VALUE || min > max) {
                    L.INSTANCE.w("event=embedding_ratio_bounds_invalid min=" + min + " max=" + max);
                    return chain.proceed();
                }
                int clamped = Math.max(min, Math.min(max, pos));
                if (snapLogs.incrementAndGet() <= 6) {
                    L.INSTANCE.i("event=embedding_ratio_no_snap raw=" + pos + " clamped=" + clamped
                            + " min=" + min + " max=" + max);
                }
                return clamped;
            })) {
                ok++;
            }

            // 2) 拖动上下限：-1（系统默认）→ 1/8、7/8
            total++;
            if (hookAttrRatio(attrs, "getPrimaryMinRatio", MIN_RATIO)) {
                ok++;
            }
            total++;
            if (hookAttrRatio(attrs, "getPrimaryMaxRatio", MAX_RATIO)) {
                ok++;
            }

            if (ok == 0) {
                L.INSTANCE.w("event=embedding_ratio_hook_failed reason=none_hooked pkg=" + appPkg);
                return false;
            }
            installed = true;
            L.INSTANCE.i("event=embedding_ratio_install_ok pkg=" + appPkg + " hooks=" + ok + "/" + total
                    + " enabled=" + enabled() + " allowed=" + appAllowed());
            return true;
        } catch (Throwable t) {
            L.INSTANCE.w("event=embedding_ratio_install_failed msg=" + t);
        }
        return false;
    }

    // ------------------------------------------------------------ 工具

    /**
     * 热重载：旧句柄已被框架摘掉，放掉幂等位让 {@link #install} 重新挂一遍。
     * （热重载不重放生命周期，不清这个标记新代码会直接早退，表现为功能全没了。）
     */
    public synchronized void resetInstalled() {
        installed = false;
    }

    private static boolean enabled() {
        return HookPrefs.embeddingRatioEnabled();
    }

    /** 热重载后偏好可能变化，每次都按当前进程包名即时判断。 */
    private boolean appAllowed() {
        return HookPrefs.embeddingRatioAppAllowed(appPkg);
    }

    /**
     * 把 {@code DividerAttributes} 上返回 float 的比例 getter 钉到 {@code target}。
     *
     * <p>只替换「系统默认」（-1）：app 自己声明过上下限就尊重它。
     * 只对可拖动分栏生效：固定分栏构造器要求上下限必须是 -1。
     */
    private boolean hookAttrRatio(Class<?> attrs, String name, float target) {
        try {
            Method m = attrs.getDeclaredMethod(name);
            if (m.getReturnType() != float.class) {
                L.INSTANCE.w("event=embedding_ratio_hook_failed target=" + name
                        + " reason=wrong_return_type ret=" + m.getReturnType());
                return false;
            }
            m.setAccessible(true);
            module.hook(m)
                    .setId("embedding_ratio_" + name)
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        HookPrefs.refreshIfStale(module, PREF_REFRESH_MS);
                        Object raw = chain.proceed();
                        if (!(raw instanceof Float)) {
                            return raw;
                        }
                        float current = (Float) raw;
                        if (!enabled() || !appAllowed()) {
                            return raw;
                        }
                        // 只替换系统默认；app 显式声明的上下限不碰。
                        if (Float.compare(current, RATIO_SYSTEM_DEFAULT) != 0) {
                            return raw;
                        }
                        if (!isDraggable(chain.getThisObject())) {
                            return raw;
                        }
                        if (boundLogs.incrementAndGet() <= 6) {
                            L.INSTANCE.i("event=embedding_ratio_bound name=" + name
                                    + " " + current + " -> " + target);
                        }
                        return target;
                    });
            return true;
        } catch (Throwable t) {
            L.INSTANCE.w("event=embedding_ratio_hook_failed target=" + name + " msg=" + t);
            return false;
        }
    }

    /** {@code dividerAttributes.getDividerType() == DIVIDER_TYPE_DRAGGABLE}。 */
    private boolean isDraggable(Object attributes) {
        Method m = dividerTypeMethod;
        if (attributes == null || m == null) {
            return false;
        }
        try {
            Object type = m.invoke(attributes);
            return type instanceof Integer && (Integer) type == DIVIDER_TYPE_DRAGGABLE;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 反射调用 presenter 上的无参 int 方法。这两个方法定义在父类且是包级可见，
     * 所以要用 getDeclaredMethod 沿类层级往上找，不能只靠 getMethod（只查 public）。
     * 任何异常都返回 {@link Integer#MIN_VALUE} 作哨兵，交给调用方保守放行。
     */
    private static int callInt(Object receiver, String methodName) {
        if (receiver == null) {
            return Integer.MIN_VALUE;
        }
        Class<?> cls = receiver.getClass();
        while (cls != null) {
            try {
                Method m = cls.getDeclaredMethod(methodName);
                m.setAccessible(true);
                Object r = m.invoke(receiver);
                return r instanceof Integer ? (Integer) r : Integer.MIN_VALUE;
            } catch (NoSuchMethodException ignored) {
                cls = cls.getSuperclass();
            } catch (Throwable t) {
                L.INSTANCE.w("event=embedding_ratio_callint_failed method=" + methodName + " msg=" + t);
                return Integer.MIN_VALUE;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** 按「方法名 + 参数个数 + 返回 int」定位，兼容签名细微漂移。 */
    private boolean hookIfPresent(Class<?> cls, String name, int paramCount,
                                  XposedInterface.Hooker hooker) {
        for (Method method : cls.getDeclaredMethods()) {
            if (!name.equals(method.getName()) || method.getParameterCount() != paramCount) {
                continue;
            }
            if (method.getReturnType() != int.class) {
                continue;
            }
            try {
                method.setAccessible(true);
                module.hook(method)
                        .setId("embedding_ratio_" + name)
                        .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                        .intercept(hooker);
                return true;
            } catch (Throwable t) {
                L.INSTANCE.w("event=embedding_ratio_hook_failed target=" + name + " msg=" + t);
                return false;
            }
        }
        return false;
    }
}
