package io.github.skyshadowhero.fancypad.hooks

import io.github.skyshadowhero.fancypad.*

import android.content.Intent
import android.content.res.Configuration
import android.graphics.PointF
import android.hardware.input.InputManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.InputDevice
import android.view.inputmethod.DeleteGesture
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.HandwritingGesture
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InsertGesture
import android.view.inputmethod.InsertModeGesture
import android.view.inputmethod.JoinOrSplitGesture
import android.view.inputmethod.SelectGesture
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.Executor
import java.util.function.IntConsumer

/**
 * FancyPad · 随手写 —— **输入法进程侧**（作用域 `com.xiaomi.type`）。
 *
 * 目标是把小爱输入法做成搜狗那样：笔在任意输入框上一落就能写，写的时候屏幕上常驻一块
 * 可拖动的操作面板，抬笔出字，面板不走。
 *
 * ## 补的是哪四个回调
 *
 * AOSP 的 `InputMethodService.onStartStylusHandwriting()` **默认返回 false**，
 * 小爱（`MiInputMethodService`）没有实现这四个 stylus 回调，系统走到这里会打
 * `"IME is not ready. Can't start Stylus Handwriting"` 然后放弃。所以：
 *
 * | 回调 | 处理 |
 * |---|---|
 * | `onStartStylusHandwriting()` | 返回 true —— 会话才开得起来；顺带挂笔迹、进随手写模式 |
 * | `onPrepareStylusHandwriting()` | 只记会话目标（**不**初始化：两边都清会清两次墨迹） |
 * | `onStylusHandwritingMotionEvent(MotionEvent)` | 先判面板、再画笔迹 + 攒笔迹识别 |
 * | `onFinishStylusHandwriting()` | 收尾：把攒着没到点的笔迹当场清账 |
 *
 * ## 会话短、面板长
 *
 * 会话活着的时候 system_server 的 `HandwritingModeController` 会 `pilferPointers`
 * 把笔从宿主应用手里抢给输入法，所以会话越短越好（[sessionTimeoutMs]）；
 * 但**面板**是挂在输入法窗口里的独立浮层（见 [StylusToolbar]），会话结束它不走 ——
 * 这正是搜狗的分工：`MiuiHandWritingIMEStylus` 管会话，`ImeMenuViewHolder` 管面板。
 *
 * 进出随手写模式：
 * - **进**：`onUpdateEditorToolType(STYLUS)` 只负责首笔预挂 UI，真正的识别会话仍由
 *   `onStartStylusHandwriting` 确认。实体键盘下也不能跳过这次预挂，否则第一笔只会让
 *   IME 宿主变为可用，必须再点一次才能看到工具栏。
 *   `TOOL_TYPE_FINGER` 不作为退出信号（手指点面板按钮时也会报）。
 * - **出**：点「键盘」键 / 输入法窗口隐藏 / 输入连接结束。
 *
 * ## 识别只有一条路：系统笔引擎
 *
 * [PencilEngine]（`/system_ext/framework/xiaomi-pencilengine-pad.jar` +
 * `/system_ext/etc/ocr_model.tflite`）。早先还有一条"借小爱自带讯飞 HCR（反射 `v4.x`）"
 * 的路，已整体删除：它吃小爱内部实现、随版本改名就断，还和系统笔引擎抢同一份引擎状态。
 *
 * ## 写输入框前必须校验身份
 *
 * 会话结束后 `currentInputConnection` 随时可能换人。识别结果的写入一律先过
 * [writeAllowed]（比对 [sessionIc] 身份）；面板按钮是用户当场的动作，用当前连接。
 */
class StylusImeHooks(private val module: XposedModule) {

    @Volatile
    private var installed = false

    fun resetInstalled() {
        installed = false
    }

    /** 热重载前：停掉定时任务、摘掉面板、清半截会话（旧一代被冻结后线程仍在跑）。 */
    fun dispose() {
        runCatching { workerHandler?.removeCallbacksAndMessages(null) }
        runCatching { mainHandler.removeCallbacksAndMessages(null) }
        runCatching { toolbar.hide() }
        runCatching { restoreFloatingKeyboard() }
        synchronized(stateLock) {
            strokes.clear()
            current = null
        }
        strokeActive = false
        sessionActive = false
        runCatching { machine.reset() }
        lifecycleGuard.reset()
        sessionIme = null
        sessionIc = null
        lastEditorToolType = MotionEvent.TOOL_TYPE_UNKNOWN
        lastComposingText = null
        finalizeAfterRecognize = false
        undoStack.clear()
        redoStack.clear()
    }

    /** 目标输入法类 `com.mi.ime.MiInputMethodService`，用于 instanceof 守卫。 */
    @Volatile
    private var imeClass: Class<*>? = null

    /** 当前输入法实例（`onStartInputView` 时记下）：面板按钮的兜底目标。 */
    @Volatile
    private var imeInstance: InputMethodService? = null

    /** 三态（屏幕键盘 / 已连接键盘 / 随手写）的唯一真源，见 [StylusModeMachine]。 */
    private val machine = StylusModeMachine()

    /** 过滤系统进入随手写时必经的瞬时 `onWindowHidden` / `onFinishInput`。 */
    private val lifecycleGuard = StylusLifecycleGuard()

    /** 当前是否处在随手写状态（面板在、键盘被面板盖住）。 */
    private val stylusMode: Boolean
        get() = machine.mode == StylusModeMachine.Mode.STYLUS

    /** 最近一次由系统上报的落笔工具类型；搜狗也用这条回调在首笔轻点时切换手写 UI。 */
    @Volatile
    private var lastEditorToolType = MotionEvent.TOOL_TYPE_UNKNOWN

    // ---- 会话状态 ----
    private val stateLock = Any()
    private val strokes = ArrayList<List<PencilEngine.Pt>>()
    private var current: ArrayList<PencilEngine.Pt>? = null

    @Volatile
    private var sessionIme: InputMethodService? = null

    /** 本会话第几笔，仅诊断。 */
    @Volatile
    private var sessionStrokeCount = 0

    /** 防止框架对同一会话重复 start 时再次清掉已经开始绘制的首笔。 */
    @Volatile
    private var sessionActive = false

    /** 当前是否有笔画进行中（DOWN 之后、UP/CANCEL 之前）。 */
    @Volatile
    private var strokeActive = false

    // ---- 笔迹显示 ----
    private val ink = StylusInkOverlay()

    // ---- 轻点判定 ----
    @Volatile private var downTime = 0L
    @Volatile private var downX = 0f
    @Volatile private var downY = 0f
    @Volatile private var maxDist = 0f
    @Volatile private var moveEvents = 0

    // ---- 书写手势 ----
    private val gestureEngine = StylusGestureEngine()
    @Volatile private var prevStroke: List<PencilEngine.Pt>? = null
    @Volatile private var lastStrokeUpTime = 0L
    @Volatile private var idleBeforeStroke = Long.MAX_VALUE

    /** 本次会话用的 `InputConnection`（写入前比对身份）。 */
    @Volatile
    private var sessionIc: InputConnection? = null

    /** 我们最后一次放进组词区的文字。 */
    @Volatile
    private var lastComposingText: String? = null

    /** 会话结束时要补一次"识别完顺手落定"。 */
    @Volatile
    private var finalizeAfterRecognize = false

    /**
     * 撤回 / 重做栈：**只记我们自己上屏的整段文字**。
     *
     * 搜狗把这两个键接到编辑器的 `KEYCODE_UNDO/REDO`（Ctrl+Z / Ctrl+Y），
     * 很多应用（WebView 输入框、自绘编辑器）根本不接。这里用自己的历史：
     * 撤回 = 删掉最近一次上屏的那一段（删完压进 [redoStack]），重做 = 再写回去。
     * 历史对不上（用户中间自己改过字）就退回编辑器原生的 undo/redo 键。
     */
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()

    // ---- 面板 ----
    private val toolbar = StylusToolbar()

    // ---- 后台识别线程 ----
    @Volatile private var worker: HandlerThread? = null
    @Volatile private var workerHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- 系统笔引擎 ----
    private val engineLock = Any()
    @Volatile private var engine: PencilEngine? = null
    @Volatile private var engineTried = false

    private val recognizeTask = Runnable { recognizeAndCommit() }

    /**
     * @return true = 已装好（含「本来就装过」）；false = 目标类还没加载出来，可以重试。
     */
    fun install(cl: ClassLoader): Boolean {
        if (installed) return true
        val ime = runCatching { cl.loadClass(CLS_MI_IME) }.getOrNull()
        if (ime == null) {
            L.w("event=stylus_ime_class_missing（随手写只在输入法进程生效，稍后重试）")
            return false
        }
        imeClass = ime
        installed = true
        toolbar.onAction = { action -> runCatching { onToolbarAction(action) } }
        hookStart()
        hookPrepare()
        hookMotion()
        hookFinish()
        hookEditorToolType()
        hookInputView()
        hookComputeInsets()
        hookFinishInput()
        hookWindowHidden()
        hookConfigurationChanged()
        hookPhysicalKeyboardExit()
        hookKeyboardPopupWindow(cl)
        hookPopupWindows()
        return true
    }

    /**
     * 登记输入法进程里所有传统 `PopupWindow`（悬浮键盘本体、悬浮候选条、剪贴板预览 …）。
     *
     * 它们都是**独立窗口**：`hideKeyboardContent(decor)` 遍历不到，`w2.p` 那套 Compose
     * Popup 也只覆盖悬浮工具栏。随手写期间由 [StylusToolbar.trackPopupWindow] 统一把内容
     * 藏起来并取消可触摸，退出时按登记的原状态还原。
     */
    private fun hookPopupWindows() {
        try {
            val ctor = android.widget.PopupWindow::class.java.getDeclaredConstructor(
                android.view.View::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
            ctor.isAccessible = true
            module.hook(ctor)
                .setId("stylus_ime_popup_window")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? android.widget.PopupWindow)
                        ?.let { toolbar.trackPopupWindow(it) }
                    result
                }
            L.i("event=stylus_popup_window_hooked")
        } catch (t: Throwable) {
            L.w("event=stylus_popup_window_hook_failed msg=${t.message}")
        }
    }

    /**
     * 实体键盘真正开始输入时，从随手写切回「已连接键盘」状态；只观察，不消费按键。
     *
     * ⚠ `InputMethodService.onKeyDown` 只在**输入法自己的窗口拿到焦点**时才会回调
     * （全屏提取、悬浮键盘之类）；普通应用窗口里的实体键盘按键直达应用，不经过这里。
     * 所以它是退出的**加速信号**，不是唯一依据：连接 / 断开由 `InputDeviceListener`
     * 校正，换输入框时由 [hookInputView] 校正。真机验证看
     * `event=stylus_physical_key_exit` 这条日志有没有出现。
     */
    private fun hookPhysicalKeyboardExit() {
        try {
            val m = (imeClass ?: InputMethodService::class.java).getMethod(
                "onKeyDown",
                Int::class.javaPrimitiveType,
                KeyEvent::class.java,
            )
            module.hook(m)
                .setId("stylus_ime_physical_key_exit")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val self = chain.thisObject
                    val event = chain.getArg(1) as? KeyEvent
                    if (isTarget(self) && event?.isPhysicalKeyboardEvent() == true &&
                        (stylusMode || toolbar.isShowing || sessionActive)
                    ) {
                        lastEditorToolType = MotionEvent.TOOL_TYPE_UNKNOWN
                        // 状态与面板必须在当前按键回调里先收回去，否则同一个按键会落进
                        // 一个不可触摸的面板；真正的 finishStylusHandwriting 由
                        // exitStylusMode 内部 post 到主线程队列。
                        exitStylusMode("physical_keyboard", finishSession = true)
                        L.i("event=stylus_physical_key_exit")
                    }
                    chain.proceed()
                }
        } catch (t: Throwable) {
            L.e("event=stylus_hook_physical_key_failed", t)
        }
    }

    private fun KeyEvent.isPhysicalKeyboardEvent(): Boolean =
        action == KeyEvent.ACTION_DOWN &&
            keyCode != KeyEvent.KEYCODE_UNKNOWN &&
            deviceId != android.view.KeyCharacterMap.VIRTUAL_KEYBOARD &&
            flags and KeyEvent.FLAG_SOFT_KEYBOARD == 0 &&
            isFromSource(InputDevice.SOURCE_KEYBOARD)

    /**
     * 连接 / 断开实体键盘只做状态校正，**不退出**已经确认的随手写会话。
     * 三种状态彼此独立：屏幕键盘、已连接键盘、随手写；真实落笔可以从前两种进入随手写。
     */
    private fun hookConfigurationChanged() {
        try {
            val m = (imeClass ?: InputMethodService::class.java).getMethod(
                "onConfigurationChanged",
                Configuration::class.java,
            )
            module.hook(m)
                .setId("stylus_ime_hardware_keyboard_change")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val result = chain.proceed()
                    val self = chain.thisObject
                    if (isTarget(self)) {
                        val ime = self as InputMethodService
                        // 配置变化是「键盘真的换了一种」的权威信号：缓存必须作废，
                        // 三态也要跟着重新校正（随手写期间不动）。
                        invalidatePhysicalKeyboard()
                        reconcileMode(ime, "configuration_changed")
                        if (hasPhysicalKeyboard(ime)) {
                            // 此回调本身不能证明是手指还是笔，只清理用于"预显示"的缓存。
                            // 真正的笔会随后进入 onStartStylusHandwriting，由会话切到随手写状态。
                            lastEditorToolType = MotionEvent.TOOL_TYPE_UNKNOWN
                        }
                    }
                    result
                }
        } catch (t: Throwable) {
            L.e("event=stylus_hook_configuration_failed", t)
        }
    }

    // ---- 实体键盘探测（带缓存） ----

    /**
     * 上次探测到的实体键盘状态。
     *
     * 枚举 `InputManager.inputDeviceIds` 并逐 id 调 `getInputDevice()` 全是 binder 调用，
     * 而 [hookEditorToolType] 在**每次落笔**都会问一次，所以结果按
     * [PHYSICAL_KEYBOARD_TTL_MS] 缓存；设备增删、配置变化会立即作废。
     */
    @Volatile
    private var physicalKeyboardCached = false

    @Volatile
    private var physicalKeyboardAtMs = 0L

    @Volatile
    private var inputDeviceListener: InputManager.InputDeviceListener? = null

    /** 只认真实、已启用的字母键盘；软键盘与手写笔设备不会命中。 */
    private fun hasPhysicalKeyboard(ime: InputMethodService): Boolean {
        ensureInputDeviceListener(ime)
        val now = SystemClock.uptimeMillis()
        val age = now - physicalKeyboardAtMs
        if (physicalKeyboardAtMs != 0L && age in 0..PHYSICAL_KEYBOARD_TTL_MS) {
            return physicalKeyboardCached
        }
        val value = probePhysicalKeyboard(ime)
        physicalKeyboardCached = value
        physicalKeyboardAtMs = now
        return value
    }

    private fun invalidatePhysicalKeyboard() {
        physicalKeyboardAtMs = 0L
    }

    private fun probePhysicalKeyboard(ime: InputMethodService): Boolean {
        val fromDevices = runCatching {
            val manager = ime.getSystemService(InputManager::class.java)
            manager.inputDeviceIds.any { id ->
                val device = manager.getInputDevice(id) ?: return@any false
                device.isEnabled &&
                    !device.isVirtual &&
                    device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
                    (device.sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
            }
        }.getOrDefault(false)
        // 设备列表是主判据；Configuration 只作兜底（部分 ROM 在设备已禁用时仍报 QWERTY）。
        return fromDevices || ime.resources.configuration.keyboard == Configuration.KEYBOARD_QWERTY
    }

    /**
     * 设备增删是**事件**信号，不依赖任何按键回调，所以三态的「已连接键盘」在
     * 连接/拔出的一瞬间就能校正，不必等下一次 `onStartInputView`。
     */
    private fun ensureInputDeviceListener(ime: InputMethodService) {
        if (inputDeviceListener != null) return
        val manager = runCatching { ime.getSystemService(InputManager::class.java) }.getOrNull()
            ?: return
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = onInputDevicesChanged("device_added")

            override fun onInputDeviceRemoved(deviceId: Int) =
                onInputDevicesChanged("device_removed")

            override fun onInputDeviceChanged(deviceId: Int) =
                onInputDevicesChanged("device_changed")
        }
        runCatching { manager.registerInputDeviceListener(listener, mainHandler) }
            .onSuccess {
                inputDeviceListener = listener
                L.i("event=stylus_input_device_listener_installed")
            }
            .onFailure { L.w("event=stylus_input_device_listener_failed msg=${it.message}") }
    }

    private fun onInputDevicesChanged(reason: String) {
        invalidatePhysicalKeyboard()
        val ime = imeInstance ?: sessionIme ?: return
        reconcileMode(ime, reason)
    }

    /**
     * 把「此刻有没有实体键盘」同步进 [machine]（随手写期间不动，三态独立）。
     *
     * @return 是否发生了迁移
     */
    private fun reconcileMode(ime: InputMethodService, reason: String): Boolean {
        if (machine.mode == StylusModeMachine.Mode.STYLUS) return false
        if (!machine.onKeyboardAvailability(hasPhysicalKeyboard(ime))) return false
        L.i("event=stylus_mode_change mode=${machine.mode} reason=$reason")
        return true
    }

    /**
     * 悬浮键盘右下角工具栏是独立的 Compose Popup（不属于 IME decorView）。
     * 登记它的宿主 View，随手写工具栏显示时才能同步隐藏，避免两套工具栏同时出现。
     */
    private fun hookKeyboardPopupWindow(cl: ClassLoader) {
        try {
            val popup = cl.loadClass(CLS_COMPOSE_POPUP)
            val ctor = popup.declaredConstructors.firstOrNull { it.parameterCount == 7 }
                ?: run {
                    L.w("event=stylus_keyboard_popup_ctor_missing class=$CLS_COMPOSE_POPUP")
                    return
                }
            ctor.isAccessible = true
            module.hook(ctor)
                .setId("stylus_ime_keyboard_popup")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? android.view.View)?.let { toolbar.trackKeyboardPopupWindow(it) }
                    result
                }

            // PopupLayout.j(...) 会在 Compose 重组时整包重写 WindowManager.LayoutParams.flags；
            // 随手写期间需在它之后重新补回 NOT_TOUCHABLE，否则透明弹窗又会抢走点击。
            popup.declaredMethods.firstOrNull { it.name == "j" && it.parameterCount == 4 }
                ?.also { it.isAccessible = true }
                ?.let { update ->
                    module.hook(update)
                        .setId("stylus_ime_keyboard_popup_flags")
                        .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                        .intercept { chain ->
                            val result = chain.proceed()
                            (chain.thisObject as? android.view.View)?.let { toolbar.trackKeyboardPopupWindow(it) }
                            result
                        }
                }
            L.i("event=stylus_keyboard_popup_hooked class=$CLS_COMPOSE_POPUP")
        } catch (t: Throwable) {
            // 混淆名会随输入法版本变化；失配只降级为旧行为，不能影响手写主链路。
            L.w("event=stylus_keyboard_popup_hook_failed class=$CLS_COMPOSE_POPUP msg=${t.message}")
        }
    }

    // ------------------------------------------------------------------ 回调

    private fun hookStart() {
        try {
            val m = InputMethodService::class.java.getDeclaredMethod("onStartStylusHandwriting")
            module.hook(m)
                .setId("stylus_ime_on_start")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    if (!isTarget(chain.thisObject) || !HookPrefs.stylusEnabled(module)) {
                        chain.proceed()
                    } else {
                        beginSession(chain.thisObject as InputMethodService)
                        true
                    }
                }
        } catch (t: Throwable) {
            L.e("event=stylus_hook_start_failed", t)
        }
    }

    private fun hookPrepare() {
        try {
            val m = InputMethodService::class.java.getDeclaredMethod("onPrepareStylusHandwriting")
            module.hook(m)
                .setId("stylus_ime_on_prepare")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    if (isTarget(chain.thisObject) && HookPrefs.stylusEnabled(module)) {
                        val ime = chain.thisObject as InputMethodService
                        sessionIme = ime
                        imeInstance = ime
                    }
                    chain.proceed()
                }
        } catch (t: Throwable) {
            L.e("event=stylus_hook_prepare_failed", t)
        }
    }

    private fun hookMotion() {
        try {
            val m = InputMethodService::class.java.getDeclaredMethod(
                "onStylusHandwritingMotionEvent", MotionEvent::class.java,
            )
            module.hook(m)
                .setId("stylus_ime_on_motion")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val self = chain.thisObject
                    if (isTarget(self) && HookPrefs.stylusEnabled(module)) {
                        val ev = chain.getArg(0) as? MotionEvent
                        if (ev != null) {
                            runCatching { onStylusMotion(self as InputMethodService, ev) }
                                .onFailure { L.e("event=stylus_motion_failed", it) }
                        }
                    }
                    chain.proceed()
                }
        } catch (t: Throwable) {
            L.e("event=stylus_hook_motion_failed", t)
        }
    }

    private fun hookFinish() {
        try {
            val m = InputMethodService::class.java.getDeclaredMethod("onFinishStylusHandwriting")
            module.hook(m)
                .setId("stylus_ime_on_finish")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val self = chain.thisObject
                    if (isTarget(self) && HookPrefs.stylusEnabled(module)) {
                        runCatching { endSession(self as InputMethodService) }
                            .onFailure { L.e("event=stylus_finish_failed", it) }
                    }
                    chain.proceed()
                }
        } catch (t: Throwable) {
            L.e("event=stylus_hook_finish_failed", t)
        }
    }

    /** 笔轻点输入框时系统先上报 toolType=STYLUS；无需等到划过 slop 才显示手写 UI。 */
    private fun hookEditorToolType() {
        try {
            val m = (imeClass ?: InputMethodService::class.java).getMethod(
                "onUpdateEditorToolType", Int::class.javaPrimitiveType,
            )
            module.hook(m)
                .setId("stylus_ime_editor_tool_type")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val result = chain.proceed()
                    val self = chain.thisObject
                    if (isTarget(self) && HookPrefs.stylusEnabled(module)) {
                        val ime = self as InputMethodService
                        val toolType = chain.getArg(0) as? Int ?: MotionEvent.TOOL_TYPE_UNKNOWN
                        val physicalKeyboard = hasPhysicalKeyboard(ime)
                        reconcileMode(ime, "editor_tool_type")
                        if (physicalKeyboard) {
                            // 不把值缓存给未来的 onStartInputView，避免它沿用一次旧 STYLUS；
                            // 但当前回调本身是首笔的新鲜信号，仍需立即预挂 UI。否则实体键盘下
                            // 第一笔只负责唤醒 IME 宿主，用户必须再点一次才会出现工具栏。
                            lastEditorToolType = MotionEvent.TOOL_TYPE_UNKNOWN
                        } else {
                            lastEditorToolType = toolType
                        }
                        if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
                            imeInstance = ime
                            val info = runCatching { ime.currentInputEditorInfo }.getOrNull()
                            if (allowsStylusUi(ime, info)) {
                                lifecycleGuard.recordStylusActivity(SystemClock.uptimeMillis())
                                val ticket = machine.ticket()
                                mainHandler.post {
                                    if (machine.isStale(ticket)) {
                                        L.i("event=stylus_tool_type_enter_skipped reason=stale")
                                        return@post
                                    }
                                    runCatching { enterStylusMode(ime) }
                                        .onFailure { L.e("event=stylus_tool_type_enter_failed", it) }
                                }
                                L.i(
                                    "event=stylus_tool_type tool=stylus enter=true " +
                                        "physicalKeyboard=$physicalKeyboard",
                                )
                            }
                        }
                    }
                    result
                }
        } catch (t: Throwable) {
            L.e("event=stylus_hook_tool_type_failed", t)
        }
    }

    private fun hookInputView() {
        try {
            val m = InputMethodService::class.java.getDeclaredMethod(
                "onStartInputView", EditorInfo::class.java, Boolean::class.javaPrimitiveType,
            )
            module.hook(m)
                .setId("stylus_ime_on_start_input_view")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val self = chain.thisObject
                    chain.proceed()
                    if (isTarget(self) && HookPrefs.stylusEnabled(module)) {
                        val ime = self as InputMethodService
                        imeInstance = ime
                        val info = chain.getArg(0) as? EditorInfo
                        val physicalKeyboard = hasPhysicalKeyboard(ime)
                        reconcileMode(ime, "start_input_view")
                        if (physicalKeyboard) {
                            lastEditorToolType = MotionEvent.TOOL_TYPE_UNKNOWN
                        }
                        if (machine.mode == StylusModeMachine.Mode.STYLUS) {
                            // 随手写优先于“已连接键盘”：requestShowSelf 可能重建输入视图，
                            // 重建后只需把工具栏重新贴回去，不能因此退回实体键盘状态。
                            //
                            // 这里的 post 和 onFinishInput / onWindowHidden 里的 exit 会交错：
                            // 只有"排队这一刻仍是随手写"才允许把面板贴回去，否则会出现
                            // "状态已退出、面板却贴在下一个输入框上"的僵尸（键盘不可见）。
                            val ticket = machine.ticket()
                            mainHandler.post {
                                if (machine.isStale(ticket) ||
                                    machine.mode != StylusModeMachine.Mode.STYLUS
                                ) {
                                    L.i("event=stylus_input_view_show_skipped reason=stale_or_exited")
                                    return@post
                                }
                                runCatching {
                                    if (HookPrefs.stylusToolbarEnabled()) {
                                        toolbar.show(ime, info ?: ime.currentInputEditorInfo)
                                        toolbar.refreshKeyboardContent()
                                        // 键盘重建后输入法可能把悬浮小键盘重新显示出来，
                                        // 这里补一次隐藏，否则随手写期间它会重新冒出来。
                                        setFloatingKeyboardHidden(ime, true)
                                    } else {
                                        exitStylusMode("toolbar_disabled")
                                    }
                                }
                            }
                        } else if (toolbar.isShowing) {
                            // 非法组合：状态不是随手写、面板却还挂着。只可能是上一次
                            // 会话的遗留，收掉，免得键盘被一块零高度面板挡住。
                            L.w("event=stylus_panel_orphan reason=start_input_view")
                            mainHandler.post { runCatching { exitStylusMode("panel_orphan") } }
                        } else if (!physicalKeyboard &&
                            lastEditorToolType == MotionEvent.TOOL_TYPE_STYLUS && allowsStylusUi(ime, info)
                        ) {
                            mainHandler.post {
                                runCatching { enterStylusMode(ime) }
                                    .onFailure { L.e("event=stylus_input_view_enter_failed", it) }
                            }
                        }
                    }
                }
        } catch (t: Throwable) {
            L.e("event=stylus_hook_input_view_failed", t)
        }
    }

    /**
     * 随手写工具栏是悬浮层，不应像普通软键盘一样占用应用底部空间。
     *
     * 保留 IME 窗口本身作为工具栏宿主，但把 content/visible 的顶部都上报到窗口底边，
     * 等价于 IME 占用高度为 0；工具栏的可点击矩形仍由 [StylusToolbar] 单独上报。
     */
    private fun hookComputeInsets() {
        try {
            val m = (imeClass ?: InputMethodService::class.java).getMethod(
                "onComputeInsets",
                InputMethodService.Insets::class.java,
            )
            module.hook(m)
                .setId("stylus_ime_compute_insets")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    val result = chain.proceed()
                    val self = chain.thisObject
                    if (isTarget(self) &&
                        (machine.mode == StylusModeMachine.Mode.STYLUS || toolbar.isShowing)
                    ) {
                        val ime = self as InputMethodService
                        val out = chain.getArg(0) as? InputMethodService.Insets
                        val decorHeight = runCatching { ime.window?.window?.decorView?.height }
                            .getOrNull()
                            ?: 0
                        if (out != null && decorHeight > 0) {
                            out.contentTopInsets = decorHeight
                            out.visibleTopInsets = decorHeight
                        }
                    }
                    result
                }
        } catch (t: Throwable) {
            L.e("event=stylus_hook_compute_insets_failed", t)
        }
    }

    private fun hookFinishInput() {
        try {
            val m = InputMethodService::class.java.getDeclaredMethod("onFinishInput")
            module.hook(m)
                .setId("stylus_ime_on_finish_input")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    chain.proceed()
                    val self = chain.thisObject
                    if (isTarget(self) && (stylusMode || toolbar.isShowing)) {
                        handleImeLifecycleCallback(
                            ime = self as InputMethodService,
                            reason = "finish_input",
                        )
                    }
                }
        } catch (t: Throwable) {
            L.e("event=stylus_hook_finish_input_failed", t)
        }
    }

    private fun hookWindowHidden() {
        try {
            val m = InputMethodService::class.java.getDeclaredMethod("onWindowHidden")
            module.hook(m)
                .setId("stylus_ime_on_window_hidden")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    chain.proceed()
                    val self = chain.thisObject
                    if (isTarget(self) && (stylusMode || toolbar.isShowing)) {
                        handleImeLifecycleCallback(
                            ime = self as InputMethodService,
                            reason = "window_hidden",
                        )
                    }
                }
        } catch (t: Throwable) {
            L.e("event=stylus_hook_window_hidden_failed", t)
        }
    }

    /**
     * Android 切入系统手写时可能短暂结束输入或隐藏普通 IME 窗口。先依据最近的
     * 手写活动保护状态，再延时确认：若输入确实没有恢复才退出；否则只恢复工具栏宿主，
     * 绝不清首笔。机器 generation 同时保证旧回调不会误伤后来开启的新会话。
     */
    private fun handleImeLifecycleCallback(
        ime: InputMethodService,
        reason: String,
    ) {
        val action = if (stylusMode) {
            lifecycleGuard.onLifecycleCallback(
                nowMs = SystemClock.uptimeMillis(),
                handwritingSessionActive = sessionActive,
            )
        } else {
            StylusLifecycleAction.EXIT_STYLUS
        }
        val ticket = machine.ticket()
        if (action == StylusLifecycleAction.EXIT_STYLUS) {
            mainHandler.post {
                if (!machine.isStale(ticket)) {
                    runCatching { exitStylusMode(reason) }
                }
            }
            return
        }

        L.i(
            "event=stylus_lifecycle_preserved reason=$reason " +
                "session=$sessionActive generation=$ticket",
        )
        mainHandler.post {
            if (machine.isStale(ticket) || machine.mode != StylusModeMachine.Mode.STYLUS) {
                return@post
            }
            runCatching { ime.requestShowSelf(0) }
                .onSuccess { L.i("event=stylus_window_host_restore_requested reason=$reason") }
                .onFailure {
                    L.w("event=stylus_window_host_restore_failed reason=$reason msg=${it.message}")
                }
        }

        // onFinishInput 返回后 framework 才会把 currentInputStarted 置 false；必须延时确认，
        // 不能在回调栈内读取。瞬时重启会在此之前重新变 true，真正离开输入框则保持 false。
        mainHandler.postDelayed(
            {
                if (machine.isStale(ticket) ||
                    machine.mode != StylusModeMachine.Mode.STYLUS
                ) {
                    return@postDelayed
                }
                if (lifecycleGuard.onDeferredConfirmation(ime.currentInputStarted) ==
                    StylusLifecycleAction.EXIT_STYLUS
                ) {
                    L.i("event=stylus_lifecycle_exit_confirmed reason=$reason")
                    exitStylusMode(reason)
                    return@postDelayed
                }
                if (!toolbar.isShowing) attachPanel(ime)
                if (toolbar.isShowing) {
                    toolbar.refreshKeyboardContent()
                    setFloatingKeyboardHidden(ime, true)
                    L.i("event=stylus_lifecycle_recovered reason=$reason")
                } else {
                    rollbackStylusMode(ime, "lifecycle_host_not_attached")
                }
            },
            LIFECYCLE_CONFIRM_DELAY_MS,
        )
    }

    // ------------------------------------------------------------------ 笔迹

    /**
     * 把一个**历史采样点**补进两处：笔迹画布、当前笔画的点集。
     *
     * 高刷笔的一次 MOVE 往往还带着 1~3 个更早的采样点（`getHistoricalX/Y`），
     * 只取当前点等于把轨迹抽稀 —— 白捡的识别率损失。
     */
    private fun acceptExtraPoint(
        rawX: Float,
        rawY: Float,
        time: Long,
        canvasX: Float,
        canvasY: Float,
    ) {
        if (HookPrefs.stylusInkEnabled()) ink.view?.addPoint(canvasX, canvasY, ACTION_MOVE)
        synchronized(stateLock) {
            current?.add(PencilEngine.Pt(rawX, rawY, ACTION_MOVE, time))
        }
    }

    private fun onStylusMotion(ime: InputMethodService, ev: MotionEvent) {
        lifecycleGuard.recordStylusActivity(SystemClock.uptimeMillis())
        val action = ev.actionMasked
        val rawX = ev.rawX
        val rawY = ev.rawY
        val time = ev.eventTime

        // ---- 面板优先 ----
        //
        // 会话期间笔事件只走这一条回调（不经过 View 树），所以面板的按钮/拖动要在这里接一份。
        // 笔落在面板上就整笔剔除：不进墨迹、不进识别、也不算手势。
        if (toolbar.isShowing) {
            val onPanel = if (action == MotionEvent.ACTION_DOWN) {
                toolbar.hitTest(rawX, rawY)
            } else {
                toolbar.penActive
            }
            if (onPanel) {
                if (action == MotionEvent.ACTION_DOWN) L.i("event=stylus_stroke_on_toolbar")
                toolbar.dispatchPen(ev)
                strokeActive = false
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    lastStrokeUpTime = time
                }
                return
            }
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downTime = time
                downX = rawX
                downY = rawY
                maxDist = 0f
                moveEvents = 0
                strokeActive = true
                idleBeforeStroke =
                    if (lastStrokeUpTime > 0L) time - lastStrokeUpTime else Long.MAX_VALUE
                ink.sync()
            }

            MotionEvent.ACTION_MOVE -> {
                moveEvents++
                val dx = rawX - downX
                val dy = rawY - downY
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                if (d > maxDist) maxDist = d
            }
        }

        val canvasLoc = ink.canvasOffset()
        val canvasX = if (canvasLoc != null) rawX - canvasLoc[0] else rawX
        val canvasY = if (canvasLoc != null) rawY - canvasLoc[1] else rawY

        if (action == MotionEvent.ACTION_MOVE && ev.historySize > 0) {
            val rawShiftX = rawX - ev.x
            val rawShiftY = rawY - ev.y
            for (i in 0 until ev.historySize) {
                val hx = ev.getHistoricalX(0, i) + rawShiftX
                val hy = ev.getHistoricalY(0, i) + rawShiftY
                val hdx = hx - downX
                val hdy = hy - downY
                val hd = kotlin.math.hypot(hdx.toDouble(), hdy.toDouble()).toFloat()
                if (hd > maxDist) maxDist = hd
                moveEvents++
                acceptExtraPoint(
                    rawX = hx,
                    rawY = hy,
                    time = ev.getHistoricalEventTime(i),
                    canvasX = if (canvasLoc != null) hx - canvasLoc[0] else hx,
                    canvasY = if (canvasLoc != null) hy - canvasLoc[1] else hy,
                )
            }
        }

        // 轻点 = 点击（不写字）：位移不到 touchSlop，而且落笔前停过一下。
        // 与框架 `HandwritingInitiator` 用同一个 touchSlop，两边判断一致。
        val isTap = action == MotionEvent.ACTION_UP &&
            maxDist <= tapSlop(ime) &&
            idleBeforeStroke >= TAP_IDLE_MS

        if (HookPrefs.stylusInkEnabled() && !isTap) {
            ink.view?.addPoint(canvasX, canvasY, action)
        }

        var justFinished: List<PencilEngine.Pt>? = null

        synchronized(stateLock) {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    current = ArrayList<PencilEngine.Pt>(64).also {
                        it.add(PencilEngine.Pt(rawX, rawY, ACTION_DOWN, time))
                    }
                }

                MotionEvent.ACTION_MOVE -> current?.add(PencilEngine.Pt(rawX, rawY, ACTION_MOVE, time))

                MotionEvent.ACTION_UP -> {
                    current?.let {
                        it.add(PencilEngine.Pt(rawX, rawY, ACTION_UP, time))
                        strokes.add(it)
                        justFinished = it
                    }
                    current = null
                }

                MotionEvent.ACTION_CANCEL -> current = null
            }
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            strokeActive = false
            lastStrokeUpTime = time
        }

        if (isTap) {
            // 轻点：把这一笔从待识别集合里摘掉（不能清空 —— 刚写完还没到延迟的那批要留着），
            // 抹掉墨迹，然后把笔交回系统。
            synchronized(stateLock) {
                if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
                current = null
            }
            ink.view?.removeLastStroke()
            sessionStrokeCount++
            // ★ 必须 post：本回调在框架 `deliverStylusHandwritingMotionEvent` 里，
            //   它之后还有"把触摸还给界面"的收尾；同步收会话会让那一步空转，留下一层吃笔的 surface。
            mainHandler.post { runCatching { ime.finishStylusHandwriting() } }
            return
        }

        if (action == MotionEvent.ACTION_UP) sessionStrokeCount++

        // ---- 书写手势 ----
        if (action == MotionEvent.ACTION_UP && HookPrefs.stylusGestureEnabled()) {
            val stroke = justFinished
            if (stroke != null && looksLikeGesture(stroke)) {
                val g = gestureEngine.recognize(ime, stroke.map { PointF(it.x, it.y) })
                if (g != null && gestureShapeOk(g, stroke)) {
                    performGesture(ime, g)
                    prevStroke = stroke
                    return
                }
                if (g != null) {
                    L.i(
                        "event=stylus_gesture_shape_mismatch type=${g.javaClass.simpleName} " +
                            "pts=${stroke.size}",
                    )
                } else {
                    L.i("event=stylus_gesture_not_recognized points=${stroke.size}")
                }
            }
        }
        if (action == MotionEvent.ACTION_UP && justFinished != null) {
            prevStroke = justFinished
        }

        // ---- 系统笔引擎：停笔后延迟识别，连续落笔会把计时重置 ----
        if (action == MotionEvent.ACTION_UP) {
            val h = workerHandler()
            h.removeCallbacks(recognizeTask)
            h.postDelayed(recognizeTask, recognizeDelayMs())
        }
    }

    private fun tapSlop(ime: InputMethodService): Float =
        runCatching {
            android.view.ViewConfiguration.get(ime).scaledTouchSlop.toFloat()
        }.getOrDefault(FALLBACK_TAP_SLOP)

    // ------------------------------------------------------------------ 手势判据

    /**
     * 便宜的几何预筛：这一笔（配合上一笔）像不像手势。
     *
     * 只看包围盒、首尾距离、方向这些 O(n) 的量，不碰引擎。仅放行两类：
     * 闭合环（圈选）/ 长横线（划掉删除）。
     * 预筛偏宽松：假阳性只是白问一次模型，假阴性才是功能失效。
     */
    private fun looksLikeGesture(stroke: List<PencilEngine.Pt>): Boolean {
        if (stroke.size < 6) return false
        val g = Geometry(stroke)
        if (g.w < MIN_GESTURE_PX && g.h < MIN_GESTURE_PX) return false

        if (isLoopShape(g)) return true
        if (isStrikeShape(g)) return true
        return false
    }

    /**
     * 引擎给出的手势**再验一遍形状**：模型会认错（真机"横线被识别为圈选"就是它），
     * 而一次误判就直接改坏用户的文字，所以答案必须对得上形状。
     */
    private fun gestureShapeOk(
        gesture: HandwritingGesture,
        stroke: List<PencilEngine.Pt>,
    ): Boolean {
        val g = Geometry(stroke)
        return when (gesture) {
            is SelectGesture -> isLoopShape(g)
            is DeleteGesture -> isStrikeShape(g)
            else -> false
        }
    }

    private class Geometry(val stroke: List<PencilEngine.Pt>) {
        val first: PencilEngine.Pt = stroke.first()
        val last: PencilEngine.Pt = stroke.last()
        val minX: Float
        val maxX: Float
        val minY: Float
        val maxY: Float

        init {
            var loX = Float.MAX_VALUE
            var hiX = -Float.MAX_VALUE
            var loY = Float.MAX_VALUE
            var hiY = -Float.MAX_VALUE
            for (p in stroke) {
                if (p.x < loX) loX = p.x
                if (p.x > hiX) hiX = p.x
                if (p.y < loY) loY = p.y
                if (p.y > hiY) hiY = p.y
            }
            minX = loX
            maxX = hiX
            minY = loY
            maxY = hiY
        }

        val w: Float get() = maxX - minX
        val h: Float get() = maxY - minY
        val gap: Float
            get() = kotlin.math
                .hypot((last.x - first.x).toDouble(), (last.y - first.y).toDouble())
                .toFloat()
    }

    /** 闭合环（画框框）→ 圈选。要求像"框"而不是像「o / 0」。 */
    private fun isLoopShape(g: Geometry): Boolean {
        if (g.gap >= SELECT_GAP_RATIO * maxOf(g.w, g.h)) return false
        if (g.w <= MIN_GESTURE_PX || g.h <= MIN_GESTURE_PX) return false
        return frameLike(g)
    }

    private fun frameLike(g: Geometry): Boolean {
        val diag = kotlin.math.hypot(g.w.toDouble(), g.h.toDouble()).toFloat()
        if (diag <= 0f) return false
        val limit = FRAME_CORNER_RATIO * diag
        var near = 0
        for (cx in floatArrayOf(g.minX, g.maxX)) {
            for (cy in floatArrayOf(g.minY, g.maxY)) {
                var best = Float.MAX_VALUE
                for (p in g.stroke) {
                    val d = kotlin.math
                        .hypot((p.x - cx).toDouble(), (p.y - cy).toDouble())
                        .toFloat()
                    if (d < best) best = d
                    if (best <= limit) break
                }
                if (best <= limit) near++
            }
        }
        return near >= FRAME_CORNERS_MIN
    }

    private fun isStrikeShape(g: Geometry): Boolean =
        g.w > STRIKE_ASPECT * g.h && g.w > MIN_STRIKE_PX

    private fun isVerticalShape(g: Geometry): Boolean =
        g.h > SPLIT_ASPECT * g.w && g.h > MIN_SPLIT_PX && g.h < MAX_SPLIT_PX

    private fun isPolylineShape(stroke: List<PencilEngine.Pt>, g: Geometry): Boolean {
        if (stroke.size < MIN_NEWLINE_POINTS) return false
        val corner = cornerIndex(stroke, g.first, g.last)
        if (corner !in 1 until stroke.size - 1) return false
        val c = stroke[corner]
        val leg1x = c.x - g.first.x
        val leg1y = c.y - g.first.y
        val leg2x = g.last.x - c.x
        val leg2y = g.last.y - c.y
        val leg1 = kotlin.math.hypot(leg1x.toDouble(), leg1y.toDouble()).toFloat()
        val leg2 = kotlin.math.hypot(leg2x.toDouble(), leg2y.toDouble()).toFloat()
        if (leg1 <= MIN_NEWLINE_LEG_PX || leg2 <= MIN_NEWLINE_LEG_PX) return false
        val leg1Horizontal = kotlin.math.abs(leg1x) > kotlin.math.abs(leg1y)
        val leg2Horizontal = kotlin.math.abs(leg2x) > kotlin.math.abs(leg2y)
        return leg1Horizontal != leg2Horizontal
    }

    private fun isCaretShape(
        stroke: List<PencilEngine.Pt>,
        g: Geometry,
        prev: List<PencilEngine.Pt>?,
    ): Boolean {
        if (prev != null && prev.size >= 4) {
            val pFirst = prev.first()
            val pLast = prev.last()
            val join = kotlin.math
                .hypot((pLast.x - g.first.x).toDouble(), (pLast.y - g.first.y).toDouble())
                .toFloat()
            val prevUp = pLast.y < pFirst.y - MIN_CARET_LEG_PX
            val curDown = g.last.y > g.first.y + MIN_CARET_LEG_PX
            val wide = (maxOf(g.maxX, pLast.x) - minOf(g.minX, pFirst.x)) > MIN_CARET_PX
            if (join < CARET_JOIN_RATIO * maxOf(g.w, g.h) && prevUp && curDown && wide) return true
        }

        if (g.w > MIN_CARET_PX && g.h > MIN_CARET_LEG_PX && stroke.size >= 6) {
            var turnIdx = 0
            for (i in stroke.indices) if (stroke[i].y < stroke[turnIdx].y) turnIdx = i
            val apexY = stroke[turnIdx].y
            if (turnIdx in 1 until stroke.size - 1 &&
                g.first.y > apexY + MIN_CARET_LEG_PX && g.last.y > apexY + MIN_CARET_LEG_PX
            ) {
                return true
            }
            turnIdx = 0
            for (i in stroke.indices) if (stroke[i].y > stroke[turnIdx].y) turnIdx = i
            val bottomY = stroke[turnIdx].y
            if (turnIdx in 1 until stroke.size - 1 &&
                g.first.y < bottomY - MIN_CARET_LEG_PX && g.last.y < bottomY - MIN_CARET_LEG_PX
            ) {
                return true
            }
        }
        return false
    }

    /** 「单独画的一笔、落在已有文字之间」—— 连接·拆分这种改动型笔势的准入条件。 */
    private fun isMarkBetweenText(ime: InputMethodService): Boolean {
        if (idleBeforeStroke < MARK_IDLE_MS) return false
        return runCatching {
            val ic = ime.currentInputConnection ?: return@runCatching false
            ic.getTextBeforeCursor(1, 0)?.isNotEmpty() == true &&
                ic.getTextAfterCursor(1, 0)?.isNotEmpty() == true
        }.getOrDefault(false)
    }

    private fun cornerIndex(
        stroke: List<PencilEngine.Pt>,
        first: PencilEngine.Pt,
        last: PencilEngine.Pt,
    ): Int {
        val chordX = last.x - first.x
        val chordY = last.y - first.y
        val chord = kotlin.math.hypot(chordX.toDouble(), chordY.toDouble()).toFloat()
        if (chord < 1f) return -1
        var best = 0f
        var index = -1
        for (i in 1 until stroke.size - 1) {
            val d = kotlin.math.abs(
                chordY * (stroke[i].x - first.x) - chordX * (stroke[i].y - first.y),
            ) / chord
            if (d > best) {
                best = d
                index = i
            }
        }
        return index
    }

    private fun performGesture(ime: InputMethodService, gesture: HandwritingGesture) {
        synchronized(stateLock) {
            if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
            current = null
        }
        ink.view?.removeLastStroke()

        val ic = ime.currentInputConnection
        if (ic == null || !writeAllowed(ime)) {
            L.w("event=stylus_gesture_no_input_connection")
            return
        }
        val name = gesture.javaClass.simpleName
        runCatching {
            ic.performHandwritingGesture(
                gesture,
                Executor { it.run() },
                IntConsumer { result -> L.i("event=stylus_gesture_result type=$name result=$result") },
            )
        }.onFailure { L.e("event=stylus_gesture_perform_failed type=$name", it) }
        L.i("event=stylus_gesture_performed type=$name")
    }

    // ------------------------------------------------------------------ 识别

    private fun recognizeAndCommit() {
        val ime = sessionIme ?: return
        val snapshot = synchronized(stateLock) {
            if (strokes.isEmpty()) null else strokes.map { ArrayList(it) }
        } ?: return
        dispatchRecognition(ime, snapshot)
    }

    private fun dispatchRecognition(ime: InputMethodService, snapshot: List<List<PencilEngine.Pt>>) {
        workerHandler().post {
            val eng = ensureEngine(ime.applicationContext) ?: return@post
            val text = eng.recognize(normalize(snapshot))
            if (text.isNullOrEmpty()) {
                L.i("event=stylus_recognize_empty strokes=${snapshot.size}")
                mainHandler.post { finalizeIfNeeded(ime) }
                return@post
            }
            L.i("event=stylus_recognize_ok strokes=${snapshot.size} text=$text")
            showComposing(ime, text)
        }
    }

    /**
     * 组词态上屏 —— 这就是「自动优化文字」。
     *
     * 只 `setComposingText`、**不 commit**：中间结果（刚写完「女」）先显示，后面重认更好时整段替换
     *（「女」→「好」，「宀」+「豕」→「家」）。落定交给 [finishComposing]（会话结束时）。
     */
    private fun showComposing(ime: InputMethodService, text: String) {
        val finalize = finalizeAfterRecognize
        finalizeAfterRecognize = false
        mainHandler.post {
            if (!writeAllowed(ime)) return@post
            runCatching { ime.currentInputConnection?.setComposingText(text, 1) }
                .onFailure { L.w("event=stylus_composing_failed msg=${it.message}") }
            lastComposingText = text
            if (!strokeActive) ink.clear()
            if (finalize) finishComposing(ime)
        }
    }

    private fun finalizeIfNeeded(ime: InputMethodService) {
        if (!finalizeAfterRecognize) return
        finalizeAfterRecognize = false
        finishComposing(ime)
    }

    /**
     * 落定组词态：文字真正提交、下划线消失，并记进撤回历史。
     *
     * 落定前确认光标前那段还是我们写的：用户可能已经接着打拼音了，
     * 那时 `finishComposingText` 会把拼到一半的拼音截断。
     */
    private fun finishComposing(ime: InputMethodService) {
        val mine = lastComposingText
        lastComposingText = null
        if (!writeAllowed(ime)) return
        val ic = ime.currentInputConnection ?: return
        if (mine != null) {
            val before = runCatching { ic.getTextBeforeCursor(mine.length, 0) }
                .getOrNull()?.toString()
            if (before != mine) {
                L.i("event=stylus_finish_composing_skipped reason=not_ours")
                return
            }
        }
        runCatching { ic.finishComposingText() }
            .onFailure { L.w("event=stylus_finish_composing_failed msg=${it.message}") }
        if (!mine.isNullOrEmpty()) recordCommit(mine)
    }

    private fun recordCommit(text: String) {
        undoStack.addLast(text)
        while (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
        redoStack.clear()
        toolbar.setCanUndo(true)
        toolbar.setCanRedo(false)
    }

    /** 现在还能不能往这个输入框写（识别结果专用：必须是本次会话那个连接）。 */
    private fun writeAllowed(ime: InputMethodService): Boolean {
        val ic = ime.currentInputConnection ?: return false
        val mine = sessionIc
        if (mine == null || ic !== mine) {
            L.i("event=stylus_write_skipped reason=input_connection_changed")
            return false
        }
        return true
    }

    private fun normalize(strokes: List<List<PencilEngine.Pt>>): List<List<PencilEngine.Pt>> {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        for (stroke in strokes) {
            for (p in stroke) {
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
            }
        }
        if (minX == Float.MAX_VALUE || (minX == 0f && minY == 0f)) return strokes
        return strokes.map { stroke ->
            stroke.map { PencilEngine.Pt(it.x - minX, it.y - minY, it.action, it.time) }
        }
    }

    private fun recognizeDelayMs(): Long =
        HookPrefs.stylusDelayMs().toLong().coerceIn(50L, 5000L)

    /** 搜狗固定为 500ms；会话结束时 [endSession] 会立刻补做尚未触发的识别。 */
    private fun sessionTimeoutMs(): Long = STYLUS_SESSION_TIMEOUT_MS

    // ------------------------------------------------------------------ 会话 / 模式

    private fun beginSession(ime: InputMethodService) {
        // ★ 这个函数在 **IMMS 的手势窗口**里被回调，必须尽快返回：
        //   `AFTER_STYLUS_UP_ALLOW_PERIOD_MS` 只有 200ms，返回晚了会话建不起来，
        //   还会留下一层吃笔的 surface。所以这里只做轻活，建面板一律 post。
        val t0 = System.nanoTime()
        lifecycleGuard.recordStylusActivity(SystemClock.uptimeMillis())
        val freshSession = !sessionActive
        sessionActive = true
        sessionIme = ime
        imeInstance = ime
        sessionIc = ime.currentInputConnection
        if (freshSession) {
            lastComposingText = null
            finalizeAfterRecognize = false
            synchronized(stateLock) {
                strokes.clear()
                current = null
            }
            sessionStrokeCount = 0
            strokeActive = false
            ink.clear()
        } else {
            L.i("event=stylus_session_begin_duplicate preserve_ink=true")
        }

        // 偏好刷新与引擎配置放后台（读 RemotePreferences 是 binder，不能占这个窗口）
        workerHandler().post {
            runCatching { HookPrefs.rebind(module) }
                .onFailure { L.w("event=stylus_prefs_rebind_failed msg=${it.message}") }
        }

        runCatching {
            val idle = sessionTimeoutMs()
            ime.setStylusHandwritingSessionTimeout(java.time.Duration.ofMillis(idle))
            L.i("event=stylus_session_timeout idle=${idle}ms")
        }.onFailure { L.w("event=stylus_timeout_failed msg=${it.message}") }

        // ★ 这里**不** requestHideSelf：那会让 IME 窗口的 token 当场作废，
        //   挂在上面的面板变成画得出来但点不动的僵尸。键盘由面板盖住（INVISIBLE），
        //   IME 窗口继续作为宿主；随手写状态下由 onComputeInsets 上报零占用高度。
        if (HookPrefs.stylusInkEnabled()) {
            runCatching { ink.attach(ime) }
                .onFailure { L.e("event=stylus_ink_attach_exception", it) }
        } else {
            ink.clear()
        }

        val costMs = (System.nanoTime() - t0) / 1_000_000
        L.i("event=stylus_session_begin ink=${ink.isAttached} cost=${costMs}ms")

        // 搜狗也是异步：`showToolboxAsync` 里 `sendMessageDelayed(1, 200)` ——
        // 等会话彻底起来再建视图，别在 200ms 窗口里 inflate。
        mainHandler.postDelayed(
            { runCatching { enterStylusMode(ime) }.onFailure { L.e("event=stylus_mode_enter_failed", it) } },
            TOOLBAR_SHOW_DELAY_MS,
        )
    }

    /**
     * 悬浮小键盘的宿主。
     *
     * 悬浮键盘不是 IME decor 的子 View —— 它是 `FloatingKeyboardManager` 里的独立
     * `PopupWindow`（窗口类型 `TYPE_INPUT_METHOD`，token 复用输入法窗口），
     * 所以 [StylusToolbar.hideKeyboardContent] 遍历 decor 根本碰不到它。
     * 输入法自己提供了一个 `applyFloatingPanelContentHidden(boolean)`：
     * 内容 INVISIBLE + 弹窗窗口 `FLAG_NOT_TOUCHABLE` + 重新 layout，
     * 而且**不改**它内部的悬浮状态，退出随手写后按原样恢复。
     *
     * 这里记下"被我们藏过"的实例，保证每条退出路径都还回去。
     */
    @Volatile
    private var floatingKeyboardHiddenFor: InputMethodService? = null

    private val floatingContentHiddenMethod: java.lang.reflect.Method? by lazy {
        val cls = imeClass ?: return@lazy null
        cls.methods.firstOrNull {
            it.name.startsWith("applyFloatingPanelContentHidden") &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }.also {
            if (it == null) {
                // 输入法版本改名时只降级：悬浮小键盘在随手写期间会留着，其它状态不受影响。
                L.w("event=stylus_floating_hidden_missing class=${cls.name}")
            }
        }
    }

    private fun setFloatingKeyboardHidden(ime: InputMethodService, hidden: Boolean) {
        if (hidden) {
            if (floatingKeyboardHiddenFor === ime) return
            val previous = floatingKeyboardHiddenFor
            floatingKeyboardHiddenFor = ime
            if (previous != null && previous !== ime) invokeFloatingHidden(previous, false)
        } else {
            val target = floatingKeyboardHiddenFor ?: return
            floatingKeyboardHiddenFor = null
            if (target !== ime) invokeFloatingHidden(target, false)
        }
        invokeFloatingHidden(ime, hidden)
    }

    private fun invokeFloatingHidden(ime: InputMethodService, hidden: Boolean) {
        val m = floatingContentHiddenMethod ?: return
        runCatching { m.invoke(ime, hidden) }
            .onSuccess { L.i("event=stylus_floating_keyboard_hidden hidden=$hidden") }
            .onFailure {
                L.w("event=stylus_floating_keyboard_hidden_failed hidden=$hidden msg=${it.message}")
            }
    }

    /** 退出随手写时把悬浮小键盘还回可见 / 可触摸（每条退出路径都要走）。 */
    private fun restoreFloatingKeyboard() {
        val ime = floatingKeyboardHiddenFor ?: return
        setFloatingKeyboardHidden(ime, false)
    }

    /**
     * 进随手写模式：把面板贴到输入法窗口上，并藏起键盘内容。
     *
     * 输入法窗口如果在笔落笔前**根本没显示**（笔直接点了一个没聚焦的输入框），
     * 面板就没有宿主；这时按搜狗的做法请一次 `requestShowSelf`。
     *
     * ⚠ 面板是"零高度键盘 + 悬浮面板"的唯一可见物：一旦 `stylusMode` 置位而面板
     * 没挂上（没有 decor / 不在窗口上 / addView 失败 / 悬浮键盘模式下宿主不可用），
     * 用户就会既看不到面板也看不到键盘。所以这里**必须**在 `show()` 之后复核，
     * 失败就回滚状态，绝不允许 `stylusMode == true && !toolbar.isShowing` 长期存在。
     */
    private fun enterStylusMode(ime: InputMethodService) {
        if (!HookPrefs.stylusEnabled(module)) return
        if (!HookPrefs.stylusToolbarEnabled()) {
            if (stylusMode || toolbar.isShowing) exitStylusMode("toolbar_disabled")
            return
        }
        // 先置状态再挂面板：show() 里触发的 insets / 布局回调应当已经看到随手写态。
        // 后面任何一步失败都走 rollback。
        machine.enterStylus()
        attachPanel(ime)
        if (toolbar.isShowing) {
            finishEnterStylusMode(ime)
            return
        }
        // 输入法窗口还没显示（笔直接点了未聚焦输入框）：按搜狗的做法请一次 showSelf，
        // 再给一帧时间重试；重试仍挂不上才回滚，避免"零高度键盘 + 无面板"的死状态。
        if (!ime.isInputViewShown) {
            runCatching { ime.requestShowSelf(0) }
                .onFailure { L.w("event=stylus_show_self_failed msg=${it.message}") }
            val ticket = machine.ticket()
            mainHandler.postDelayed(
                {
                    if (machine.isStale(ticket) ||
                        machine.mode != StylusModeMachine.Mode.STYLUS
                    ) {
                        return@postDelayed
                    }
                    attachPanel(ime)
                    if (toolbar.isShowing) {
                        finishEnterStylusMode(ime)
                    } else {
                        rollbackStylusMode(ime, "panel_not_attached_after_show_self")
                    }
                },
                TOOLBAR_SHOW_DELAY_MS,
            )
            return
        }
        L.w("event=stylus_show_self_skipped reason=input_view_shown_but_no_decor")
        rollbackStylusMode(ime, "panel_not_attached")
    }

    private fun attachPanel(ime: InputMethodService) {
        val info = runCatching { ime.currentInputEditorInfo }.getOrNull()
        runCatching { toolbar.show(ime, info) }
            .onFailure { L.e("event=stylus_toolbar_show_failed", it) }
    }

    private fun finishEnterStylusMode(ime: InputMethodService) {
        toolbar.setCanUndo(undoStack.isNotEmpty())
        toolbar.setCanRedo(redoStack.isNotEmpty())
        // 悬浮小键盘是独立窗口，只能请输入法自己把它藏掉（见 setFloatingKeyboardHidden）。
        setFloatingKeyboardHidden(ime, true)
        if (!ime.isInputViewShown) {
            runCatching { ime.requestShowSelf(0) }
                .onFailure { L.w("event=stylus_show_self_failed msg=${it.message}") }
        }
        L.i("event=stylus_mode_enter toolbar=${toolbar.isShowing} mode=${machine.mode}")
    }

    /**
     * 面板没挂上时的回滚：状态退回「已连接键盘 / 屏幕键盘」，半挂的面板与窗口标记一并清掉。
     */
    private fun rollbackStylusMode(ime: InputMethodService?, reason: String) {
        L.w("event=stylus_mode_rollback reason=$reason")
        machine.exitStylus(ime != null && hasPhysicalKeyboard(ime))
        runCatching { toolbar.hide(forceImeTouchable = true) }
        ink.clear()
        lastEditorToolType = MotionEvent.TOOL_TYPE_UNKNOWN
        lifecycleGuard.reset()
        restoreFloatingKeyboard()
    }

    private fun exitStylusMode(
        reason: String,
        forceImeTouchable: Boolean = false,
        finishSession: Boolean = false,
    ) {
        val wasStylus = stylusMode
        val hadPanel = toolbar.isShowing
        val hadSession = sessionActive
        // 只要是离开随手写，悬浮小键盘就必须还回去（面板没挂上的半挂状态也一样）。
        restoreFloatingKeyboard()
        lifecycleGuard.reset()
        // 会话状态必须在退出时一起归零：框架不保证每个退出路径都会回调
        // onFinishStylusHandwriting，残留的 sessionActive 会让下一次 beginSession
        // 误判成"重复 start"而保留上一轮的笔迹。
        sessionActive = false
        if (!wasStylus && !hadPanel) {
            lastEditorToolType = MotionEvent.TOOL_TYPE_UNKNOWN
            if (finishSession && hadSession) scheduleSessionFinish(reason)
            return
        }
        machine.exitStylus(restingHardwareKeyboard())
        // 物理键盘输入 / 工具栏键切回后，后续 onStartInputView 不能沿用上一次 STYLUS
        // 再把手写面板贴回来；下一次真实落笔会重新上报 STYLUS。
        lastEditorToolType = MotionEvent.TOOL_TYPE_UNKNOWN
        runCatching { toolbar.hide(forceImeTouchable = forceImeTouchable) }
        ink.clear()
        if (finishSession && hadSession) scheduleSessionFinish(reason)
        L.i("event=stylus_mode_exit reason=$reason mode=${machine.mode}")
    }

    /** 退出随手写后落在哪一态：看此刻有没有实体键盘，而不是进入前的状态。 */
    private fun restingHardwareKeyboard(): Boolean {
        val ime = sessionIme ?: imeInstance ?: return false
        return hasPhysicalKeyboard(ime)
    }

    /**
     * 结束框架的随手写会话。
     *
     * ★ 一律 `post`：调用点可能正处在框架的 `deliverStylusHandwritingMotionEvent` 栈里
     *（工具栏「键盘」键就是），同步收会话会让框架那一步"把笔还给界面"的收尾空转，
     * 留下一层吃笔的 surface —— 见 `onStylusMotion` 里轻点判定的同一条结论。
     */
    private fun scheduleSessionFinish(reason: String) {
        val ime = sessionIme ?: imeInstance ?: return
        mainHandler.post {
            runCatching { ime.finishStylusHandwriting() }
                .onFailure {
                    L.w("event=stylus_finish_session_failed reason=$reason msg=${it.message}")
                }
        }
    }

    private fun endSession(ime: InputMethodService) {
        sessionActive = false
        // ★ 会话结束 = 用户写完了。必须**当场清账**：把还攒着、还没到延迟的笔迹立刻识别掉，
        //   再落定组词态。绝不能把写入推迟到会话之外 —— 那时 currentInputConnection 可能已经换人。
        val snapshot = synchronized(stateLock) {
            if (strokes.isEmpty()) null else strokes.map { ArrayList(it) }
        }
        val h = workerHandler()
        h.removeCallbacks(recognizeTask)
        if (snapshot != null) {
            finalizeAfterRecognize = true
            h.post { dispatchRecognition(ime, snapshot) }
        } else {
            finishComposing(ime)
        }
        synchronized(stateLock) {
            strokes.clear()
            current = null
        }
        strokeActive = false
        sessionStrokeCount = 0
        prevStroke = null
        ink.clear()
        L.i("event=stylus_session_end mode=$stylusMode")
    }

    // ------------------------------------------------------------------ 面板按钮

    /**
     * 面板按钮 —— 语义对齐搜狗 `MiuiHandWritingIMESylus.onImeMenuClickListenerClicked`：
     * 空格 `commitText(" ")`，退格发 `KEYCODE_DEL`，回车按 `imeOptions` 走
     * `performEditorAction`、没有 action 就发 `KEYCODE_ENTER`，键盘键切回普通键盘。
     *
     * 撤/重做搜狗是发 `KEYCODE_UNDO/REDO`（Ctrl+Z/Y），大量应用不接；
     * 这里改成自己的历史（见 [undoStack]），对不上再退回原生键。
     */
    private fun onToolbarAction(action: StylusToolbar.Action) {
        val ime = sessionIme ?: imeInstance ?: return
        if (action == StylusToolbar.Action.KEYBOARD) {
            // 这是从系统手写会话显式切回软键盘，不只是把自定义面板藏起来：
            // 1. 结束 framework 会话，归还被 pilfer 的笔事件；
            // 2. 不恢复进入手写时记录到的 NOT_TOUCHABLE，否则键盘虽然显示却无法点击。
            //
            // ★ 收会话**不能同步**：本回调来自 onStylusHandwritingMotionEvent →
            //   dispatchPen → onPointer，同步 finishStylusHandwriting 会让框架的
            //   "把笔还给界面"那一步空转，留下一层吃笔的 surface（同 onStylusMotion 里
            //   轻点判定的注释）。exitStylusMode 内部负责把 finish 排到主线程队列。
            exitStylusMode(
                "keyboard_button",
                forceImeTouchable = true,
                finishSession = true,
            )
            return
        }
        if (action == StylusToolbar.Action.SETTINGS) {
            openStylusSettings(ime)
            return
        }
        val ic = ime.currentInputConnection
        if (ic == null) {
            L.w("event=stylus_toolbar_no_ic action=$action")
            return
        }
        when (action) {
            StylusToolbar.Action.SPACE -> {
                finishComposingQuietly(ic)
                val ok = runCatching { ic.commitText(" ", 1) }.getOrDefault(false)
                if (!ok) sendKey(ic, KeyEvent.KEYCODE_SPACE)
                L.i("event=stylus_toolbar_space commit=$ok")
            }

            StylusToolbar.Action.BACKSPACE -> {
                finishComposingQuietly(ic)
                // 搜狗就是这条：DEL 的 down/up，带软键盘标志（否则部分编辑器不认）
                sendKey(ic, KeyEvent.KEYCODE_DEL)
            }

            StylusToolbar.Action.UNDO -> doUndo(ime, ic)

            StylusToolbar.Action.REDO -> doRedo(ime, ic)

            StylusToolbar.Action.SEND -> {
                finishComposingQuietly(ic)
                val info = runCatching { ime.currentInputEditorInfo }.getOrNull()
                val editorAction = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                if (editorAction != EditorInfo.IME_ACTION_NONE && editorAction != 0) {
                    L.i("event=stylus_toolbar_send editor_action=$editorAction")
                    runCatching { ic.performEditorAction(editorAction) }
                } else {
                    L.i("event=stylus_toolbar_send enter")
                    sendKey(ic, KeyEvent.KEYCODE_ENTER)
                }
            }

            else -> Unit
        }
    }

    private fun doUndo(ime: InputMethodService, ic: InputConnection) {
        finishComposingQuietly(ic)
        val last = undoStack.removeLastOrNull()
        if (last == null) {
            // 没有自己的历史：退回编辑器原生 undo（搜狗的做法）
            // 搜狗原实现 getKeyEvent(54) + META_CTRL_ON —— 就是 Ctrl+Z（54 = KEYCODE_Z）
            sendKey(ic, KeyEvent.KEYCODE_Z, meta = KeyEvent.META_CTRL_ON)
            L.i("event=stylus_toolbar_undo native")
            return
        }
        val before = runCatching { ic.getTextBeforeCursor(last.length, 0) }
            .getOrNull()?.toString()
        if (before != last) {
            // 光标前已经不是我们写的那段（用户中间改过）：不做破坏性删除
            undoStack.addLast(last)
            // 搜狗原实现 getKeyEvent(54) + META_CTRL_ON —— 就是 Ctrl+Z（54 = KEYCODE_Z）
            sendKey(ic, KeyEvent.KEYCODE_Z, meta = KeyEvent.META_CTRL_ON)
            L.i("event=stylus_toolbar_undo mismatch native")
            return
        }
        val ok = runCatching { ic.deleteSurroundingTextInCodePoints(last.length, 0) }
            .getOrDefault(false)
        if (!ok) runCatching { ic.deleteSurroundingText(last.length, 0) }
        redoStack.addLast(last)
        toolbar.setCanUndo(undoStack.isNotEmpty())
        toolbar.setCanRedo(true)
        L.i("event=stylus_toolbar_undo len=${last.length} ok=$ok")
    }

    private fun doRedo(ime: InputMethodService, ic: InputConnection) {
        val text = redoStack.removeLastOrNull()
        if (text == null) {
            // 搜狗原实现 getKeyEvent(53) + META_CTRL_ON —— 就是 Ctrl+Y（53 = KEYCODE_Y）
            sendKey(ic, KeyEvent.KEYCODE_Y, meta = KeyEvent.META_CTRL_ON)
            L.i("event=stylus_toolbar_redo native")
            return
        }
        finishComposingQuietly(ic)
        runCatching { ic.commitText(text, 1) }
        undoStack.addLast(text)
        toolbar.setCanUndo(true)
        toolbar.setCanRedo(redoStack.isNotEmpty())
        L.i("event=stylus_toolbar_redo len=${text.length}")
    }

    /** 落定组词态但不记历史（发键/删字前先收掉半截组词）。 */
    private fun finishComposingQuietly(ic: InputConnection) {
        if (lastComposingText == null) return
        lastComposingText = null
        runCatching { ic.finishComposingText() }
    }

    private fun sendKey(ic: InputConnection, code: Int, meta: Int = 0) {
        val now = SystemClock.uptimeMillis()
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        runCatching {
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta, -1, 0, flags))
            ic.sendKeyEvent(
                KeyEvent(
                    now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, meta, -1, 0, flags,
                ),
            )
        }.onFailure { L.w("event=stylus_key_failed code=$code msg=${it.message}") }
    }

    /** 「设置」键：搜狗打开的是 MIUI 手写设置页；这台机器没有那个 Activity，就开模块自己的随手写页。 */
    private fun openStylusSettings(ime: InputMethodService) {
        val miui = Intent().setComponent(
            android.content.ComponentName(
                "com.miui.miinput",
                "com.miui.miinput.stylus.KeyBoardAndStylusSettingsActivity",
            ),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val own = Intent().setComponent(
            android.content.ComponentName(
                "io.github.skyshadowhero.fancypad",
                "io.github.skyshadowhero.fancypad.SettingsActivity",
            ),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ctx = ime.applicationContext
        val ok = runCatching { ctx.startActivity(miui); true }.getOrDefault(false)
        if (!ok) {
            runCatching { ctx.startActivity(own) }
                .onFailure {
                    if (it !is android.content.ActivityNotFoundException) {
                        L.w("event=stylus_settings_failed msg=${it.message}")
                    }
                }
        }
        L.i("event=stylus_toolbar_settings miui=$ok")
    }

    // ------------------------------------------------------------------ 工具

    private fun isTarget(obj: Any?): Boolean {
        val cls = imeClass ?: return false
        return obj != null && cls.isInstance(obj)
    }

    private fun allowsStylusUi(ime: InputMethodService, info: EditorInfo?): Boolean {
        if (info == null || runCatching { ime.currentInputConnection }.getOrNull() == null) return false
        val classAndVariation = info.inputType and
            (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
        val password = classAndVariation ==
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) ||
            classAndVariation ==
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
            classAndVariation ==
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) ||
            classAndVariation ==
            (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        if (password) return false
        return info.extras?.getBoolean(EXTRA_HANDWRITING_SUPPORTED, true) ?: true
    }

    private fun workerHandler(): Handler {
        workerHandler?.let { return it }
        synchronized(this) {
            workerHandler?.let { return it }
            val t = HandlerThread("fancypad-stylus", Process.THREAD_PRIORITY_BACKGROUND)
            t.start()
            val h = Handler(t.looper)
            worker = t
            workerHandler = h
            L.i("event=stylus_worker_started")
            return h
        }
    }

    /**
     * 懒开系统笔引擎：构造会把 17MB 的 `ocr_model.tflite` 吃进 native，
     * 每次会话重建太贵；进程常驻可接受。
     */
    private fun ensureEngine(context: android.content.Context): PencilEngine? {
        engine?.let { return it }
        synchronized(engineLock) {
            if (!engineTried) {
                engineTried = true
                engine = PencilEngine.open(context)
            }
        }
        return engine
    }

    private companion object {
        const val CLS_MI_IME = "com.mi.ime.MiInputMethodService"
        /** Compose PopupLayout；0.2.1448 上悬浮键盘工具栏的独立窗口宿主。 */
        const val CLS_COMPOSE_POPUP = "w2.p"
        const val EXTRA_HANDWRITING_SUPPORTED =
            "com.miui.inputmethod.EditorInfo.HANDWRITING_SUPPORTED"

        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2

        const val FALLBACK_TAP_SLOP = 20f

        /** 判成"轻点"还要求落笔前已经停了这么久（写字途中的蜻蜓点水不算）。 */
        const val TAP_IDLE_MS = 300L

        /** 面板延迟多久出现（搜狗 `showToolboxAsync` 也是 200ms）。 */
        const val TOOLBAR_SHOW_DELAY_MS = 200L

        /** 给瞬时 `onFinishInput` 对应的 `onStartInput` 留出恢复时间。 */
        const val LIFECYCLE_CONFIRM_DELAY_MS = 300L

        /**
         * 实体键盘探测结果的缓存时长。
         *
         * 枚举输入设备是 binder 调用，而落笔路径每次都要问一次；设备增删与配置变化
         * 会立即作废缓存，所以这里的 TTL 只影响"没有事件提醒"的极端情况。
         */
        const val PHYSICAL_KEYBOARD_TTL_MS = 500L

        /** 搜狗在初始化随手写服务时设置的固定会话空闲超时。 */
        const val STYLUS_SESSION_TIMEOUT_MS = 500L

        /** 撤回历史最多记多少段。 */
        const val UNDO_LIMIT = 32

        const val MIN_GESTURE_PX = 72f
        const val SELECT_GAP_RATIO = 0.40f
        const val FRAME_CORNER_RATIO = 0.10f
        const val FRAME_CORNERS_MIN = 3
        const val MIN_STRIKE_PX = 90f
        const val STRIKE_ASPECT = 1.5f
        const val MIN_SPLIT_PX = 70f
        const val MAX_SPLIT_PX = MIN_SPLIT_PX * 3f
        const val SPLIT_ASPECT = 1.4f
        const val MARK_IDLE_MS = 600L
        const val MIN_NEWLINE_POINTS = 8
        const val MIN_NEWLINE_LEG_PX = 40f
        const val MIN_CARET_PX = 90f
        const val MIN_CARET_LEG_PX = 36f
        const val CARET_JOIN_RATIO = 0.6f
    }
}
