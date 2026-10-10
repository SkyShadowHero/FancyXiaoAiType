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
import android.view.inputmethod.HandwritingGesture
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
 * 形状是**整屏**（屏幕宽 × 屏幕高，写哪儿都有笔迹），
 * 并且会**跟着落笔点上下移动** —— 一直写在原地就不动，写到别处就跟过去。
 *
 * 早先那版把整屏画布塞进这个窗口、真机发现窗口是 `Requested w=1497 h=1084`、
 * `frame=[851,526][2348,1610]`（屏幕 3200×2136），其实是"**窗口按内容 wrap**"的结果：
 * 那条 1497 就是当时塞进去的 `MATCH_PARENT` 带子被量出来的宽度。
 * 而 `stylus_geom.txt` 记下的事实是：第一次落笔在 `raw=(630,230)`、当时区域是
 * `[851,526][2348,1610]` —— **笔在区域外，事件照样收得到**。
 * 所以"只有中间一片有笔迹"纯粹是画布被自己那条带子裁掉了，不是系统不给事件。
 * 现在的做法：区域尺寸由我们显式给（满宽 × 固定带高），位置跟着落笔点走。
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
        // 不再掺时间判据，也不再掺会话状态：早先那版加了"本会话写过没有"，
        // 结果是写完字之后再点一下就被当成写字送去识别（点一下变成一个字的经典 bug）。
        val isTap = action == MotionEvent.ACTION_UP && maxDist <= tapSlop(ime)

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
                // 立刻把前面攒下的字送识别，别让这次点击把它拖到会话结束
                flushRecognition()
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

        // ---- 书写手势（圈选 / 尖尖插入 / 划掉删除 / 换行）----
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
            if (stroke != null && looksLikeGesture(stroke, prevStroke)) {
                val g = gestureEngine.recognize(ime, stroke.map { PointF(it.x, it.y) })
                if (g != null) {
                    performGesture(ime, g, feedX.toInt(), feedY.toInt())
                    prevStroke = stroke
                    return
                }
                L.i("event=stylus_gesture_not_recognized points=${stroke.size}")
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
        // 节奏：**攒一段 → 停笔识别 → 直接落字（commitText）→ 清空累积**。
        //
        // 一路试下来的取舍（都踩过）：
        //   · 每笔就 commit：第一笔被认成别的字就落定了，后面越写越乱；
        //   · setComposingText（组词态）能让中间结果被替换，但用户看到的是
        //     "文字下面有横线、还会被后面写的字改掉" —— 明确不要；
        //   · 所以现在是落字 + **每次落字后清空 [iflytekStrokes]**，后面的笔画不再回头改前面的字。
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
                // 停笔后识别并**直接落字**（不做组词态）：
                // 组词态虽然能让中间结果被替换，但用户看到的是"文字下面有横线、还会被
                // 后面写的字改掉" —— 明确不要。改成落字 + 每次落字后清空累积，
                // 后面的笔画就不会再回头改前面已经定下的字。
                val h = workerHandler()
                h.removeCallbacks(recognizeTask)
                h.postDelayed(recognizeTask, HookPrefs.stylusDelayMs().toLong().coerceIn(200L, 1500L))
            }
            return
        }

        // ① 小爱自己的讯飞 HCR：只有它当前就是手写引擎时才走
        if (usingHcr) {
            runCatching { hcrFeed?.invoke(null, ime, feedX.toInt(), feedY.toInt(), action) }
                .onFailure { L.w("event=stylus_hcr_feed_failed msg=${it.message}") }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                runCatching { hcrLift?.invoke(null, ime) }
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
            h.postDelayed(recognizeTask, HookPrefs.stylusDelayMs().toLong().coerceIn(50L, 1000L))
        }
    }

    /** 延后清墨迹的任务（HCR 上屏之后；若期间又落笔则跳过）。 */
    private val inkFadeTask = Runnable {
        if (!strokeActive) ink.clear()
    }

    /** 小爱当前是否挂着讯飞手写引擎（`v4.x.g()` 非空表示是 `r9.j`）。 */
    private fun hcrEngineAvailable(ime: InputMethodService): Boolean =
        runCatching { hcrEngine?.invoke(null, ime) != null }.getOrDefault(false)

    // ------------------------------------------------------------------ 书写手势

    /**
     * 便宜的几何预筛：这一笔（配合上一笔）像不像手势。
     *
     * 只看包围盒、首尾距离、方向这些 O(n) 的量，不碰引擎。三类：
     * - **闭合环**（圈选）：首尾几乎相接，且包围盒不是一条细线；
     * - **长横线**（划掉删除）：明显又宽又扁；
     * - **尖尖**（插入）：这一笔的起点接在上一笔的终点上，且上一笔向上、这一笔向下（V 形），
     *   并要求够宽 —— 太小的 V 就是「人」「八」这类字，不能当手势。
     */
    private fun looksLikeGesture(stroke: List<PencilEngine.Pt>, prev: List<PencilEngine.Pt>?): Boolean {
        if (stroke.size < 6) return false
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in stroke) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        val w = maxX - minX
        val h = maxY - minY
        if (w < MIN_GESTURE_PX && h < MIN_GESTURE_PX) return false

        val first = stroke.first()
        val last = stroke.last()
        val gap = kotlin.math.hypot((last.x - first.x).toDouble(), (last.y - first.y).toDouble()).toFloat()

        // ① 闭合环（圈选）：首尾相接 + 两个方向都张得开
        if (gap < 0.30f * maxOf(w, h) && w > MIN_GESTURE_PX && h > MIN_GESTURE_PX) return true

        // ② 长横线（划掉删除）
        if (w > 2.2f * h && w > 2f * MIN_GESTURE_PX) return true

        // ③ 尖尖（插入）· 两笔版：两笔头尾相接成 V
        //
        // 阈值放宽过（真机反馈"画尖尖识别概率低"）：原来是每笔上下都要超过
        // MIN_GESTURE_PX(90px)、总宽 >200px、相接误差 <35% —— 小一点的 ^ 全被漏掉。
        if (prev != null && prev.size >= 4) {
            val pFirst = prev.first()
            val pLast = prev.last()
            val join = kotlin.math.hypot(
                (pLast.x - first.x).toDouble(),
                (pLast.y - first.y).toDouble(),
            ).toFloat()
            val prevUp = pLast.y < pFirst.y - MIN_CARET_LEG_PX   // 上一笔向上
            val curDown = last.y > first.y + MIN_CARET_LEG_PX    // 这一笔向下
            val wide = (maxOf(maxX, pLast.x) - minOf(minX, pFirst.x)) > MIN_CARET_PX
            if (join < 0.5f * maxOf(w, h) && prevUp && curDown && wide) return true
        }

        // ③b 尖尖（插入）· **一笔版**：一笔画出 ^ 或 V，折返点在笔画中段、两端朝同侧张开。
        //
        // 这条是主要的漏检来源：大家写 ^ 常常一笔画成，而上面那条要求"两笔相接"。
        if (w > MIN_CARET_PX && h > MIN_CARET_LEG_PX && stroke.size >= 6) {
            // ^（尖端朝上）：最高点在中间，两端都比它低
            var turnIdx = 0
            for (i in stroke.indices) if (stroke[i].y < stroke[turnIdx].y) turnIdx = i
            val apexY = stroke[turnIdx].y
            if (turnIdx in 2 until stroke.size - 2 &&
                first.y > apexY + MIN_CARET_LEG_PX && last.y > apexY + MIN_CARET_LEG_PX
            ) {
                return true
            }
            // V（尖端朝下）：最低点在中间，两端都比它高
            turnIdx = 0
            for (i in stroke.indices) if (stroke[i].y > stroke[turnIdx].y) turnIdx = i
            val bottomY = stroke[turnIdx].y
            if (turnIdx in 2 until stroke.size - 2 &&
                first.y < bottomY - MIN_CARET_LEG_PX && last.y < bottomY - MIN_CARET_LEG_PX
            ) {
                return true
            }
        }
        return false
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

    /** 延迟任务到点：讯飞 HCR 优先，其次系统笔引擎。 */
    private fun recognizeAndCommit() {
        val ime = sessionIme ?: return

        // ⓪ 讯飞 HCR：整段字迹归一化后重喂 → 取候选 → **直接落字**（无组词态、无下划线）
        if (iflytek.isActive && !usingHcr) {
            val all = synchronized(stateLock) { iflytekStrokes.map { ArrayList(it) } }
            if (all.isEmpty()) return
            val top = iflytek.recognize(all).firstOrNull()
            synchronized(stateLock) { iflytekStrokes.clear() }
            if (top.isNullOrEmpty()) {
                L.i("event=stylus_iflytek_empty")
                return
            }
            commitText(ime, top)
            return
        }

        val snapshot = takeStrokes() ?: return
        dispatchRecognition(ime, snapshot)
    }

    /**
     * 会话空闲超时。
     *
     * 开了工具条就多留一会儿：落字后我们不再立刻收会话，否则工具条刚出现就消失，
     * 「撤回 / 恢复」根本来不及点。
     */
    private fun sessionTimeoutMs(): Long = maxOf(1200L, HookPrefs.stylusDelayMs().toLong() + 600L)

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

    /** 上屏 + 墨迹退场 + 字出来了就收会话（讯飞与系统笔引擎共用）。 */
    private fun commitText(ime: InputMethodService, text: String) {
        mainHandler.post {
            runCatching { ime.currentInputConnection?.commitText(text, 1) }
                .onFailure { L.w("event=stylus_commit_failed msg=${it.message}") }
            if (!strokeActive) {
                ink.clear()
                runCatching { ime.finishStylusHandwriting() }
                    .onFailure { L.w("event=stylus_finish_after_commit_failed msg=${it.message}") }
            }
        }
    }

    /**
     * 立刻识别攒下的笔迹（轻点收笔时用）。
     *
     * 一定要在主线程**同步取走快照**再交后台：紧接着 `finishStylusHandwriting()` 会走
     * [endSession]，那里会把 `strokes` 清掉 —— 要是还等后台线程自己去取，就什么也取不到了。
     */
    private fun flushRecognition() {
        val ime = sessionIme ?: return
        val snapshot = takeStrokes() ?: return
        L.i("event=stylus_flush strokes=${snapshot.size}")
        dispatchRecognition(ime, snapshot)
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
            mainHandler.post {
                runCatching { ime.currentInputConnection?.commitText(text, 1) }
                    .onFailure { L.w("event=stylus_commit_failed msg=${it.message}") }
                if (!strokeActive) {
                    // 字上屏了，墨迹退场（与 HCR 那条路一致：笔迹→文字是接续关系）
                    ink.clear()
                    // 并把会话收掉：会话在的时候笔是"被输入法拿走"的（framework 的
                    // pilferPointers），用户这时点界面点不动。收掉之后下一次落笔如果只是点一下，
                    // 系统自己的 HandwritingInitiator 不会开新会话，那一下就能落到界面上。
                    // 会话空闲超时也会做这件事，这里只是把它提前到"字已经出来了"这一刻。
                    runCatching { ime.finishStylusHandwriting() }
                        .onFailure { L.w("event=stylus_finish_after_commit_failed msg=${it.message}") }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 会话

    private fun beginSession(ime: InputMethodService) {
        // ★ 这个函数是在 **IMMS 的手势窗口里**被回调的，必须尽快返回。
        //
        // 真机踩了两个大坑，症状一模一样（笔写不了、工具条不出现、还留下僵尸拦截面）：
        //   ① 这里同步读一次 RemotePreferences（binder）；
        //   ② 这里同步建 Miuix/Compose 工具条（首次组合 + 主题/字体加载是几百毫秒级）。
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
        // 墨迹与工具条都不出现，而那个手势的 stylus 拦截面还会残留下来把笔吃掉。
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
        // ★ 工具条**不在这里建**。
        //
        // 这里建过两次都出事（同步建、post 建都试过）：只要在会话建立前后碰
        // Compose/Miuix（首次组合 + 主题字体加载是几百毫秒级）或去改手写窗口的触摸标志，
        // 就可能把 IMMS 那个 200ms 的手势窗口拖过去 → 会话建不起来 → 僵尸拦截面吃笔。
        // 现在改成**等第一个真实笔事件到达时再建**（见 onStylusMotion）：那个事件本身
        // 就证明会话已经建好了，此时再做重活不可能影响会话建立。
        L.i("event=stylus_session_begin ink=${if (HookPrefs.stylusInkEnabled()) ink.isAttached else false}")
    }

    private fun endSession(ime: InputMethodService) {
        workerHandler?.removeCallbacks(recognizeTask)
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


        /** 手势预筛：一个方向至少要这么大（px）才可能是手势。 */
        const val MIN_GESTURE_PX = 90f

        /** 尖尖（插入）预筛要求的最小宽度：太小就是「人」「八」，不是手势。 */
        /** 尖尖（插入）手势的最小宽度。原为 200，实测偏严：小一点的 ^ 会被漏掉。 */
        const val MIN_CARET_PX = 110f

        /** 尖尖两侧"腿"至少要有这么高（px）。 */
        const val MIN_CARET_LEG_PX = 45f

        /** HCR 上屏后延迟多久收掉墨迹（留一点"笔迹→文字"的接续感）。 */
        const val INK_FADE_MS = 400L
    }
}
