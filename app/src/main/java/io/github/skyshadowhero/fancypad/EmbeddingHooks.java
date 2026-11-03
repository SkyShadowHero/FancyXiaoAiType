package io.github.skyshadowhero.fancypad;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * FancyPad · 平行窗口动画修复（原 os4平行窗口动画fix模块），作用域 {@code com.android.systemui}。
 *
 * <p>问题：HyperOS 的平行窗口（Activity Embedding）左右切换 / 进二级页时卡顿、丢动画。
 * 根因：{@code MiuiEmbeddingControllerImpl} + {@code MiuiEmbeddingAnimationRunner} 通过
 * {@code MiuiEmbeddingTransitionUtil.useFolmeEngine()}（受
 * {@code persist.folme.activity_embedding.enable} 控制，默认 true）选了小米 Folme 引擎，
 * 而不是 AOSP 的 {@code DefaultAnimationProvider}；另外 {@code createAnimationAdapters()}
 * 返回空 → 跳切，{@code mergeAnimation()} 失败 → {@code cancelAnimationFromMerge()} 也会丢动画。
 * 证据：{@code /system_ext/framework/Miui-WindowManager-Shell.jar}（只在 com.android.systemui 里加载）。
 *
 * <p>四个 Hook 各自有独立开关（见 {@link PrefKeys}），总开关 {@code embedding_enabled}
 * 关掉即整块还原成系统原行为。实测真正影响观感的是「禁止转场合并」+「禁止跳切」，
 * 另外两项保留为开关便于对照排查。
 */
public final class EmbeddingHooks {

    private static final String CLS_UTIL =
            "com.android.wm.shell.activityembedding.MiuiEmbeddingTransitionUtil";
    private static final String CLS_RUNNER =
            "com.android.wm.shell.activityembedding.MiuiEmbeddingAnimationRunner";

    private final XposedInterface module;
    private volatile boolean installed;

    public EmbeddingHooks(XposedInterface module) {
        this.module = module;
    }

    /**
     * 由 {@code XposedEntry} 在 com.android.systemui 进程里调用。
     *
     * @return true = 已装好（含「本来就装过」）；false = 一个都没装上，可以重试。
     *         早先无论成败都把 {@code installed} 钉成 true，一次偶发的类加载失败
     *         就等于这块功能永久失效到 SystemUI 重启。
     */
    public synchronized boolean install(ClassLoader classLoader) {
        if (installed) {
            return true;
        }
        int ok = 0;
        try {
            Class<?> util = classLoader.loadClass(CLS_UTIL);
            Class<?> runner = classLoader.loadClass(CLS_RUNNER);

            // 1) provider 选择：AOSP 原生实现
            module.hook(util.getDeclaredMethod("useFolmeEngine"))
                    .setId("use_folme_engine")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> folmeDisabled() ? Boolean.FALSE : chain.proceed());
            ok++;

            // 2) MIUI 运行时可切换 provider 的那个布尔参数，钉死为 false
            if (hookIfPresent(util, "createTransitionAnimationProvider", 3, chain -> {
                if (!folmeDisabled()) {
                    return chain.proceed();
                }
                return chain.proceed(new Object[]{chain.getArg(0), chain.getArg(1), Boolean.FALSE});
            })) {
                ok++;
            }

            // 3) 不合并转场：合并失败会把正在播的动画取消掉
            if (hookIfPresent(runner, "mergeAnimation", 5, chain -> {
                if (mergeDisabled()) {
                    chain.proceed();
                    return Boolean.FALSE;
                }
                return chain.proceed();
            })) {
                ok++;
            }

            // 4) 不跳切：跳切等于这次转场不播动画
            if (hookIfPresent(runner, "shouldUseJumpCutForChangeTransition", 1,
                    chain -> jumpCutDisabled() ? Boolean.FALSE : chain.proceed())) {
                ok++;
            }
            if (hookIfPresent(runner, "shouldUseJumpCutForAnimation", 1,
                    chain -> jumpCutDisabled() ? Boolean.FALSE : chain.proceed())) {
                ok++;
            }
        } catch (Throwable t) {
            // 目标版本不匹配时跳过，不影响 SystemUI；一个都没装上才允许外层重试
            L.INSTANCE.w("event=embedding_install_partial ok=" + ok + " msg=" + t);
        }
        installed = ok > 0;
        if (ok == 0) {
            L.INSTANCE.w("event=embedding_install_failed reason=target_not_found");
        }
        return ok > 0;
    }

    private static boolean folmeDisabled() {
        return HookPrefs.embeddingEnabled() && HookPrefs.folmeDisabled();
    }

    private static boolean mergeDisabled() {
        return HookPrefs.embeddingEnabled() && HookPrefs.mergeDisabled();
    }

    private static boolean jumpCutDisabled() {
        return HookPrefs.embeddingEnabled() && HookPrefs.jumpCutDisabled();
    }

    /** 按「方法名 + 参数个数」定位，避免不同模块版本签名漂移时整体失效。 */
    private boolean hookIfPresent(Class<?> cls, String name, int paramCount, XposedInterface.Hooker hooker) {
        for (Method method : cls.getDeclaredMethods()) {
            if (!name.equals(method.getName()) || method.getParameterCount() != paramCount) {
                continue;
            }
            try {
                method.setAccessible(true);
                module.hook(method)
                        .setId(name + "_" + paramCount)
                        .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                        .intercept(hooker);
                return true;
            } catch (Throwable t) {
                L.INSTANCE.w("event=embedding_hook_failed target=" + name + " msg=" + t);
                return false;
            }
        }
        return false;
    }
}
