package io.github.skyshadowhero.fancypad.hooks

import io.github.skyshadowhero.fancypad.*

import android.os.Bundle
import android.os.Handler
import android.text.InputType
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * 随手写 · 应用侧的最小兼容兜底。
 *
 * HyperOS 会按页面配置给部分自绘编辑器同时写入：
 *
 * ```
 * view.setAutoHandwritingEnabled(false)
 * view.setForceDisableHandwriting(true)
 * ```
 *
 * 小米便签的 `NoteEditorWebView` 就会因此在应用进程的 [android.view.HandwritingInitiator]
 * 阶段被过滤，事件根本到不了输入法。这里不改应用的触摸分发，也不把所有 View 全局放行；
 * 只有当前包名在设置页「生效的应用」中，且一个输入连接**已经真正打开**时才检查目标编辑器，
 * 并且必须同时满足：
 *
 * - View 已聚焦、已挂载、可见且启用；
 * - `onCheckIsTextEditor()` 为 true；
 * - [InputConnection] 非空；
 * - [EditorInfo] 不是任何密码输入类型。
 *
 * 命中后暂时打开 auto-handwriting、解除 force-disable，让随后执行的
 * `HandwritingInitiator.onInputConnectionCreated()` 能登记候选。连接关闭时恢复原值，因而不会把
 * 未聚焦、无连接或密码编辑器留在手写候选集合里。
 */
internal class StylusAppHooks(private val module: XposedModule) {

    private data class OverrideState(
        val originalAuto: Boolean,
        val originalForceDisable: Boolean,
        var connectionCount: Int,
    )

    private val stateLock = Any()
    private val activeOverrides = WeakHashMap<View, OverrideState>()

    @Volatile
    private var openedHookInstalled = false

    @Volatile
    private var closedHookInstalled = false

    @Volatile
    private var registrationHookInstalled = false

    @Volatile
    private var diagnosticHooksInstalled = false

    /** HyperOS 给 View 增加的私有扩展，公开 Android SDK stub 中没有，只能运行时解析。 */
    @Volatile
    private var forceDisableGetter: Method? = null

    @Volatile
    private var forceDisableSetter: Method? = null

    /** 两个 Hook 缺一不可：没有关闭回调时，打开回调会保持透传，不留下永久状态。 */
    fun install(cl: ClassLoader): Boolean {
        if (!resolveForceDisableMethods(cl)) return false
        if (!openedHookInstalled) openedHookInstalled = hookInputConnectionOpened(cl)
        if (!closedHookInstalled) closedHookInstalled = hookInputConnectionClosed(cl)
        if (!registrationHookInstalled) registrationHookInstalled = hookCandidateRegistration(cl)
        if (!diagnosticHooksInstalled) diagnosticHooksInstalled = hookHandwritingDiagnostics(cl)
        val ready = openedHookInstalled && closedHookInstalled && registrationHookInstalled
        L.i(
            "event=stylus_app_hooks installed=$ready " +
                "opened=$openedHookInstalled closed=$closedHookInstalled " +
                "registered=$registrationHookInstalled",
        )
        return ready
    }

    /** 热重载摘掉旧句柄后允许重新安装。 */
    fun resetInstalled() {
        openedHookInstalled = false
        closedHookInstalled = false
        registrationHookInstalled = false
        diagnosticHooksInstalled = false
    }

    /** 热重载前恢复所有仍存活的编辑器，不能把临时兼容状态交给下一代代码。 */
    fun dispose() {
        val pending = synchronized(stateLock) {
            activeOverrides.entries.map { it.key to it.value }.also { activeOverrides.clear() }
        }
        for ((view, state) in pending) restoreView(view, state, "dispose")
    }

    /**
     * `InputMethodManager.reportInputConnectionOpened(InputConnection, EditorInfo, Handler, View)`。
     *
     * 这是最窄的稳定时机：InputConnection 已创建且即将通知 HandwritingInitiator；比 attach/focus
     * Hook 更晚，因此能确认不是普通 View，也不会在密码字段尚未产出 EditorInfo 时误放行。
     */
    private fun hookInputConnectionOpened(cl: ClassLoader): Boolean = try {
        val cls = cl.loadClass(CLS_INPUT_METHOD_MANAGER)
        val method = cls.getDeclaredMethod(
            "reportInputConnectionOpened",
            InputConnection::class.java,
            EditorInfo::class.java,
            Handler::class.java,
            View::class.java,
        ).apply { isAccessible = true }

        module.hook(method)
            .setId("stylus_app_input_opened")
            .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
            .intercept { chain ->
                val inputConnection = chain.getArg(0) as? InputConnection
                val editorInfo = chain.getArg(1) as? EditorInfo
                val view = chain.getArg(3) as? View

                // 若关闭 Hook 没装好，宁可不兼容，也不能把 View 永久改成可手写。
                val acquired = if (
                    closedHookInstalled && inputConnection != null && editorInfo != null && view != null
                ) {
                    onInputConnectionOpened(view, inputConnection, editorInfo)
                } else {
                    false
                }

                try {
                    chain.proceed()
                } catch (t: Throwable) {
                    // 原调用失败时不会有配对的 close 回调，立即回滚本次引用。
                    if (acquired && view != null) releaseOverride(view, "open_failed")
                    throw t
                }
            }
        true
    } catch (t: Throwable) {
        L.e("event=stylus_app_hook_failed target=input_opened", t)
        false
    }

    /** 连接真正关闭后恢复页面配置原值。 */
    private fun hookInputConnectionClosed(cl: ClassLoader): Boolean = try {
        val cls = cl.loadClass(CLS_HANDWRITING_INITIATOR)
        val method = cls.getDeclaredMethod("onInputConnectionClosed", View::class.java)
            .apply { isAccessible = true }

        module.hook(method)
            .setId("stylus_app_input_closed")
            .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
            .intercept { chain ->
                val view = chain.getArg(0) as? View
                try {
                    chain.proceed()
                } finally {
                    if (view != null) releaseOverride(view, "connection_closed")
                }
            }
        true
    } catch (t: Throwable) {
        L.e("event=stylus_app_hook_failed target=input_closed", t)
        false
    }

    /**
     * Android 16+ 的 `initiationWithoutInputConnection` 路径不再由
     * `onInputConnectionCreated()` 填 `mConnectedView`，而是在获得焦点时填 `mFocusedView`。
     * 小米页面配置先把自绘编辑器的 auto 关掉，导致那次焦点通知早已被过滤；连接打开后
     * 我们虽然恢复了 auto，却必须补发一次焦点登记，否则候选永远是空的。
     */
    private fun hookCandidateRegistration(cl: ClassLoader): Boolean = try {
        val cls = cl.loadClass(CLS_HANDWRITING_INITIATOR)
        val created = cls.getDeclaredMethod("onInputConnectionCreated", View::class.java)
            .apply { isAccessible = true }
        val focused = cls.getDeclaredMethod("onEditorFocused", View::class.java)
            .apply { isAccessible = true }
        val updateArea = cls.getDeclaredMethod("updateHandwritingAreasForView", View::class.java)
            .apply { isAccessible = true }

        module.hook(created)
            .setId("stylus_app_candidate_registration")
            .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
            .intercept { chain ->
                val result = chain.proceed()
                val view = chain.getArg(0) as? View
                if (view != null && isActiveOverride(view)) {
                    runCatching {
                        updateArea.invoke(chain.thisObject, view)
                        focused.invoke(chain.thisObject, view)
                    }.onSuccess {
                        L.i("event=stylus_app_candidate_registered view=${describeView(view)}")
                    }.onFailure {
                        L.e("event=stylus_app_candidate_register_failed view=${view.javaClass.name}", it)
                    }
                }
                result
            }
        true
    } catch (t: Throwable) {
        L.e("event=stylus_app_hook_failed target=candidate_registration", t)
        false
    }

    /**
     * 只在本类正临时放行某个编辑器时记录触控笔入口，避免给普通触摸热路径刷日志。
     * 这能明确区分“应用候选没登记”和“系统已经请求 IME、但 IME 没接住”。
     */
    private fun hookHandwritingDiagnostics(cl: ClassLoader): Boolean = try {
        val cls = cl.loadClass(CLS_HANDWRITING_INITIATOR)
        val connectedField = cls.getDeclaredField("mConnectedView").apply { isAccessible = true }
        val focusedField = cls.getDeclaredField("mFocusedView").apply { isAccessible = true }
        val touch = cls.getDeclaredMethod("onTouchEvent", MotionEvent::class.java)
            .apply { isAccessible = true }
        val start = cls.getDeclaredMethod("startHandwriting", View::class.java)
            .apply { isAccessible = true }

        module.hook(touch)
            .setId("stylus_app_touch_diagnostic")
            .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
            .intercept { chain ->
                val event = chain.getArg(0) as? MotionEvent
                val trace = event != null && hasActiveOverride() &&
                    (event.isFromSource(InputDevice.SOURCE_STYLUS) ||
                        event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_STYLUS ||
                        event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_ERASER)
                if (!trace) return@intercept chain.proceed()

                val action = event!!.actionMasked
                val connected = runCatching {
                    val ref = connectedField.get(chain.thisObject) as? java.lang.ref.WeakReference<*>
                    ref?.get() as? View
                }.getOrNull()
                val focused = runCatching {
                    val ref = focusedField.get(chain.thisObject) as? java.lang.ref.WeakReference<*>
                    ref?.get() as? View
                }.getOrNull()

                if (action == MotionEvent.ACTION_DOWN) {
                    L.i(
                        "event=stylus_app_pen_down source=0x${event.source.toString(16)} " +
                            "tool=${event.getToolType(event.actionIndex)} " +
                            "connected=${describeView(connected)} focused=${describeView(focused)}",
                    )
                }
                val handled = chain.proceed()
                if (action == MotionEvent.ACTION_MOVE) {
                    L.sampled("stylus_app_pen_move", limit = 6) {
                        "event=stylus_app_pen_move handled=$handled connected=${describeView(connected)} " +
                            "focused=${describeView(focused)}"
                    }
                }
                handled
            }

        module.hook(start)
            .setId("stylus_app_start_diagnostic")
            .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
            .intercept { chain ->
                val view = chain.getArg(0) as? View
                if (view != null && isActiveOverride(view)) {
                    L.i("event=stylus_app_start_handwriting view=${describeView(view)}")
                }
                chain.proceed()
            }
        true
    } catch (t: Throwable) {
        // 诊断不属于兼容链的必要条件；ROM 改名时不能拖累打开/关闭连接两个核心 Hook。
        L.e("event=stylus_app_diagnostic_hook_failed", t)
        false
    }

    private fun onInputConnectionOpened(
        view: View,
        @Suppress("UNUSED_PARAMETER") inputConnection: InputConnection,
        editorInfo: EditorInfo,
    ): Boolean {
        if (!HookPrefs.stylusEnabled(module)) {
            restoreOverrideNow(view, "feature_disabled")
            return false
        }

        val packageName = editorInfo.packageName?.takeIf { it.isNotBlank() }
            ?: view.context.packageName
        if (!HookPrefs.stylusAppAllowed(packageName)) {
            restoreOverrideNow(view, "app_not_allowed")
            L.sampled("stylus_app_not_allowed", limit = 6) {
                "event=stylus_app_candidate rejected=app_not_allowed package=$packageName"
            }
            return false
        }

        val rejection = rejectionReason(view, editorInfo)
        if (rejection != null) {
            // 同一个自绘 View 可能切换 inputType；一旦变成密码字段，立即撤销旧放行。
            restoreOverrideNow(view, "ineligible_$rejection")
            L.sampled("stylus_app_candidate_rejected", limit = 12) {
                "event=stylus_app_candidate rejected=$rejection view=${view.javaClass.name} " +
                    "inputType=0x${editorInfo.inputType.toString(16)}"
            }
            return false
        }

        val autoBefore = runCatching { view.isAutoHandwritingEnabled }.getOrDefault(false)
        val forceBefore = runCatching { getForceDisableHandwriting(view) }.getOrDefault(true)

        val state = synchronized(stateLock) {
            activeOverrides[view]?.also { it.connectionCount++ }
                ?: if (autoBefore && !forceBefore) {
                    null
                } else {
                    OverrideState(autoBefore, forceBefore, 1).also { activeOverrides[view] = it }
                }
        }

        // 编辑器本来就处于系统支持状态，不需要改写，也不接管它的生命周期。
        if (state == null) {
            L.sampled("stylus_app_candidate_native") {
                "event=stylus_app_candidate native=true view=${view.javaClass.name} " +
                    "auto=$autoBefore forceDisable=$forceBefore"
            }
            return false
        }

        return try {
            // 顺序很重要：auto 的 setter 会把 handwriting-area 更新投递到 View 队列；先清 force，
            // 那个更新任务执行时才能把此 View 加入候选集合。
            setForceDisableHandwriting(view, false)
            view.setAutoHandwritingEnabled(true)

            // MIUI 把同一判定附加进 EditorInfo。此时补成 true，保证应用回调以及后续重启输入
            // 看到的元数据与 View 状态一致；不覆盖其它 extras。
            val extras = editorInfo.extras ?: Bundle().also { editorInfo.extras = it }
            extras.putBoolean(EXTRA_HANDWRITING_SUPPORTED, true)

            L.i(
                "event=stylus_app_fallback view=${view.javaClass.name} " +
                    "inputType=0x${editorInfo.inputType.toString(16)} " +
                    "auto=$autoBefore->true forceDisable=$forceBefore->false " +
                    "connections=${state.connectionCount}",
            )
            true
        } catch (t: Throwable) {
            L.e("event=stylus_app_fallback_failed view=${view.javaClass.name}", t)
            restoreOverrideNow(view, "apply_failed")
            false
        }
    }

    private fun rejectionReason(view: View, editorInfo: EditorInfo): String? = when {
        !view.hasFocus() -> "not_focused"
        !view.isAttachedToWindow -> "not_attached"
        !view.isShown -> "not_visible"
        !view.isEnabled -> "disabled"
        !runCatching { view.onCheckIsTextEditor() }.getOrDefault(false) -> "not_editor"
        isPasswordInputType(editorInfo.inputType) -> "password"
        else -> null
    }

    /** 与真机 `IViewStub.isAnyPasswordInputType()` 的四个分支保持一致。 */
    private fun isPasswordInputType(inputType: Int): Boolean {
        val classAndVariation = inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
        return classAndVariation == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) ||
            classAndVariation == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
            classAndVariation == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) ||
            classAndVariation == (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun releaseOverride(view: View, reason: String) {
        var restore: OverrideState? = null
        synchronized(stateLock) {
            val state = activeOverrides[view] ?: return
            state.connectionCount--
            if (state.connectionCount <= 0) {
                activeOverrides.remove(view)
                restore = state
            }
        }
        restore?.let { restoreView(view, it, reason) }
    }

    private fun restoreOverrideNow(view: View, reason: String) {
        val state = synchronized(stateLock) { activeOverrides.remove(view) } ?: return
        restoreView(view, state, reason)
    }

    private fun hasActiveOverride(): Boolean = synchronized(stateLock) { activeOverrides.isNotEmpty() }

    private fun isActiveOverride(view: View): Boolean = synchronized(stateLock) {
        activeOverrides.containsKey(view)
    }

    private fun describeView(view: View?): String {
        if (view == null) return "none"
        val forceDisabled = runCatching { getForceDisableHandwriting(view) }.getOrNull()
        val available = runCatching {
            view.javaClass.getMethod("isStylusHandwritingAvailable").invoke(view) as? Boolean
        }.getOrNull()
        return "${view.javaClass.name}{focus=${view.hasFocus()},auto=${view.isAutoHandwritingEnabled}," +
            "force=$forceDisabled,available=$available,shown=${view.isShown}}"
    }

    private fun restoreView(view: View, state: OverrideState, reason: String) {
        runCatching {
            setForceDisableHandwriting(view, state.originalForceDisable)
            view.setAutoHandwritingEnabled(state.originalAuto)
        }.onSuccess {
            L.i(
                "event=stylus_app_restore reason=$reason view=${view.javaClass.name} " +
                    "auto=${state.originalAuto} forceDisable=${state.originalForceDisable}",
            )
        }.onFailure {
            L.e("event=stylus_app_restore_failed reason=$reason view=${view.javaClass.name}", it)
        }
    }

    private fun resolveForceDisableMethods(cl: ClassLoader): Boolean {
        if (forceDisableGetter != null && forceDisableSetter != null) return true
        return try {
            val viewClass = cl.loadClass("android.view.View")
            forceDisableGetter = viewClass.getDeclaredMethod("isForceDisableHandwriting")
                .apply { isAccessible = true }
            forceDisableSetter = viewClass.getDeclaredMethod(
                "setForceDisableHandwriting",
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            true
        } catch (t: Throwable) {
            L.e("event=stylus_app_force_disable_api_missing", t)
            false
        }
    }

    private fun getForceDisableHandwriting(view: View): Boolean =
        forceDisableGetter?.invoke(view) as? Boolean
            ?: error("isForceDisableHandwriting is unavailable")

    private fun setForceDisableHandwriting(view: View, value: Boolean) {
        val setter = forceDisableSetter ?: error("setForceDisableHandwriting is unavailable")
        setter.invoke(view, value)
    }

    private companion object {
        const val CLS_INPUT_METHOD_MANAGER = "android.view.inputmethod.InputMethodManager"
        const val CLS_HANDWRITING_INITIATOR = "android.view.HandwritingInitiator"
        const val EXTRA_HANDWRITING_SUPPORTED =
            "com.miui.inputmethod.EditorInfo.HANDWRITING_SUPPORTED"
    }
}
