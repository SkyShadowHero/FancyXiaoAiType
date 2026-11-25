package io.github.skyshadowhero.fancypad.hooks;

import io.github.skyshadowhero.fancypad.*;

import android.content.Context;
import android.graphics.Rect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
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
 *   <li><b>拖动上下限可调（默认就是系统的 0.35/0.65）</b>：动态接管
 *       {@code DividerPresenter.calculateMinPosition()/calculateMaxPosition()} 的静态计算入口。
 *       不能再改 {@code DividerAttributes} getter：sanitize 会把改后的比例固化进新对象，
 *       之后即使关掉开关，当前分栏仍会拿旧的 0.25/0.75，无法完整恢复系统范围。</li>
 * </ol>
 *
 * <p>只改「可拖动分栏」（{@code DIVIDER_TYPE_DRAGGABLE}）：固定分栏的构造器要求上下限
 * 必须是 -1，硬改会抛 {@code IllegalStateException}，所以按类型放过。
 * app 自己声明过上下限（非 -1）时也放过，尊重应用的选择。
 */
public final class EmbeddingRatioHooks {

    private static final String CLS_DIVIDER =
            "androidx.window.extensions.embedding.MiuiDividerPresenter";
    private static final String CLS_PRESENTER =
            "androidx.window.extensions.embedding.DividerPresenter";
    private static final String CLS_ATTRS =
            "androidx.window.extensions.embedding.DividerAttributes";
    private static final String CLS_TASK_CONTAINER =
            "androidx.window.extensions.embedding.TaskContainer";

    /** 小米把最后一次连续比例存在目标应用自己的这个 SharedPreferences 里。 */
    private static final String SPLIT_RATIO_PREFS = "miui_split_ratio_prefs";

    /** (int dividerPosition, float velocity) → int，松手停靠点决策。 */
    private static final String METHOD_SNAP_POINTS = "dividerPositionForSnapPoints";
    /** 无参实例方法，snap hook 里反射读边界做 clamp。 */
    private static final String METHOD_MIN = "calculateMinPosition";
    private static final String METHOD_MAX = "calculateMaxPosition";

    /** {@code DividerAttributes.DIVIDER_TYPE_DRAGGABLE}：只有可拖动分栏才允许自定义上下限。 */
    private static final int DIVIDER_TYPE_DRAGGABLE = 2;
    /** 系统 sanitize 后的原生范围；只接管这一档，应用显式自定义的比例继续放行。 */
    private static final float SYSTEM_MIN_RATIO = 0.35f;
    private static final float SYSTEM_MAX_RATIO = 0.65f;

    /**
     * 偏好快照的兜底刷新间隔。正常会在每次开始拖动时同步重读；这里用于未来系统版本
     * 找不到开始拖动方法时，至少还能在松手入口后台自愈。
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
    private volatile Method minRatioMethod;
    private volatile Method maxRatioMethod;
    /** {@code TaskContainer#mOverrideSplitType}；关闭无级后必须显式清掉旧运行态。 */
    private volatile Field overrideSplitTypeField;
    /** 低频记录活着的 TaskContainer，关开关后的下一次拖动可把已有实例也恢复。 */
    private final Set<Object> taskContainers =
            Collections.newSetFromMap(new WeakHashMap<>());
    private volatile Context appContext;

    /** 日志只留前几条做取证，避免逐次拖动刷屏。 */
    private final AtomicInteger snapLogs = new AtomicInteger();
    private final AtomicInteger boundLogs = new AtomicInteger();
    private final AtomicInteger restoreLogs = new AtomicInteger();

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
        Class<?> presenter;
        Class<?> attrs;
        Class<?> taskContainer;
        try {
            divider = classLoader.loadClass(CLS_DIVIDER);
            presenter = classLoader.loadClass(CLS_PRESENTER);
            attrs = classLoader.loadClass(CLS_ATTRS);
            taskContainer = classLoader.loadClass(CLS_TASK_CONTAINER);
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
                minRatioMethod = attrs.getDeclaredMethod("getPrimaryMinRatio");
                minRatioMethod.setAccessible(true);
                maxRatioMethod = attrs.getDeclaredMethod("getPrimaryMaxRatio");
                maxRatioMethod.setAccessible(true);
                overrideSplitTypeField = taskContainer.getDeclaredField("mOverrideSplitType");
                overrideSplitTypeField.setAccessible(true);
            } catch (Throwable ignored) {
            }
            appContext = resolveApplicationContext();

            int ok = 0;
            int total = 0;

            // 1) 每笔拖动开始时同步刷新。RemotePreferences 的变更通知在已注入 App 里
            // 不可靠；异步刷新又会让「关闭后的第一笔」仍按旧快照无级停靠。
            total++;
            if (hookIfPresent(divider, "onStartDragging", 1, void.class, chain -> {
                HookPrefs.rebind(module);
                if (shouldRestoreSystemTiers()) {
                    clearPersistedRatio(null);
                    clearTrackedOverrides();
                }
                return chain.proceed();
            })) {
                ok++;
            }

            // 2) 松手不吸附
            total++;
            if (hookIfPresent(divider, METHOD_SNAP_POINTS, 2, int.class, chain -> {
                // 若目标版本没有 onStartDragging，保留低频异步刷新兜底。
                HookPrefs.refreshIfStale(module, PREF_REFRESH_MS);
                if (!appAllowed()) {
                    return chain.proceed();
                }
                if (!enabled()) {
                    Object original = chain.proceed();
                    if (!(original instanceof Integer)) {
                        return original;
                    }
                    int min = callInt(chain.getThisObject(), METHOD_MIN);
                    int max = callInt(chain.getThisObject(), METHOD_MAX);
                    if (min == Integer.MIN_VALUE || max == Integer.MIN_VALUE || min > max) {
                        return original;
                    }
                    int snapped = snapToSystemTier((Integer) original, min, max);
                    if (snapLogs.incrementAndGet() <= 6) {
                        L.INSTANCE.i("event=embedding_ratio_system_snap raw=" + chain.getArg(0)
                                + " original=" + original + " snapped=" + snapped
                                + " min=" + min + " max=" + max);
                    }
                    return snapped;
                }
                // arg0 = mPreDividerPosition，松手瞬间的连续像素位置。原实现会 snap 到
                // 1/2（居中吸附）或 min/max（甩动）；去掉吸附但必须保留 clamp。
                Object raw = chain.getArg(0);
                if (!(raw instanceof Integer)) {
                    return chain.proceed();
                }
                int pos = (Integer) raw;
                Object presenterObject = chain.getThisObject();
                int min = callInt(presenterObject, METHOD_MIN);
                int max = callInt(presenterObject, METHOD_MAX);
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

            // 3) 动态上下限。开关关闭时直接返回系统原计算，不把自定义值固化进
            // DividerAttributes，因此同一个分栏对象也能立刻恢复 0.35 / 0.65。
            total++;
            if (hookBoundPosition(presenter, METHOD_MIN, true)) {
                ok++;
            }
            total++;
            if (hookBoundPosition(presenter, METHOD_MAX, false)) {
                ok++;
            }

            // 4) 关闭后不再把三档结果写回连续比例偏好。旧值也在这里同步移除。
            total++;
            if (hookIfPresent(divider, "saveSplitRatio", 3, void.class, chain -> {
                if (shouldRestoreSystemTiers() && packageMatches(chain.getArg(1))) {
                    clearPersistedRatio(chain.getArg(0));
                    return null;
                }
                return chain.proceed();
            })) {
                ok++;
            }

            // 5) 新建/恢复 TaskContainer 时，系统原实现只在读到有效比例时赋值，
            // 没有值却不会清旧字段；因此关闭态要跳过加载并显式置 null。
            total++;
            if (hookIfPresent(taskContainer, "loadDragOverrideSplitType", 0, void.class, chain -> {
                Object receiver = chain.getThisObject();
                trackTaskContainer(receiver);
                HookPrefs.rebind(module);
                if (shouldRestoreSystemTiers()) {
                    clearPersistedRatio(null);
                    clearOverride(receiver);
                    return null;
                }
                return chain.proceed();
            })) {
                ok++;
            }

            // 6) 当前任务拖完后，系统会重新给 mOverrideSplitType 塞 RatioSplitType。
            // 先让它把当前分栏落到 35/50/65，再清字段与持久值，避免以后继承连续比例。
            total++;
            if (hookIfPresent(taskContainer, "updateTopSplitContainerForDivider", 2,
                    void.class, chain -> {
                        Object receiver = chain.getThisObject();
                        trackTaskContainer(receiver);
                        Object result = chain.proceed();
                        if (shouldRestoreSystemTiers()) {
                            clearOverride(receiver);
                            clearPersistedRatio(null);
                        }
                        return result;
                    })) {
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

    /** 只有曾在设置页选中的目标应用才做恢复；其它作用域应用保持系统原样。 */
    private boolean shouldRestoreSystemTiers() {
        return !enabled() && appAllowed();
    }

    private boolean packageMatches(Object pkg) {
        return pkg instanceof String && appPkg != null && appPkg.equals(pkg);
    }

    /** 保留系统原有的全屏展开结果，只把正常区间量化到 35% / 50% / 65%。 */
    private static int snapToSystemTier(int candidate, int min, int max) {
        if (candidate < min || candidate > max) {
            return candidate;
        }
        int center = min + ((max - min) / 2);
        int best = min;
        int distance = Math.abs(candidate - min);
        int centerDistance = Math.abs(candidate - center);
        if (centerDistance < distance) {
            best = center;
            distance = centerDistance;
        }
        if (Math.abs(candidate - max) < distance) {
            best = max;
        }
        return best;
    }

    private void trackTaskContainer(Object container) {
        if (container == null) {
            return;
        }
        synchronized (taskContainers) {
            taskContainers.add(container);
        }
    }

    private void clearTrackedOverrides() {
        synchronized (taskContainers) {
            for (Object container : taskContainers) {
                clearOverride(container);
            }
        }
    }

    private void clearOverride(Object container) {
        Field field = overrideSplitTypeField;
        if (container == null || field == null) {
            return;
        }
        try {
            field.set(container, null);
            if (restoreLogs.incrementAndGet() <= 6) {
                L.INSTANCE.i("event=embedding_ratio_override_cleared pkg=" + appPkg);
            }
        } catch (Throwable t) {
            L.INSTANCE.w("event=embedding_ratio_override_clear_failed msg=" + t);
        }
    }

    private void clearPersistedRatio(Object contextHint) {
        Context context = contextHint instanceof Context ? (Context) contextHint : appContext;
        if (context == null) {
            context = resolveApplicationContext();
            appContext = context;
        }
        if (context == null || appPkg == null) {
            return;
        }
        try {
            context.getSharedPreferences(SPLIT_RATIO_PREFS, Context.MODE_PRIVATE)
                    .edit().remove(appPkg).apply();
        } catch (Throwable t) {
            L.INSTANCE.w("event=embedding_ratio_pref_clear_failed msg=" + t);
        }
    }

    /** ActivityThread 是隐藏 API，Hook 进程里通过反射取当前 Application。 */
    private static Context resolveApplicationContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object application = currentApplication.invoke(null);
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 动态改写系统默认分栏的像素边界。
     *
     * <p>目标是静态五参重载：
     * {@code (Rect, int dividerWidth, DividerAttributes, boolean vertical, boolean reversed)}。
     * 只接管 sanitize 后恰为系统 0.35/0.65 的可拖动分栏；应用显式给出的其它范围不碰。
     */
    private boolean hookBoundPosition(Class<?> presenter, String name, boolean isMin) {
        return hookIfPresent(presenter, name, 5, int.class, chain -> {
            Object original = chain.proceed();
            if (!(original instanceof Integer) || !enabled() || !appAllowed()) {
                return original;
            }

            Object boundsArg = chain.getArg(0);
            Object widthArg = chain.getArg(1);
            Object attrsArg = chain.getArg(2);
            Object verticalArg = chain.getArg(3);
            Object reversedArg = chain.getArg(4);
            if (!(boundsArg instanceof Rect) || !(widthArg instanceof Integer)
                    || !(verticalArg instanceof Boolean) || !(reversedArg instanceof Boolean)
                    || !isSystemDefaultDraggable(attrsArg)) {
                return original;
            }

            Rect bounds = (Rect) boundsArg;
            int dividerWidth = (Integer) widthArg;
            boolean vertical = (Boolean) verticalArg;
            boolean reversed = (Boolean) reversedArg;
            int usable = (vertical ? bounds.width() : bounds.height()) - dividerWidth;
            if (usable <= 0) {
                return original;
            }

            float min = HookPrefs.embeddingRatioMin();
            float max = HookPrefs.embeddingRatioMax();
            float ratio = isMin
                    ? (reversed ? 1.0f - max : min)
                    : (reversed ? 1.0f - min : max);
            int target = (int) (usable * ratio);
            if (boundLogs.incrementAndGet() <= 6) {
                L.INSTANCE.i("event=embedding_ratio_bound name=" + name
                        + " " + original + " -> " + target + " ratio=" + ratio);
            }
            return target;
        });
    }

    /** 可拖动，且当前对象是系统 sanitize 后的默认 0.35/0.65。 */
    private boolean isSystemDefaultDraggable(Object attributes) {
        Method type = dividerTypeMethod;
        Method min = minRatioMethod;
        Method max = maxRatioMethod;
        if (attributes == null || type == null || min == null || max == null) {
            return false;
        }
        try {
            Object dividerType = type.invoke(attributes);
            Object minRatio = min.invoke(attributes);
            Object maxRatio = max.invoke(attributes);
            return dividerType instanceof Integer
                    && (Integer) dividerType == DIVIDER_TYPE_DRAGGABLE
                    && minRatio instanceof Float
                    && maxRatio instanceof Float
                    && Float.compare((Float) minRatio, SYSTEM_MIN_RATIO) == 0
                    && Float.compare((Float) maxRatio, SYSTEM_MAX_RATIO) == 0;
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

    /** 按「方法名 + 参数个数 + 返回类型」定位，兼容签名细微漂移。 */
    private boolean hookIfPresent(Class<?> cls, String name, int paramCount, Class<?> returnType,
                                  XposedInterface.Hooker hooker) {
        for (Method method : cls.getDeclaredMethods()) {
            if (!name.equals(method.getName()) || method.getParameterCount() != paramCount) {
                continue;
            }
            if (method.getReturnType() != returnType) {
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
