package io.github.skyshadowhero.fancypad;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * FancyPad · 平行窗口动画修复（原 os4平行窗口动画fix模块），作用域 {@code com.android.systemui}。
 *
 * <p>问题：HyperOS 的平行窗口（Activity Embedding）左右切换 / 进二级页时卡顿、丢动画。
 * 真机实测只在「跳切」这一层有效：{@code createAnimationAdapters()} 返回空 →
 * {@code shouldUseJumpCut*} 为 true → 这次转场根本不播动画。把这两个判定钉成 false，
 * 转场就恢复成真正的动画。
 * <p>早先还 hook 过 {@code useFolmeEngine()}（改用 AOSP 动画 provider）与
 * {@code mergeAnimation()}（禁止转场合并），真机对比无可见效果，已删除。
 *
 * <p>总开关 {@code embedding_enabled} 关掉即还原成系统原行为。
 */
public final class EmbeddingHooks {

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
            Class<?> runner = classLoader.loadClass(CLS_RUNNER);

            // 不跳切：跳切等于这次转场不播动画（唯一实测有效的一项）
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
