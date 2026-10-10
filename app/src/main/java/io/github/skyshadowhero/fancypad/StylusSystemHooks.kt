package io.github.skyshadowhero.fancypad

import android.content.ComponentName
import android.view.inputmethod.InputMethodInfo
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field

/**
 * FancyPad · 随手写 —— **system_server 侧**的「声明」（触控笔手写 / stylus handwriting）。
 *
 * 「随手写」不是输入法里那个手写键盘，而是 AOSP Android 14+ 的触控笔手写：
 * 笔直接在输入框上写字、笔迹转文字上屏。HyperOS 4 / Android 17 的实测链路：
 *
 * ```
 * App: View.isStylusHandwritingAvailable()
 *        └─ InputMethodManager.isStylusHandwritingAvailableAsUser()      (binder)
 * system_server: InputMethodManagerService.isStylusHandwritingAvailableAsUser()
 *        ├─ isStylusHandwritingEnabled()                    ← 总闸 Secure: stylus_handwriting_enabled
 *        └─ InputMethodBindingController.getSupportsStylusHandwriting()   ← ★本文件的第一道 hook
 * ```
 *
 * 小爱输入法过不去的原因很具体：它的 `res/xml/method.xml` 里没有
 * `android:supportsStylusHandwriting="true"`，于是 `InputMethodInfo` 的该标志为 false，
 * `onImeConnected()` 又把它同步进 `InputMethodBindingController`，系统就认定「当前输入法不支持」。
 * 后果不止"笔没反应"，而是**两条**：
 *
 * 1. `HandwritingConfigManager.updateHandwritingState()` 会把总闸
 *    `stylus_handwriting_enabled` **主动写回 0** —— 用户打开随手写也存不住；
 * 2. MIUI 设置页/安全中心会认为「当前输入法不支持」，弹提示并把输入法切给支持的那个
 *    （谁真正执行切换见下）。
 *
 * 这里做两件事，把上面两条一起掐掉：
 *
 * - hook `InputMethodInfo.supportsStylusHandwriting()`：包名命中时返回 true。
 *   它是「写回 0」的判定源，也是 `onImeConnected` 同步值的来源；
 * - hook `InputMethodBindingController.getSupportsStylusHandwriting()`：当前绑定的输入法是小爱时
 *   返回 true。这是 `IMMS.startStylusHandwriting()` 里的硬闸，直接放开就不依赖
 *   `onImeConnected` 的时机（该布尔值只在 onImeConnected 同步一次 —— 只改前者往往不生效）。
 *
 * ## 「打开随手写被切到搜狗」的真正执行者
 *
 * 不在 system_server，而在**安全中心**：`com.miui.securitycore` 的
 * `MiuiHandwritingSettingsFragment.p(boolean)` 直接改写
 * `Settings.Secure.default_input_method` 为「已启用且声明支持随手写的第一个输入法」。
 * 它在改写**之前**会问 `getCurrentInputMethodInfo().supportsStylusHandwriting()` ——
 * 所以只要那个进程里这个答案也是 true，它自己就早退了。
 * 因此 [installForSecurityCenter] 会把同一个声明 hook 也装进安全中心进程（作用域里要有它）。
 *
 * ## 为什么声明开关默认是「开」（**不要**改成默认关）
 *
 * 第一版把这两个 hook 挂在一个默认关的总开关上，结果是死锁，真机踩过：
 * 小爱被标为不支持 → MIUI 设置页里那个「随手写」开关**根本打不开** → 用户不会去动模块开关
 * → 模块开关是关的 → 放行 → 回到不支持。所以声明必须默认开。
 *
 * 笔迹要不要真的处理、由哪个引擎识别，是 [StylusImeHooks] 的事，由「随手写（触控笔手写）」控制。
 */
class StylusSystemHooks(private val module: XposedModule) {

    @Volatile
    private var installed = false

    /** 安全中心进程单独一个幂等位（那个进程只装声明）。 */
    @Volatile
    private var securityCenterInstalled = false

    /** system_server（作用域 `system`）：声明两道闸。 */
    fun install(cl: ClassLoader) {
        if (installed) return
        installed = true
        hookInputMethodInfo(cl)
        hookBindingController(cl)
    }

    /**
     * 安全中心（作用域 `com.miui.securitycore`）：只要声明。
     *
     * 这里没有 `InputMethodBindingController`（那是 system_server 的类）。
     * 装它的目的见类注释：让 `p()` 的第一行判断对小爱返回 true，它就不改写 `default_input_method` 了。
     */
    fun installForSecurityCenter(cl: ClassLoader) {
        if (securityCenterInstalled) return
        securityCenterInstalled = true
        hookInputMethodInfo(cl)
    }

    /**
     * `android.view.inputmethod.InputMethodInfo.supportsStylusHandwriting()`。
     *
     * MIUI 与 AOSP 共同的判定源，小爱为 false 的根因就在这里。改成 true 之后
     * `onImeConnected()` 会把 true 同步进 binding controller，`HandwritingConfigManager`
     * 也不会再把总闸写回 0。
     */
    private fun hookInputMethodInfo(cl: ClassLoader) {
        try {
            val cls = cl.loadClass("android.view.inputmethod.InputMethodInfo")
            val getter = cls.getDeclaredMethod("supportsStylusHandwriting")
            module.hook(getter)
                .setId("stylus_imi_supports")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    if (!HookPrefs.stylusWhitelist(module)) {
                        chain.proceed()
                    } else {
                        // getPackageName() 是 InputMethodInfo 的公开 API，直接强转即可
                        val pkg = (chain.thisObject as? InputMethodInfo)?.packageName
                        if (pkg == TARGET_PACKAGE) true else chain.proceed()
                    }
                }
            L.i("event=stylus_hook_imi_installed")
        } catch (t: Throwable) {
            L.e("event=stylus_hook_imi_failed", t)
        }
    }

    /**
     * `com.android.server.inputmethod.InputMethodBindingController.getSupportsStylusHandwriting()`。
     *
     * 包私有方法（`boolean getSupportsStylusHandwriting()`），必须 `setAccessible`。
     * 当前绑定输入法从小米的字段 `mCurrentBoundInputMethod` 读 —— 低频调用
     * （App 侧 `isStylusHandwritingAvailable()` 有 PropertyInvalidatedCache，不是每次移动都调），
     * 所以直接反射读字段即可，不做额外缓存。
     */
    private fun hookBindingController(cl: ClassLoader) {
        try {
            val cls = cl.loadClass(CLS_BINDING_CONTROLLER)
            val getter = cls.getDeclaredMethod("getSupportsStylusHandwriting")
                .apply { isAccessible = true }
            val boundField: Field = cls.getDeclaredField("mCurrentBoundInputMethod")
                .apply { isAccessible = true }
            module.hook(getter)
                .setId("stylus_bc_supports")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    if (!HookPrefs.stylusWhitelist(module)) {
                        chain.proceed()
                    } else {
                        val bound = runCatching {
                            boundField.get(chain.thisObject) as? ComponentName
                        }.getOrNull()
                        if (bound?.packageName == TARGET_PACKAGE) true else chain.proceed()
                    }
                }
            L.i("event=stylus_hook_binding_controller_installed")
        } catch (t: Throwable) {
            L.e("event=stylus_hook_binding_controller_failed", t)
        }
    }

    private companion object {
        const val CLS_BINDING_CONTROLLER =
            "com.android.server.inputmethod.InputMethodBindingController"

        /** 目标输入法包名。 */
        const val TARGET_PACKAGE = "com.xiaomi.type"
    }
}
