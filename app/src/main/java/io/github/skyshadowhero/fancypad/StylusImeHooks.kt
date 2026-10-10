package io.github.skyshadowhero.fancypad

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.graphics.PointF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.DeleteGesture
import android.view.inputmethod.HandwritingGesture
import android.view.inputmethod.InsertGesture
import android.view.inputmethod.InsertModeGesture
import android.view.inputmethod.JoinOrSplitGesture
import android.view.inputmethod.SelectGesture
import java.util.concurrent.Executor
import java.util.function.IntConsumer
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.File
import java.lang.reflect.Method

/**
 * FancyPad · 随手写 —— **输入法进程侧**（作用域 `com.xiaomi.type`）。
 *
 * 这是链路里最后一道闸，也是最硬的一道：AOSP 的
 * `InputMethodService.onStartStylusHandwriting()` **默认返回 false**，而
 * `MiInputMethodService` 完全没有实现它（DEX 级复核：8683+502 个类里 0 个声明这四个回调）。
 * 系统走到这里会打 `"IME is not ready. Can't start Stylus Handwriting"` 然后放弃。
 *
 * 补齐四个回调（都挂在**框架类** `android.inputmethodservice.InputMethodService` 上，
 * 所以作用域只需 `com.xiaomi.type`，不碰别的输入法）：
 *
 * | 回调 | 处理 |
 * |---|---|
 * | `onStartStylusHandwriting()` | 返回 true —— 会话才开得起来；顺带挂笔迹画布、收起键盘 |
 * | `onPrepareStylusHandwriting()` | 只记会话目标（**不**做初始化，见下面的"清两次"坑） |
 * | `onStylusHandwritingMotionEvent(MotionEvent)` | 画笔迹 + 攒笔迹识别；**仍然放行原实现** |
 * | `onFinishStylusHandwriting()` | 收尾 |
 *
 * ## 墨迹画在哪
 *
 * 画布挂在**框架自己的手写窗口** `getStylusHandwritingWindow()` 里（见 [StylusInkOverlay]），
 * 尺寸是**整屏**（屏幕宽 × 屏幕高），写哪儿都有笔迹。
 *
 * 早先那版往这个窗口里塞的是一条满宽横带，真机发现窗口是 `Requested w=1497 h=1084`、
 * `frame=[851,526][2348,1610]`（屏幕 3200×2136），其实是"**窗口按内容 wrap**"的结果：
 * 那条 1497 就是当时塞进去的 `MATCH_PARENT` 带子被量出来的宽度。
 * 而当时记下的事实是：第一次落笔在 `raw=(630,230)`、区域是
 * `[851,526][2348,1610]` —— **笔在区域外，事件照样收得到**。
 * 所以"只有中间一片有笔迹"纯粹是画布被自己那条带子裁掉了，不是系统不给事件。
 * 现在：尺寸由我们显式给**整屏**，没有带子，也不需要位置跟随。
 *
 * ## 点击 vs 画线（以及"会话"为什么必须短命）
 *
 * 规则：**点击就是点击，只有画线才是写字**。判据与框架自己对齐 —— 位移不超过系统 touchSlop
 * 的手势，在 framework 的 `HandwritingInitiator` 眼里本来就不算画线，所以也不会开手写会话。
 *
 * 真正会出事的是**会话活得太久**：会话期间 system_server 的 `HandwritingModeController`
 * 会 `pilferPointers` + `startIntercepting`，把笔的事件从宿主应用手里抢给输入法，
 * 于是用户点什么都没反应（表现为"有一层透明遮罩，退不出书写模式"）。
 * 所以这里把会话空闲超时设成**秒级**（[beginSession]），并在字上屏后主动收会话；
 * 一停笔就把笔交回系统，之后"点一下"自然落到界面上、只有画线才会再开新会话。
 *
 * ## 识别两条路
 *
 * 1. **小爱自带的讯飞 HCR**（`v4.x.f` → `r9.j.y(x,y,action)`，action 语义与
 *    `MotionEvent.getActionMasked()` 完全一致；`v4.x.e` 抬手取候选并上屏）。
 *    只有小爱**当前就是手写引擎**时才通（`v4.x.g(ime)` 非空）。
 * 2. **系统笔引擎 [PencilEngine]**（`/system_ext/framework/xiaomi-pencilengine-pad.jar`
 *    + 本地 `/system_ext/etc/ocr_model.tflite`）。已真机验证能出字，不依赖小爱的混淆类名。
 *
 * 两条都失败时只记日志，绝不抛给输入法 —— 输入法崩溃会直接丢掉用户的输入焦点。
 */
class StylusImeHooks(private val module: XposedModule) {

    @Volatile
    private var installed = false

    /** 目标输入法类 `com.mi.ime.MiInputMethodService`，用于 instanceof 守卫。 */
    @Volatile
    private var imeClass: Class<*>? = null

    // ---- 小爱自带讯飞 HCR 的转发入口（版本相关的混淆名，取不到就退化为 PencilEngine）----
    @Volatile private var hcrFeed: Method? = null   // v4.x.f(MiInputMethodService, int, int, int)
    @Volatile private var hcrLift: Method? = null   // v4.x.e(MiInputMethodService)
    @Volatile private var hcrClear: Method? = null  // v4.x.c(MiInputMethodService)
    @Volatile private var hcrEngine: Method? = null // v4.x.g(MiInputMethodService) -> r9.j?

    // ---- 会话状态 ----
    private val stateLock = Any()
    private val strokes = ArrayList<List<PencilEngine.Pt>>()
    private var current: ArrayList<PencilEngine.Pt>? = null

    @Volatile private var sessionIme: InputMethodService? = null

    /** 本次会话是否走小爱自己的 HCR（走它就别再走 PencilEngine，否则重复出字）。 */
    @Volatile private var usingHcr = false

    /**
     * 本会话第几笔（含轻点）。**只用于诊断统计**。
     *
     * 注意：**不能**拿它当"这一笔是不是轻点"的判据 —— 试过，后果是写完字之后再点一下
     * 就又不算轻点了，于是"点一下"被当写字送去识别（用户明确要求：点击就是点击，
     * 只有画线才是写字）。判定只看这一笔自己的几何。
     */
    @Volatile private var sessionStrokeCount = 0

    /** 当前是否有笔画进行中（DOWN 之后、UP/CANCEL 之前）。用于避免清墨迹清掉正在写的一笔。 */
    @Volatile private var strokeActive = false

    // ---- 笔迹显示（纯附加能力：挂不上只是没有墨迹，识别照常）----
    private val ink = StylusInkOverlay()

    // ---- 轻点判定（笔尖轻点 = 点击，不是写字）----
    @Volatile private var downTime = 0L
    @Volatile private var downX = 0f
    @Volatile private var downY = 0f

    /** 这一笔里离落笔点最远的距离（px，欧氏）。 */
    @Volatile private var maxDist = 0f

    /** 这一笔收到的 MOVE 事件数（轻点通常是 0）。 */
    @Volatile private var moveEvents = 0

    // ---- 书写手势 ----
    /** 手势识别门面（懒建：只有真的出现"像手势"的笔画才会构造，见 [StylusGestureEngine]）。 */
    private val gestureEngine = StylusGestureEngine()

    /** 上一笔的点集：判断"尖尖插入"那种两笔相接的形状要用。 */
    @Volatile private var prevStroke: List<PencilEngine.Pt>? = null

    /** 上一笔抬起的时刻（事件时间）。判断这一笔是不是"单独画的"要用（见 [looksLikeGesture]）。 */
    @Volatile private var lastStrokeUpTime = 0L

    /**
     * 因为**轻点**而立刻收会话时，把这批还没到识别延迟的笔迹带走（见 [carryStrokes]）。
     *
     * [endSession] 会清空 [strokes]，而这个字段跨会话保留；[recognizeAndCommit] 优先取它。
     */
    @Volatile
    private var carriedStrokes: List<List<PencilEngine.Pt>>? = null

    /** 本次落笔距上一笔抬起过了多久（ms）；第一笔、或隔了很久没写时是 [Long.MAX_VALUE]。 */
    @Volatile private var idleBeforeStroke = Long.MAX_VALUE

    // ---- 识别后端：优先讯飞 HCR（小爱自带），失败自动退回系统笔引擎 ----
    private val iflytek = IflytekHcrEngine()

    /**
     * 讯飞引擎用的**会话级**笔画集合。
     *
     * 与 [strokes] 的区别：`strokes` 每次识别就被取空（系统笔引擎是"攒一批识别一次"），
     * 而讯飞那边每个识别周期都要把**到目前为止的整段字迹**重新归一化后重喂一遍
     *（因为它按写区域归一化，只喂增量的话第一个字的位置就错了），所以这里不清空，
     * 直到"落字"或会话结束。
     */
    private val iflytekStrokes = ArrayList<List<PencilEngine.Pt>>()


    // ---- 后台识别线程（懒建：没有笔事件就不起线程）----
    @Volatile private var worker: HandlerThread? = null
    @Volatile private var workerHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- 系统笔引擎（懒开：首次识别时才加载 17MB 模型）----
    private val engineLock = Any()
    @Volatile private var engine: PencilEngine? = null
    @Volatile private var engineTried = false

    /** 停笔延迟到点后触发的识别任务；连续落笔会把它重置。 */
    private val recognizeTask = Runnable { recognizeAndCommit() }

    fun install(cl: ClassLoader) {
        if (installed) return
        installed = true
        imeClass = runCatching { cl.loadClass(CLS_MI_IME) }.getOrNull()
        if (imeClass == null) {
            L.w("event=stylus_ime_class_missing（随手写只在输入法进程生效）")
        }
        resolveHcr(cl)
        hookStart()
        hookPrepare()
        hookMotion()
        hookFinish()
    }

    // ------------------------------------------------------------------ 反射

    /**
     * 解析小爱自带的讯飞 HCR 入口。
     *
     * 这些是**按版本混淆**的名字（`v4.x` / `r9.j`），输入法每次发版都可能变；
     * 取不到不是错误 —— 直接用系统笔引擎识别即可，所以这里只记日志、不抛。
     */
    private fun resolveHcr(cl: ClassLoader) {
        try {
            val ime = imeClass ?: return
            val v4x = cl.loadClass("v4.x")
            hcrFeed = v4x.getDeclaredMethod(
                "f", ime,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            hcrLift = v4x.getDeclaredMethod("e", ime)
            hcrClear = v4x.getDeclaredMethod("c", ime)
            hcrEngine = v4x.getDeclaredMethod("g", ime)
            L.i("event=stylus_hcr_refs_ok")
        } catch (t: Throwable) {
            hcrFeed = null; hcrLift = null; hcrClear = null; hcrEngine = null
            L.w("event=stylus_hcr_refs_failed msg=${t.message}（改用系统笔引擎识别）")
        }
    }

    // ------------------------------------------------------------------ 四个回调

    private fun hookStart() {
        try {
            val m = InputMethodService::class.java.getDeclaredMethod("onStartStylusHandwriting")
            module.hook(m)
                .setId("stylus_ime_on_start")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    if (!isTarget(chain.thisObject) || !HookPrefs.stylusEnabled(module)) {
                        chain.proceed()          // 不是小爱 / 开关关掉：保持 AOSP 默认（false）
                    } else {
                        beginSession(chain.thisObject as InputMethodService)
                        true                     // ★ 会话在这里被放行
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
                    // 只记会话目标，真正的初始化放在 onStartStylusHandwriting。
                    // AOSP 的顺序是 onPrepare → onStart：两边都初始化会**清两次墨迹**，
                    // 第二次会把用户刚写下的笔迹抹掉（真机"笔迹会丢失"的来源之一）。
                    if (isTarget(chain.thisObject) && HookPrefs.stylusEnabled(module)) {
                        sessionIme = chain.thisObject as InputMethodService
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
                    // 照常放行基类实现（此刻没多大用了，但保持框架语义）
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

    // ------------------------------------------------------------------ 笔迹

    /**
     * 把一个**历史采样点**补进三处：笔迹画布、当前笔画的点集、HCR 的逐点流式喂入。
     *
     * 与主流程里"当前点"的处理是同一件事，区别只是它来得更早
     *（见 [onStylusMotion] 里关于 `getHistoricalX/Y` 的说明）。
     * 单独抽出来是为了**不碰主流程**那段已经很脆的判定逻辑。
     */
    private fun acceptExtraPoint(
        ime: InputMethodService,
        rawX: Float,
        rawY: Float,
        time: Long,
        canvasX: Float,
        canvasY: Float,
        feedX: Float,
        feedY: Float,
    ) {
        if (HookPrefs.stylusInkEnabled()) {
            ink.view?.addPoint(canvasX, canvasY, ACTION_MOVE)
        }
        synchronized(stateLock) {
            current?.add(PencilEngine.Pt(rawX, rawY, ACTION_MOVE, time))
        }
        if (usingHcr) {
            runCatching { hcrFeed?.invoke(null, ime, feedX.toInt(), feedY.toInt(), ACTION_MOVE) }
        }
    }

    private fun onStylusMotion(ime: InputMethodService, ev: MotionEvent) {
        val action = ev.actionMasked
        // 手写事件是屏幕坐标（框架从输入通道直接给过来，没有经过 View 变换）。
        // 画线与喂引擎各需要一套局部坐标，所以对外拿的是"画布/窗口在屏幕上的位置"，
        // 详见 StylusInkOverlay.canvasOffset()/windowOffset()。
        val rawX = ev.rawX
        val rawY = ev.rawY
        val time = ev.eventTime

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downTime = time
                downX = rawX
                downY = rawY
                maxDist = 0f
                moveEvents = 0
                strokeActive = true
                // 距上一笔抬起多久 —— "单独画的一笔"的判据（连接·拆分那种改动型笔势只认它）
                idleBeforeStroke =
                    if (lastStrokeUpTime > 0L) time - lastStrokeUpTime else Long.MAX_VALUE
                // 又落笔了：把轻点"带走"的那批笔迹并回累积，识别继续等真正停笔
                mergeCarriedBack()
                // 落笔时同步一次画布/窗口的真实位置（上一笔之后可能发生过布局变化）。
                // 每个 MOVE 都去问 View 的位置没必要 —— 每秒上百次；整笔沿用这次的结果。
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

        // 画布局部坐标（画线用）与手写窗口局部坐标（喂引擎用）
        val canvasLoc = ink.canvasOffset()
        val windowLoc = ink.windowOffset()
        val canvasX = if (canvasLoc != null) rawX - canvasLoc[0] else rawX
        val canvasY = if (canvasLoc != null) rawY - canvasLoc[1] else rawY
        val feedX = if (windowLoc != null) rawX - windowLoc[0] else rawX
        val feedY = if (windowLoc != null) rawY - windowLoc[1] else rawY

        // ---- 补上 MotionEvent 里的**历史点** ----
        //
        // 高刷笔（本机是 240Hz 级的数字笔）的事件是**批处理**的：一次 MOVE 往往还带着
        // 1~3 个更早的采样点（`getHistoricalX/Y`），只有当前点是"最新"那个。
        // 只取当前点 = 把轨迹抽稀，引擎看到的折线比用户实际写的粗糙 ——
        // 这是识别率上一个**白捡的损失**：补上只会更准，不会更不准。
        // 三处都要补：笔迹画布、当前笔画的点集（识别用）、HCR 的逐点流式喂入。
        if (action == MotionEvent.ACTION_MOVE && ev.historySize > 0) {
            // getHistoricalX/Y 给的是**窗口局部坐标**，而这一路上一直用 rawX/rawY（屏幕坐标）。
            // 两者的差就是事件的 raw 偏移：拿当前这一点的差值当偏移量，把历史点换算过去，
            // 这样三条链路（画布 / 识别点集 / HCR）拿到的坐标系完全一致。
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
                    ime = ime,
                    rawX = hx,
                    rawY = hy,
                    time = ev.getHistoricalEventTime(i),
                    canvasX = if (canvasLoc != null) hx - canvasLoc[0] else hx,
                    canvasY = if (canvasLoc != null) hy - canvasLoc[1] else hy,
                    feedX = if (windowLoc != null) hx - windowLoc[0] else hx,
                    feedY = if (windowLoc != null) hy - windowLoc[1] else hy,
                )
            }
        }

        // ---- 轻点判定：只看这一笔有没有"画线" ----
        //
        // 规则（用户定的）：点击就是点击，只有画线才是写字。
        //
        // 判据直接跟**框架自己**对齐：framework 的 HandwritingInitiator 只有在笔移动超过
        // touchSlop 之后才会 startStylusHandwriting（见
        // `~/type/re/work/out/FW_hi/sources/android/view/HandwritingInitiator.java` 的
        // ACTION_MOVE 分支里的 `largerThanTouchSlop(...)`）—— 也就是说"位移不到 touchSlop 的手势"
        // 在系统眼里本来就不是画线。用同一个阈值，才能保证"我们判成点击"与"系统判成没画线"一致。
        //
        // 不再掺**会话状态**：早先那版加了"本会话写过没有"，结果是写完字之后再点一下就被当
        // 写字送去识别（点一下变成一个字的经典 bug）—— 那是"整场会话"级别的状态，一次误判就
        // 一直错下去。这里用的是**本笔落笔前停了多久**，是局部量，不会有那个后遗症。
        //
        // 为什么必须加这个时间条件：写字途中笔尖难免蜻蜓点水地碰一下（笔画之间、写「点」画、
        // 手抖），这些触碰的位移常常也小于 touchSlop。只看位移的话它们会被判成"点击" → 立刻
        // 收会话 → 攒了半个字的笔迹被送去识别，真机反馈就是"字还没写完就被中断然后出字了"。
        // 加上它之后：只有**停下来之后**的轻触才算点击，写字途中的触碰算普通笔画。
        val isTap = action == MotionEvent.ACTION_UP &&
            maxDist <= tapSlop(ime) &&
            idleBeforeStroke >= TAP_IDLE_MS

        // 墨迹：边收边画。轻点不留墨（它只是搬光标/交回笔）。
        if (HookPrefs.stylusInkEnabled() && !isTap) {
            ink.view?.addPoint(canvasX, canvasY, action)
        }

        // 这一笔（抬笔时）的完整点集：手势识别要用它的**屏幕坐标**
        var justFinished: List<PencilEngine.Pt>? = null

        synchronized(stateLock) {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    current = ArrayList<PencilEngine.Pt>(64).also {
                        it.add(PencilEngine.Pt(rawX, rawY, ACTION_DOWN, time))
                    }
                    // 每次落笔重新探测：小爱可能在这期间切到了手写键盘
                    usingHcr = hcrEngineAvailable(ime)
                    // 又落笔了 → 把还没到点的"抬手"撤掉（用户还在写，不能出字）
                    workerHandler?.removeCallbacks(hcrLiftTask)
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
            // 轻点 = 点击：不识别、不留墨，并把笔交回系统。
            //
            // 但有两件事必须做对，否则"点一下"会伤人：
            //
            // ① **只摘掉这一笔，不能清空待识别笔迹**。上面的同步块已经把这一笔 add 进
            //    strokes 了，早先这里是 strokes.clear()：刚写完一个字、还没到识别延迟就点一下，
            //    那个字会被一起清掉（延迟任务到点后看到空集合，直接返回）—— 字就丢了。
            //    现在把这一笔从集合里摘掉后立刻把前面攒的送识别。
            // ② **HCR 手里那条半开笔画要收掉**：DOWN 早就喂给 HCR 了（那时还判不出是轻点），
            //    这里要是直接 return，它就挂着一条永远等不到 UP 的笔画，下一笔会被拼到它后面。
            //    补一个 UP 再 resetHcr —— 收尾 + 丢弃这次输入，而不是 lift（lift 会出字）。
            synchronized(stateLock) {
                if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
                current = null
            }
            ink.view?.removeLastStroke()
            sessionStrokeCount++
            if (usingHcr) {
                runCatching { hcrFeed?.invoke(null, ime, feedX.toInt(), feedY.toInt(), ACTION_UP) }
                runCatching { hcrClear?.invoke(null, ime) }
            } else {
                // 轻点要立刻收会话（把笔交回系统），但**不能**因此提前识别。
                //
                // 早先这里是 flushRecognition()：立即把攒下的笔迹送去识别。真机表现就是
                // "字还没写完就被中断然后出字了" —— 而且它走的是 `dispatchRecognition()`，
                // 也就是**系统笔引擎**，不是用户在设置里选的讯飞，所以出的字还常常是错的。
                // 现在改成"带走这批笔迹 + 按停笔延迟到点再识别"，见 [carryStrokes]。
                carryStrokes()
            }
            // ★ 必须**post 出去**，不能在这里同步收会话。
            //
            // 这个钩子是在框架 `InputMethodImpl.deliverStylusHandwritingMotionEvent()` 里被调的，
            // 而它调完我们之后紧接着还有一段收尾：
            //
            //     switch (motionEvent.getAction()) {
            //         case 1: case 3: mPrivOps.setHandwritingSurfaceNotTouchable(true); break;  // UP 把触摸还回去
            //     }
            //
            // 同步调 finish 会把会话 requestId 先清掉，于是 system_server 侧
            // `HandwritingModeController.setNotTouchable()` 开头的
            // `if (!getCurrentRequestId().isPresent()) return;` 直接空转 ——
            // 那层手写 surface 就留在"咬着触摸"的状态里出不来（真机实测：输入窗口表里
            // `stylus-handwriting-event-receiver-0` 一直挂着 `INTERCEPTS_STYLUS`、ownerPid 是输入法进程）。
            // 等这次事件分发彻底走完再收会话，框架那两句收尾才能正常执行。
            mainHandler.post { runCatching { ime.finishStylusHandwriting() } }
            return
        }

        if (action == MotionEvent.ACTION_UP) {
            sessionStrokeCount++
        }

        // ---- 书写手势（圈选 / 划掉删除 / 尖尖插入 / 折线换行 / 短竖线连接·拆分）----
        //
        // 识别复用系统笔引擎的 `GestureFacade`，它直接产出框架的 HandwritingGesture
        //（见 [StylusGestureEngine]）。这里只做两件事：**便宜的几何预筛** + 交给框架执行。
        //
        // 为什么要预筛：`GestureFacade` 构造时会给全局 P2PManager 注册 MotionPoint 解析器，
        // 与文字识别共用引擎状态；早先"每次抬笔都调它"真机表现是识别率越用越差。
        // 只有形状上像手势的笔画才值得惊动它 —— 预筛偏宽松（假阳性只是白跑一次引擎调用，
        // 假阴性则会让手势失效），真正的分类还是引擎说了算。
        if (action == MotionEvent.ACTION_UP && HookPrefs.stylusGestureEnabled()) {
            val stroke = justFinished
            if (stroke != null && looksLikeGesture(ime, stroke, prevStroke)) {
                val g = gestureEngine.recognize(ime, stroke.map { PointF(it.x, it.y) })
                // ★ 模型说了不算：形状对不上就不执行（见 [gestureShapeOk]）。
                //   真机反馈"横线也被识别为圈选"就是缺了这一步。
                if (g != null && gestureShapeOk(g, stroke, prevStroke)) {
                    performGesture(ime, g, feedX.toInt(), feedY.toInt())
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

        // 笔势**故意不放在这里**：早期版本在每次抬笔时都调 GestureFacade，而它构造时会给全局
        // P2PManager 注册 MotionPoint 解析器，与文字识别共用引擎状态 —— 真机表现就是
        // "一开始识别很准，后来越改越差"。现在模块完全不碰笔势：文字没认出来就只是没认出来。
        //
        // 也不依赖框架把手写事件回放给画布：那条路要先过 InkWindow.isInkViewVisible()
        //（画布没布局完就先塞进 RingBuffer 等），而我们在 onStylusHandwritingMotionEvent
        // 里本来就拿到了每一笔，直接喂画布少一条路径，也不会重复画。

        // ⓪ 我们**自己拉起来**的讯飞 HCR（与小爱那条链共用引擎，所以互斥：usingHcr 时走它那条）
        //
        // 节奏：**攒一段 → 停笔重认整段 → 组词态上屏（可被替换）→ 会话结束时落定**。
        //
        // 一路试下来的取舍（都踩过）：
        //   · 每笔就 commit：第一笔被认成别的字就落定了，后面越写越乱；
        //   · 每次识别后**清空累积**再 commit：中间结果同样落了定 —— 写「好」会变成「女」+「子」，
        //     写「家」会变成上下两个独立字（真机反馈的原始现象）；
        //   · 所以现在是：**累积不清空**，每次把整段重认一遍，用 `setComposingText`
        //     覆盖上一轮结果（自动优化文字），只在会话结束时 `finishComposingText` 落定。
        if (iflytek.isActive && !usingHcr) {
            if (action == MotionEvent.ACTION_CANCEL) {
                iflytek.reset()
                synchronized(stateLock) { iflytekStrokes.clear() }
                return
            }
            if (action == MotionEvent.ACTION_UP) {
                justFinished?.let {
                    synchronized(stateLock) { iflytekStrokes.add(it) }
                }
                // 停笔后把**整段**送去重认，结果走组词态（见 [showComposing]）。
                // 这里**不清空** [iflytekStrokes]：下一次识别要把整段重新认一遍，
                // 上一轮的中间结果（「女」）才能被更好的结果（「好」）替换 ——
                // 这就是「自动优化文字」。落定只在会话结束时（[finishComposing]）。
                val h = workerHandler()
                h.removeCallbacks(recognizeTask)
                h.postDelayed(recognizeTask, recognizeDelayMs())
            }
            return
        }

        // ① 小爱自己的讯飞 HCR：只有它当前就是手写引擎时才走
        if (usingHcr) {
            runCatching { hcrFeed?.invoke(null, ime, feedX.toInt(), feedY.toInt(), action) }
                .onFailure { L.w("event=stylus_hcr_feed_failed msg=${it.message}") }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                // ★ 抬手**不立刻** lift。小爱那条链的抬手（`v4.x.e`）会直接去取候选并上屏，
                //   等于"没停笔就出字" —— 它完全绕过下面的停笔延迟，真机反馈里的"提前出字"
                //   最可能就是这里。改成按同一个延迟再抬手；中间又落笔就在 DOWN 分支里撤掉。
                val h = workerHandler()
                h.removeCallbacks(hcrLiftTask)
                h.postDelayed(hcrLiftTask, recognizeDelayMs())
                synchronized(stateLock) { strokes.clear() }   // HCR 自己上屏，别重复处理
                // 字出来之后墨迹就该退场。延迟一小会儿是为了让「笔迹→文字」看起来是接续的；
                // 期间又落笔了（strokeActive）就不清，免得把新写的那一笔抹掉。
                mainHandler.removeCallbacks(inkFadeTask)
                mainHandler.postDelayed(inkFadeTask, INK_FADE_MS)
            }
            return
        }

        // ② 系统笔引擎：停笔后延迟识别，连续落笔会把计时重置（对应「停笔识别延迟」设置项）
        if (action == MotionEvent.ACTION_UP) {
            val h = workerHandler()
            h.removeCallbacks(recognizeTask)
            h.postDelayed(recognizeTask, recognizeDelayMs())
        }
    }

    /** 延后清墨迹的任务（HCR 上屏之后；若期间又落笔则跳过）。 */
    private val inkFadeTask = Runnable {
        if (!strokeActive) ink.clear()
    }

    /**
     * 新落笔时，把轻点**带走**的那批笔迹并回当前累积，并撤掉那次提前排的识别。
     *
     * 不这么做的话：写完两笔 → 停一下 → 笔尖轻触（按轻点判据算点击）→ 半个字被"带走"、
     * 延迟到点就落字了 —— 而用户其实还在写同一个字，看到的又是"还没写完就出字"。
     * 并回来之后，识别只会在**真正停笔**之后发生。
     */
    private fun mergeCarriedBack() {
        val carried = carriedStrokes ?: return
        carriedStrokes = null
        synchronized(stateLock) {
            strokes.addAll(carried)
            // 讯飞那条路用的是另一份累积（它每个识别周期要重喂整段），所以两边都要并
            if (iflytek.isActive && !usingHcr) iflytekStrokes.addAll(carried)
        }
        workerHandler?.removeCallbacks(recognizeTask)
        L.i("event=stylus_carry_merged strokes=${carried.size}")
    }

    /**
     * 停笔延迟到点后才让小爱 HCR 那条链"抬手"（见 [onStylusMotion] 的 `usingHcr` 分支）。
     *
     * 抬手动作（`v4.x.e`）会取候选并**直接上屏**，所以必须等停笔 —— 否则就是"没写完就出字"。
     */
    private val hcrLiftTask = Runnable {
        val ime = sessionIme ?: return@Runnable
        runCatching { hcrLift?.invoke(null, ime) }
            .onFailure { L.w("event=stylus_hcr_lift_failed msg=${it.message}") }
        L.i("event=stylus_hcr_lift")
    }

    /** 小爱当前是否挂着讯飞手写引擎（`v4.x.g()` 非空表示是 `r9.j`）。 */
    private fun hcrEngineAvailable(ime: InputMethodService): Boolean =
        runCatching { hcrEngine?.invoke(null, ime) != null }.getOrDefault(false)

    // ------------------------------------------------------------------ 书写手势

    /**
     * 便宜的几何预筛：这一笔（配合上一笔）像不像手势。
     *
     * 只看包围盒、首尾距离、方向这些 O(n) 的量，不碰引擎。五类：
     * - **闭合环**（圈选）→ `SelectGesture`；
     * - **长横线**（划掉删除）→ `DeleteGesture`；
     * - **短竖线**（连接·拆分）→ `JoinOrSplitGesture`；
     * - **直角折线**（换行）→ `InsertGesture`（插换行符）；
     * - **尖尖**（在光标处插入）→ `InsertModeGesture`：两笔相接成 V，或一笔画出 ^/V。
     *
     * 这里**总体偏宽松**：假阳性只是白问一次模型（模型说不像时这一笔照常当文字识别），
     * 假阴性才是功能直接失效。所以各条分支的阈值都按"宁可多放行"来定。
     *
     * **例外是短竖线（连接·拆分）**：竖笔在中文里太常见，放宽的代价不是"多问一次"而是
     * "写字被打断"，所以那一条反过来要严 —— 只认**单独画的、落在已有文字之间**的竖线
     *（见 [isMarkBetweenText]）。
     */
    private fun looksLikeGesture(
        ime: InputMethodService,
        stroke: List<PencilEngine.Pt>,
        prev: List<PencilEngine.Pt>?,
    ): Boolean {
        if (stroke.size < 6) return false
        val g = Geometry(stroke)
        if (g.w < MIN_GESTURE_PX && g.h < MIN_GESTURE_PX) return false

        if (isLoopShape(g)) return true                       // ① 圈选
        if (isStrikeShape(g)) return true                     // ② 划掉删除
        if (isVerticalShape(g) && isMarkBetweenText(ime)) return true   // ②b 连接·拆分
        if (isCaretShape(stroke, g, prev)) return true         // ③ 尖尖插入
        if (isPolylineShape(stroke, g)) return true            // ④ 折线换行

        // 诊断用：形状上"已经很像删除线"、却还是被预筛挡掉的笔画，采样记一笔。
        if (g.w > MIN_STRIKE_PX * 0.7f && g.w > 1.3f * g.h) {
            L.sampled("strike_rejected", limit = 8) {
                "event=stylus_gesture_rejected w=${g.w.toInt()} h=${g.h.toInt()} pts=${stroke.size}"
            }
        }
        return false
    }

    /**
     * 引擎给出的手势**再验一遍形状** —— 每种手势必须对得上自己那条形状判据。
     *
     * 为什么必须有这一步：判形状的是引擎里的小模型（`one_gesture_model.tflite`），它会认错。
     * 真机反馈"横线也被识别为圈选"就是它：预筛按"长横线"把这一笔放行去问模型，模型却回了
     * SELECT，于是这一横把文字选上了。预筛只决定"要不要问"，**问出来的答案还得对得上形状**，
     * 否则一次误判就直接改坏用户的东西（圈选会动选区、删除会删字、换行会插换行符）。
     *
     * 用的判据与预筛**同一套函数**，不会出现两处阈值各说各话。
     */
    private fun gestureShapeOk(
        gesture: HandwritingGesture,
        stroke: List<PencilEngine.Pt>,
        prev: List<PencilEngine.Pt>?,
    ): Boolean {
        val g = Geometry(stroke)
        return when (gesture) {
            is SelectGesture -> isLoopShape(g)
            is DeleteGesture -> isStrikeShape(g)
            is JoinOrSplitGesture -> isVerticalShape(g)
            is InsertGesture -> isPolylineShape(stroke, g)
            is InsertModeGesture -> isCaretShape(stroke, g, prev)
            else -> true
        }
    }

    /** 一笔的几何特征（包围盒 / 首尾间距）。五个形状判据共用，省得每处各算一遍。 */
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

    /**
     * 闭合环（**画框框**）→ 圈选。
     *
     * 首尾相接 + 两个方向都张得开。比例放宽过两次（0.25 → 0.30 → 0.40）：圈得不圆、
     * 留个口子的也放行 —— 真判形状的是模型，预筛只管别把候选漏掉；而**执行前**的
     * [gestureShapeOk] 会用同一条判据再验一次，所以放宽不会让"横线变成圈选"。
     */
    private fun isLoopShape(g: Geometry): Boolean =
        g.gap < SELECT_GAP_RATIO * maxOf(g.w, g.h) &&
            g.w > MIN_GESTURE_PX && g.h > MIN_GESTURE_PX

    /** 长横线（划掉）→ 删除。 */
    private fun isStrikeShape(g: Geometry): Boolean =
        g.w > STRIKE_ASPECT * g.h && g.w > MIN_STRIKE_PX

    /**
     * 短竖线 → 连接·拆分。
     *
     * 这条**必须严**：初版只判形状，真机结果就是"竖线被识别成手势的概率太大，写字被打断"。
     * 所以形状之外还要过 [isMarkBetweenText]（**单独画的** + **落在已有文字之间**），
     * 那道门在 [looksLikeGesture] 里把关。
     */
    private fun isVerticalShape(g: Geometry): Boolean =
        g.h > SPLIT_ASPECT * g.w && g.h > MIN_SPLIT_PX && g.h < MAX_SPLIT_PX

    /**
     * 直角折线（一横一竖）→ 换行。
     *
     * 与尖尖的区别很清楚：尖尖两条腿**都是竖的**（上-下折返），折线是**一横一竖** ——
     * 所以按"两段的主导轴不同"判，顺带也排除了直线（两段同轴）与闭合环（弦退化）。
     */
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

    /**
     * 尖尖（^ / V）→ 在光标处插入。
     *
     * 两种画法都认：**两笔相接**成 V（上一笔向上、这一笔向下），或**一笔画出** ^/V
     * （折返点在笔画中段、两端朝同侧张开）。
     *
     * 阈值放宽过几次（真机反馈"画尖尖识别概率低"）：原来是每笔上下都要超过
     * MIN_GESTURE_PX(90px)、总宽 >200px、相接误差 <35% —— 小一点的 ^ 全被漏掉。
     */
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
            val prevUp = pLast.y < pFirst.y - MIN_CARET_LEG_PX   // 上一笔向上
            val curDown = g.last.y > g.first.y + MIN_CARET_LEG_PX // 这一笔向下
            val wide = (maxOf(g.maxX, pLast.x) - minOf(g.minX, pFirst.x)) > MIN_CARET_PX
            if (join < CARET_JOIN_RATIO * maxOf(g.w, g.h) && prevUp && curDown && wide) return true
        }

        if (g.w > MIN_CARET_PX && g.h > MIN_CARET_LEG_PX && stroke.size >= 6) {
            // ^（尖端朝上）：最高点在中间，两端都比它低
            var turnIdx = 0
            for (i in stroke.indices) if (stroke[i].y < stroke[turnIdx].y) turnIdx = i
            val apexY = stroke[turnIdx].y
            if (turnIdx in 1 until stroke.size - 1 &&
                g.first.y > apexY + MIN_CARET_LEG_PX && g.last.y > apexY + MIN_CARET_LEG_PX
            ) {
                return true
            }
            // V（尖端朝下）：最低点在中间，两端都比它高
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

    /**
     * 「单独画的一笔、而且落在**已有文字之间**」—— 连接·拆分这种**改动型**笔势的准入条件。
     *
     * 两道都要满足：
     *
     * 1. **单独画的**：距上一笔抬起超过 [MARK_IDLE_MS]。正常写字笔画是连着来的（几百毫秒一笔），
     *    不会满足；要连接 / 拆分才会先停一下、再单独画一竖。
     * 2. **落在文字上**：光标**前后都有字**才放行。写字是从文字末尾往后写，落笔处光标后面是空的；
     *    要连接 / 拆分才会把笔落在两个字**之间**。框架在落笔时会先把光标移到落笔位置
     *   （`HandwritingInitiator.requestFocusWithoutReveal` → `EditText.setSelection(偏移)`），
     *    所以到抬笔这一刻这个判断是有效的。
     *
     * 宿主不返回上下文（`getTextBeforeCursor` 返回 null）时按**不成立**处理 ——
     * 这个手势宁可少触发，也不能再出现"写字被打断"。
     */
    private fun isMarkBetweenText(ime: InputMethodService): Boolean {
        if (idleBeforeStroke < MARK_IDLE_MS) return false
        return runCatching {
            val ic = ime.currentInputConnection ?: return@runCatching false
            ic.getTextBeforeCursor(1, 0)?.isNotEmpty() == true &&
                ic.getTextAfterCursor(1, 0)?.isNotEmpty() == true
        }.getOrDefault(false)
    }

    /**
     * 折角的下标：离"首尾连线"最远的那个点（点到弦的垂距最大处）。
     *
     * 首尾几乎重合（闭合环）时弦退化，返回 -1 —— 那种形状已经被闭合环那条判过了。
     * 折线（换行）预筛用它把一笔拆成两条腿，再比两条腿的主导轴。
     */
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

    /**
     * 把手势交给宿主应用执行。
     *
     * 输入法只负责"识别形状 + 报坐标"：选区、删字、插入都是宿主编辑器的活
     *（`InputConnection.performHandwritingGesture` → 宿主的 `Editor` 用那个矩形去比对自己的文本布局）。
     * 所以这里喂的是**屏幕坐标**（引擎产出的 area/point 就是屏幕坐标系的）。
     */
    private fun performGesture(ime: InputMethodService, gesture: HandwritingGesture, feedX: Int, feedY: Int) {
        // 手势不该变成文字，也不该留墨：从待识别集合里摘掉这一笔、抹掉它的墨迹
        synchronized(stateLock) {
            if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
            current = null
        }
        ink.view?.removeLastStroke()

        // HCR 那条路也要收尾：这一笔的 DOWN/MOVE 已经喂过去了，补一个 UP 再 reset，
        // 否则引擎手里挂着半条笔画（与"轻点"同理）
        if (usingHcr) {
            runCatching { hcrFeed?.invoke(null, ime, feedX, feedY, ACTION_UP) }
            runCatching { hcrClear?.invoke(null, ime) }
        }

        val ic = ime.currentInputConnection
        if (ic == null) {
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
        L.i("event=stylus_gesture_performed type=$name target=${ime.currentInputConnection != null}")
    }

    /**
     * "算不算画线"的位移阈值（px）：直接用系统的 touchSlop。
     *
     * 不写死常数：touchSlop 随密度与系统的触摸灵敏度设置变（本机 density 2.58125，
     * 标准 8dp 的 touchSlop ≈ 21px）。与 [android.view.inputmethod.HandwritingInitiator]
     * 用的是同一个量，所以"我们判成点击"和"系统判成没画线"永远是同一个标准。
     */
    private fun tapSlop(ime: InputMethodService): Float =
        runCatching {
            android.view.ViewConfiguration.get(ime).scaledTouchSlop.toFloat()
        }.getOrDefault(FALLBACK_TAP_SLOP)

    /**
     * 延迟任务到点：讯飞 HCR 优先，其次系统笔引擎。
     *
     * **不清空累积** —— 每次都要把"到目前为止的整段字迹"重认一遍，中间结果才能被替换
     *（见 [showComposing]）。累积由会话结束/开始来清（那时才落定）。
     */
    private fun recognizeAndCommit() {
        val ime = sessionIme ?: return

        // ⓪ 轻点收会话时"带走"的那批笔迹（见 [carryStrokes]）：先并回对应的累积里
        carriedStrokes?.let { carried ->
            carriedStrokes = null
            synchronized(stateLock) {
                strokes.addAll(carried)
                if (iflytek.isActive && !usingHcr) iflytekStrokes.addAll(carried)
            }
        }

        // ① 讯飞 HCR：整段字迹归一化后重喂 → 取候选 → **组词态**上屏
        if (iflytek.isActive && !usingHcr) {
            val all = synchronized(stateLock) { iflytekStrokes.map { ArrayList(it) } }
            if (all.isEmpty()) return
            val top = iflytek.recognize(all).firstOrNull()
            if (top.isNullOrEmpty()) {
                L.i("event=stylus_iflytek_empty")
                return
            }
            showComposing(ime, top)
            return
        }

        // ② 系统笔引擎：同样整段重认、组词态上屏
        val snapshot = synchronized(stateLock) {
            if (strokes.isEmpty()) null else strokes.map { ArrayList(it) }
        } ?: return
        dispatchRecognition(ime, snapshot)
    }

    /**
     * 停笔识别延迟（ms）—— **唯一入口**。
     *
     * 早先三条路各写各的 clamp（`coerceIn(200,1500)` / `coerceIn(50,1000)`），同一份设置
     * 在不同路径下等的时间还不一样（就是"没有按照我的设置"）。现在统一按设置走，只做下限保护。
     */
    private fun recognizeDelayMs(): Long =
        HookPrefs.stylusDelayMs().toLong().coerceIn(50L, 5000L)

    /**
     * 会话空闲超时：比停笔识别延迟长一点，免得正在识别时把会话收掉。
     *
     * 会话越短越好 —— 会话活着的时候笔被输入法攥着（见 [beginSession]）。
     */
    private fun sessionTimeoutMs(): Long = maxOf(1200L, recognizeDelayMs() + 600L)

    /** 把当前设置下发给讯飞引擎（间隔取自滑块；写区域固定为归一化方框）。 */
    private fun reconfigureIflytek(ime: InputMethodService) {
        if (!HookPrefs.stylusIflytek() || hcrEngineAvailable(ime)) return
        iflytek.open(
            ime,
            areaLeft = 0,
            areaTop = 0,
            areaRight = InkNormalizer.SIZE,
            areaBottom = InkNormalizer.SIZE,
            intervalMs = HookPrefs.stylusDelayMs().toInt(),
        )
    }

    /**
     * 把识别结果放成**组词态**上屏 —— 这就是「自动优化文字」。
     *
     * 只 `setComposingText`、**不 commit**：中间结果（比如刚写完「女」）先显示出来，后面
     * 重认得更好时**整段被替换**（「女」→「好」，「宀」+「豕」→「家」）。用 `commitText`
     * 出不来这个效果 —— 每次都落了定，后面的笔画再也改不回来，于是「好」变成「女」+「子」、
     * 「家」变成上下两个独立字。
     *
     * 落定交给 [finishComposing]（会话结束时）—— 那时才真正提交，文字下面的横线也随之消失。
     *
     * 注意这里**不再**像以前那样"字一上屏就收会话"：会话必须活着，累积与组词区才能继续被
     * 优化；会话由框架的空闲超时（[sessionTimeoutMs]）或用户轻点来收。
     */
    private fun showComposing(ime: InputMethodService, text: String) {
        mainHandler.post {
            runCatching { ime.currentInputConnection?.setComposingText(text, 1) }
                .onFailure { L.w("event=stylus_composing_failed msg=${it.message}") }
            // 文字已经在组词态显示出来了，墨迹就该退场（留着会跟文字糊在一起）
            if (!strokeActive) ink.clear()
        }
    }

    /** 落定组词态：把文字真正提交给编辑器、下划线消失。会话结束时调（见 [endSession]）。 */
    private fun finishComposing(ime: InputMethodService) {
        runCatching { ime.currentInputConnection?.finishComposingText() }
            .onFailure { L.w("event=stylus_finish_composing_failed msg=${it.message}") }
    }

    /**
     * 轻点收会话时，把已经攒下、还没到识别延迟的笔迹**带走**，并重新排识别。
     *
     * 为什么不能就地识别：`flushRecognition()`（旧实现）会**立即**出字 —— 真机表现就是
     * "字还没写完就被中断然后出字了"，而且走的是系统笔引擎、无视用户选的讯飞。
     * 为什么不能不管：紧接着的 `finishStylusHandwriting()` 会走 [endSession]，那里会把
     * `strokes` 清掉，字就丢了。
     *
     * 所以：**主线程同步取走快照**存进 [carriedStrokes]（跨会话保留），再按用户设的停笔
     * 延迟重新排 [recognizeTask]；`endSession` 见到 [carriedStrokes] 非空时不会撤掉它。
     */
    private fun carryStrokes() {
        val snapshot = takeStrokes() ?: return
        carriedStrokes = snapshot
        val delay = recognizeDelayMs()
        val h = workerHandler()
        h.removeCallbacks(recognizeTask)
        h.postDelayed(recognizeTask, delay)
        L.i("event=stylus_carry strokes=${snapshot.size} delay=${delay}ms")
    }

    /** 取出并清空待识别笔迹；没有就返回 null。 */
    private fun takeStrokes(): List<List<PencilEngine.Pt>>? = synchronized(stateLock) {
        if (strokes.isEmpty()) return@synchronized null
        val copy = strokes.map { ArrayList(it) }
        strokes.clear()
        copy
    }

    /** 后台线程识别 → 主线程上屏。 */
    private fun dispatchRecognition(ime: InputMethodService, snapshot: List<List<PencilEngine.Pt>>) {
        workerHandler().post {
            // 引擎只借 Context 用（模型路径 / 资源），给 applicationContext：
            // 引擎实例是**进程级常驻**的（见 ensureEngine），持有 Service 实例没意义还挡回收。
            val eng = ensureEngine(ime.applicationContext) ?: return@post
            val text = eng.recognize(normalize(snapshot))
            if (text.isNullOrEmpty()) {
                L.i("event=stylus_recognize_empty strokes=${snapshot.size}")
                // 没认出来就把墨迹留着：用户能看出"写了但没认出来"，而不是笔迹凭空消失
                return@post
            }
            L.i("event=stylus_recognize_ok strokes=${snapshot.size} text=$text")
            // 组词态上屏（自动优化文字那条路，见 [showComposing]）：这里**不收会话**，
            // 让累积与组词区活着，下一笔重认时把这段结果替换掉。
            showComposing(ime, text)
        }
    }

    // ------------------------------------------------------------------ 会话

    private fun beginSession(ime: InputMethodService) {
        // ★ 这个函数是在 **IMMS 的手势窗口里**被回调的，必须尽快返回。
        //
        // 真机踩了两个大坑，症状一模一样（笔写不了、还留下僵尸拦截面）：
        //   ① 这里同步读一次 RemotePreferences（binder）；
        //   ② 这里同步建 Miuix/Compose 视图（首次组合 + 主题/字体加载是几百毫秒级）。
        // 两次都是**在回调里做了耗时的事**。
        //
        // 为什么这么敏感：IMMS 里 `AFTER_STYLUS_UP_ALLOW_PERIOD_MS` 只有 **200ms**，
        // `startHandwritingSession` 会检查 `HandwritingModeController.isStylusGestureOngoing()`。
        // 我们返回晚了，这个窗口就过了 → 会话建不起来 → 那个手势的 stylus 拦截面残留下来
        // **把笔整个吃掉**，直到笔离开设备列表才会被框架清掉。
        //
        // 所以：这里只做**必须同步**的轻活，其余一律 post/后台。
        val t0 = System.nanoTime()
        sessionIme = ime
        // 偏好刷新：**必须异步**。
        //
        // beginSession 是在 IMMS 的手势窗口里被回调的（`canStartStylusHandwriting`），
        // 在这里同步等一次 binder（读 RemotePreferences）有把窗口拖过去的风险 ——
        // 一旦拖过去，IMMS 那边 `startHandwritingSession` 就会失败：会话建不起来、
        // 墨迹也不出现，而那个手势的 stylus 拦截面还会残留下来把笔吃掉。
        // 所以：后台刷新，落地后再把新值下发给讯飞引擎（第一次识别在几百毫秒之后，来得及）。
        workerHandler().post {
            runCatching { HookPrefs.rebind(module) }
                .onFailure { L.w("event=stylus_prefs_rebind_failed msg=${it.message}") }
            // 用刷新后的值重新配置讯飞引擎（间隔/写区域），这样滑块改动立刻生效
            runCatching { reconfigureIflytek(ime) }
                .onFailure { L.w("event=stylus_reconfigure_failed msg=${it.message}") }
        }
        // 0) 会话空闲超时**必须短**。
        //
        // 这里踩过一个大坑：早先按注释「默认很短，写着写着会断掉」把它设成了
        // `getStylusHandwritingIdleTimeoutMax()` —— 那是 **30 秒**（见 framework
        // `InputMethodService.setStylusHandwritingSessionTimeout` 里 30000 的夹取）。
        // 后果是真机上「进书写模式后怎么点都出不来，像有层透明遮罩」，原因在 system_server：
        //
        //   HandwritingModeController.startHandwritingSession(...)
        //       → mInputManager.pilferPointers(handwritingSurface)   // 把指针流从小爱抢给输入法
        //       → mHandwritingSurface.startIntercepting(imePid, imeUid)
        //
        // 会话活着的时候**笔的事件只有输入法收得到**，宿主应用完全看不到，
        // 所以点什么都没反应；而 `scheduleHandwritingSessionTimeout()` 会在**每个**笔事件上重置，
        // 于是每点一下就又续 30 秒 —— 永远出不来。
        //
        // 设短之后：写字时事件不断、会话不会断；一停笔就把笔交回系统。
        // 交回之后系统自己的 `HandwritingInitiator` 只会在位移超过 touchSlop 时才开新会话
        //（见 `~/type/re/work/out/FW_hi/.../HandwritingInitiator.java` 的 ACTION_MOVE 分支），
        // 于是"点一下"根本不会开手写会话，直接落到界面上 —— 正是「点击就是点击，画线才算书写」。
        //
        // 取值要比停笔识别延迟长一点，免得正在识别时把会话收掉。
        runCatching {
            val idle = sessionTimeoutMs()
            ime.setStylusHandwritingSessionTimeout(java.time.Duration.ofMillis(idle))
            L.i("event=stylus_session_timeout idle=${idle}ms")
        }.onFailure { L.w("event=stylus_timeout_failed msg=${it.message}") }

        // 1) 收起虚拟键盘：手写时不要让键盘挡着
        runCatching { ime.requestHideSelf(0) }
            .onFailure { L.w("event=stylus_hide_self_failed msg=${it.message}") }

        // 2) 笔迹画布：挂到框架的手写窗口里（可见部分是一条垂直居中的横带）。
        //    挂不上只是没有墨迹，识别照常 —— 所以失败不抛、只记日志。
        //    挂载点选在这里是因为 AOSP 的顺序是 prepare（`maybeCreateAndInitInkWindow()` 已经
        //    建好窗口）→ start，到这一步 `getStylusHandwritingWindow()` 一定有值。
        // 识别后端：小爱当前就是手写引擎时不碰（它自己那条链在跑）；
        // 否则按开关把**讯飞 HCR** 拉起来 —— 它比系统笔引擎（64 点输入/top-4 输出）强得多。
        // 拉起失败只会 isActive=false，后面所有分支自然退回系统笔引擎。
        // 讯飞引擎：首次 initHcrEngine 可能是上百毫秒的原生初始化 —— 一律 post，别挡住手势窗口。
        // 识别发生在停笔 delay 之后（几百毫秒），所以推迟这一点点完全来得及。
        val wantIflytek = !hcrEngineAvailable(ime) && HookPrefs.stylusIflytek()
        mainHandler.post {
            if (!wantIflytek) return@post
            runCatching {
                // 写区域 = [InkNormalizer.SIZE] 的方框，喂进去的坐标也归一化到这块
                //（见 recognizeAndCommit → IflytekHcrEngine.recognize → InkNormalizer.toBox）。
                // 早先写区域给整屏而坐标给屏幕坐标，引擎把整个字看成几个像素，
                // 候选出的是 `·`、`、`、`502` —— 真机诊断里抓到的。
                iflytek.open(
                    ime,
                    areaLeft = 0,
                    areaTop = 0,
                    areaRight = InkNormalizer.SIZE,
                    areaBottom = InkNormalizer.SIZE,
                    intervalMs = HookPrefs.stylusDelayMs().toInt(),
                )
            }.onFailure { L.e("event=stylus_iflytek_open_exception", it) }
        }

        if (HookPrefs.stylusInkEnabled()) {
            runCatching { ink.attach(ime) }
                .onFailure { L.e("event=stylus_ink_attach_exception", it) }
        } else {
            ink.clear()
        }

        synchronized(stateLock) {
            strokes.clear()
            current = null
        }
        sessionStrokeCount = 0
        strokeActive = false
        synchronized(stateLock) { iflytekStrokes.clear() }
        // 记录本次回调耗时：IMMS 的窗口只有 200ms，超过就有风险（诊断文件里可查）
        val costMs = (System.nanoTime() - t0) / 1_000_000
        // ★ 重活一律**不在这里做**。
        //
        // 会话建立前后只要碰 Compose/Miuix（首次组合 + 主题字体加载是几百毫秒级），
        // 或去改手写窗口的触摸标志，就可能把 IMMS 那个 200ms 的手势窗口拖过去
        // → 会话建不起来 → 僵尸拦截面吃笔。所以这里只做上面那些轻活，
        // 需要建视图 / 加载资源的活等会话真的起来之后再做。
        L.i("event=stylus_session_begin ink=${if (HookPrefs.stylusInkEnabled()) ink.isAttached else false}")
    }

    private fun endSession(ime: InputMethodService) {
        // ★ 落定组词态：会话结束才算"写完了"，这时才把文字真正交给编辑器（下划线消失）。
        //   必须放在清累积**之前** —— 清累积是为了下一次会话从空开始重认。
        finishComposing(ime)
        // 有"带走"的笔迹时**不能**撤掉识别回调 —— 那批字还等着到点识别（轻点会走到这里）
        if (carriedStrokes == null) workerHandler?.removeCallbacks(recognizeTask)
        workerHandler?.removeCallbacks(hcrLiftTask)
        mainHandler.removeCallbacks(inkFadeTask)
        runCatching { hcrClear?.invoke(null, ime) }
        synchronized(stateLock) {
            strokes.clear()
            current = null
        }
        usingHcr = false
        strokeActive = false
        sessionStrokeCount = 0
        prevStroke = null
        synchronized(stateLock) { iflytekStrokes.clear() }
        // 会话结束：丢弃讯飞引擎里未取走的输入（引擎本身不 release，小爱的键盘可能还在用）
        iflytek.reset()
                // 会话结束就把墨迹收掉：手写窗口会隐藏，留着只会在下次会话开头闪一下
        ink.clear()
        L.i("event=stylus_session_end")
    }

    // ------------------------------------------------------------------ 坐标

    /**
     * 把一批笔迹整体平移到"包围盒左上角 = (0,0)"。
     *
     * **这是识别率的关键**：系统笔引擎是按书写区域预期输入的，笔迹整体带一个偏移
     * （屏幕坐标可能从 (1000, 800) 这种位置起笔）会让笔画落到它不认识的盒子里 ——
     * 真机表现就是"早期用局部坐标很准，改成屏幕坐标后成功率明显下滑"。
     * 平移不改形状，只把位置归零，所以对识别只有好处。
     *
     * 这套只用在**系统笔引擎**这条路上（它是"攒一批再一次性识别"）。
     * 讯飞 HCR 是**逐点流式**喂的，没法事后整体平移，所以那边改用
     * [StylusInkOverlay.windowOffset] 把屏幕坐标换算成**手写窗口坐标**再喂 ——
     * 同一件事（把笔迹挪回引擎预期的坐标系），两条路各自的正确做法。
     */
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



    // ------------------------------------------------------------------ 工具

    private fun isTarget(obj: Any?): Boolean {
        val cls = imeClass ?: return false
        return obj != null && cls.isInstance(obj)
    }

    /** 懒建后台线程：只有真的收到过笔事件才会起。 */
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
     * 懒开系统笔引擎。
     *
     * 引擎实例是**进程级常驻**的：`RecognizeFacade` 的构造会把 17MB 的
     * `/system_ext/etc/ocr_model.tflite` 吃进 native 层，每次会话重建的代价太大，
     * 而且写一行字可能要识别好几次。代价是这块内存不会还回去 —— 对常驻的输入法进程可接受。
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

        // MotionEvent 的动作码，直接对齐小爱 HCR 的 action 语义
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2

        /**
         * "算不算画线"的位移阈值兜底值（px）—— 正常走 [tapSlop] 读系统的 touchSlop，
         * 只有取不到时才用这个数。
         */
        const val FALLBACK_TAP_SLOP = 20f

        /**
         * 判成"轻点（点击）"还要求**落笔前已经停了这么久**（ms）。
         *
         * 写字途中笔尖蜻蜓点水地碰一下的位移也常常小于 touchSlop，只看位移会把它们误判成
         * 点击 → 立刻收会话 → 半个字被送去识别（"字还没写完就被中断然后出字了"）。
         * 只有停下来之后的轻触才是真的想点（搬光标 / 把笔交回系统）。
         */
        const val TAP_IDLE_MS = 300L


        /** 手势预筛：一个方向至少要这么大（px）才可能是手势。原 90，为了"更容易触发"降到 72。 */
        const val MIN_GESTURE_PX = 72f

        /** 闭合环（圈选）：首尾间距要小于「包围盒长边 × 这个比例」才算圈上了（原 0.30，放宽到 0.40）。 */
        const val SELECT_GAP_RATIO = 0.40f

        /**
         * 划掉删除（长横线）预筛的最小宽度（px）。
         *
         * 演变：`2f * MIN_GESTURE_PX`（180px）/ 宽高比 2.2 → 108px / 1.7 → 现在 **90px / 1.5**。
         * 真机反馈是"划掉识别不出来"，而引擎里判形状的是 `one_gesture_model.tflite`
         * （`DeleteGestureParser` 自己只按包围盒建手势、不做形状校验）—— 所以我们这边
         * 每收紧一分，就是直接把候选丢掉。
         *
         * 放松的代价只是"多问模型几次"，**不会删错东西**：模型说不像删除时这一笔照常当
         * 文字识别。唯一要守的底线是别放成"任何横线都问一次" —— `GestureFacade` 与文字
         * 识别共用引擎状态（`P2PManager`），早先"每次抬笔都调它"实测会让识别率越用越差。
         */
        const val MIN_STRIKE_PX = 90f

        /** 划掉删除预筛的宽高比下限（原 2.2 → 1.7 → 1.5：容许画得斜一点）。 */
        const val STRIKE_ASPECT = 1.5f

        /** 连接·拆分（短竖线）：至少这么长（px）。 */
        const val MIN_SPLIT_PX = 70f

        /** 连接·拆分（短竖线）：最长到这里（px）—— 再长就不像"两字之间点一竖"，当普通笔画。 */
        const val MAX_SPLIT_PX = MIN_SPLIT_PX * 3f

        /** 连接·拆分：高要是宽的这么多倍才算"够竖"。 */
        const val SPLIT_ASPECT = 1.4f

        /** 连接·拆分：这一笔距上一笔抬起至少隔这么久（ms）才算"单独画的"。 */
        const val MARK_IDLE_MS = 600L

        /** 换行（折线钩）：笔画至少要这么多点才去找折角。 */
        const val MIN_NEWLINE_POINTS = 8

        /** 换行（折线钩）：两条腿各自的最小长度（px）。 */
        const val MIN_NEWLINE_LEG_PX = 40f

        /** 尖尖（插入）手势的最小宽度。原为 200 → 110，现在 90：小一点的 ^ 也要放行。 */
        const val MIN_CARET_PX = 90f

        /** 尖尖两侧"腿"至少要有这么高（px）。原 90 → 45 → 36。 */
        const val MIN_CARET_LEG_PX = 36f

        /** 尖尖（两笔版）允许的"两笔相接"误差比例（原 0.35 → 0.5，现在 0.6）。 */
        const val CARET_JOIN_RATIO = 0.6f

        /** HCR 上屏后延迟多久收掉墨迹（留一点"笔迹→文字"的接续感）。 */
        const val INK_FADE_MS = 400L
    }
}
