package io.github.skyshadowhero.fancypad

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.CursorAnchorInfo
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
 * | `onStartStylusHandwriting()` | 返回 true —— 会话才开得起来；顺带拉起悬浮层、收起键盘 |
 * | `onPrepareStylusHandwriting()` | 只记会话目标（**不**做初始化，见下面的"清两次"坑） |
 * | `onStylusHandwritingMotionEvent(MotionEvent)` | 喂墨迹 + 攒笔迹识别；**仍然放行原实现** |
 * | `onFinishStylusHandwriting()` | 收尾 |
 *
 * ## 墨迹为什么画在自建悬浮层上
 *
 * 第一版把画布 `setContentView` 进框架的 `getStylusHandwritingWindow()`，真机发现那个窗口
 * 的尺寸**由系统限死**：`dumpsys window` 里 `Requested w=1497 h=2136`、`frame=[851,0][2348,2136]`，
 * 而屏幕是 3200 宽 —— 于是 `screenX > 1497` 的笔迹被裁掉、窗口内还整体偏右 851px。
 * `setStylusHandwritingRegion(整屏)` 与改窗口 `LayoutParams` 都压不住。
 *
 * 好在我们**本来就在** `onStylusHandwritingMotionEvent` 里拿到每一笔（识别就靠它），
 * 所以直接把这些点喂给自建的全屏悬浮窗画布即可，完全不必依赖框架把事件回放给手写窗口里的 View。
 * 只负责让随手写跑起来 + 识别上屏 + 笔势/光标。
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

    // ---- 注意：模块**不画任何墨迹** ----
    // 三条路都试过，都不成立：① 往框架手写窗口里放自绘视图——窗口尺寸被系统限死(1497/3200)，
    // 笔迹被裁且偏；② 自建全屏悬浮窗——窗口背景取主题 windowBackground，直接全屏白；
    // ③ 系统窗口 + 撑满——白屏没了但字迹也不显示。
    // 结论：墨迹交回系统自己的实现，模块只负责「让随手写跑起来 + 识别上屏 + 笔势/光标」。

    // ---- 光标：应用回传的 CursorAnchorInfo（"点一下搬光标"要用）----
    @Volatile private var cursorInfo: CursorAnchorInfo? = null

    // ---- 点击判定（笔尖轻点＝搬光标，不是写字）----
    @Volatile private var downTime = 0L
    @Volatile private var downX = 0f
    @Volatile private var downY = 0f
    @Volatile private var movedFar = false

    /** 几何诊断每个会话只落一次盘。 */
    @Volatile private var geomDumped = false

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
        hookCursorAnchor()
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

    /**
     * 缓存应用回传的 `CursorAnchorInfo`。
     *
     * 它是"点一下搬光标"的唯一依据：里面有**可见字符的边界框**（索引 0..n-1）
     * 和当前插入符位置（`getInsertionMarkerHorizontal/Baseline`）。
     * 要收到它得先在会话里 `requestCursorUpdates(CURSOR_UPDATE_MONITOR)`。
     */
    private fun hookCursorAnchor() {
        try {
            val m = InputMethodService::class.java.getDeclaredMethod(
                "onUpdateCursorAnchorInfo", CursorAnchorInfo::class.java,
            )
            module.hook(m)
                .setId("stylus_ime_cursor_anchor")
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept { chain ->
                    if (isTarget(chain.thisObject)) {
                        cursorInfo = chain.getArg(0) as? CursorAnchorInfo
                    }
                    chain.proceed()
                }
        } catch (t: Throwable) {
            L.e("event=stylus_hook_cursor_anchor_failed", t)
        }
    }

    // ------------------------------------------------------------------ 笔迹

    private fun onStylusMotion(ime: InputMethodService, ev: MotionEvent) {
        val action = ev.actionMasked
        // **屏幕坐标**：自建悬浮层是全屏、原点 (0,0)，所以屏幕坐标就是画布的局部坐标。
        // （框架手写窗口的坐标不能用：它被限成一条、原点也不在 0,0。）
        val x = ev.rawX
        val y = ev.rawY
        val time = ev.eventTime

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downTime = time
                downX = x
                downY = y
                movedFar = false
                dumpGeomOnce(ime, x, y)
            }

            MotionEvent.ACTION_MOVE -> if (!movedFar &&
                (kotlin.math.abs(x - downX) > TAP_SLOP || kotlin.math.abs(y - downY) > TAP_SLOP)
            ) {
                movedFar = true
            }
        }

        // ---- ① 先用笔**点一下**：这不算笔迹（不画、不识别、也不喂手写引擎），只搬光标 ----
        if (action == MotionEvent.ACTION_UP && !movedFar && time - downTime <= TAP_MS) {
            synchronized(stateLock) {
                strokes.clear()
                current = null
            }
            // 点击＝正常点击：直接结束手写会话，把笔交回系统/应用自己处理。
            // 不自己搬光标、也不做任何"识别成点"的事。
            runCatching { ime.finishStylusHandwriting() }
            return
        }

        synchronized(stateLock) {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    current = ArrayList<PencilEngine.Pt>(64).also {
                        it.add(PencilEngine.Pt(x, y, ACTION_DOWN, time))
                    }
                    // 每次落笔重新探测：小爱可能在这期间切到了手写键盘
                    usingHcr = hcrEngineAvailable(ime)
                }

                MotionEvent.ACTION_MOVE -> current?.add(PencilEngine.Pt(x, y, ACTION_MOVE, time))

                MotionEvent.ACTION_UP -> {
                    current?.let {
                        it.add(PencilEngine.Pt(x, y, ACTION_UP, time))
                        strokes.add(it)
                    }
                    current = null
                }

                MotionEvent.ACTION_CANCEL -> current = null
            }
        }

        // 笔势**故意不放在这里**：早期版本在每次抬笔时都调 GestureFacade，
        // 而它构造时会给全局 P2PManager 注册 MotionPoint 解析器，与文字识别共用引擎状态 ——
        // 真机表现就是"一开始识别很准，后来越改越差"。现在只在**文字没认出来**时才试笔势，
        // 见 recognizeAndCommit() 里的 maybeGesture()。

        // ① 小爱自己的讯飞 HCR：只有它当前就是手写引擎时才走
        if (usingHcr) {
            runCatching { hcrFeed?.invoke(null, ime, x.toInt(), y.toInt(), action) }
                .onFailure { L.w("event=stylus_hcr_feed_failed msg=${it.message}") }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                runCatching { hcrLift?.invoke(null, ime) }
                synchronized(stateLock) { strokes.clear() }   // HCR 自己上屏，别重复处理
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

    /** 小爱当前是否挂着讯飞手写引擎（`v4.x.g()` 非空表示是 `r9.j`）。 */
    private fun hcrEngineAvailable(ime: InputMethodService): Boolean =
        runCatching { hcrEngine?.invoke(null, ime) != null }.getOrDefault(false)

    /** 后台线程：把攒下的笔迹交给识别引擎，成功后回主线程上屏。 */
    private fun recognizeAndCommit() {
        val snapshot: List<List<PencilEngine.Pt>>
        synchronized(stateLock) {
            if (strokes.isEmpty()) return
            snapshot = strokes.map { ArrayList(it) }
            strokes.clear()
        }
        val ime = sessionIme ?: return
        val eng = ensureEngine(ime) ?: return
        val text = eng.recognize(normalize(snapshot))
        if (text.isNullOrEmpty()) {
            L.i("event=stylus_recognize_empty strokes=${snapshot.size}")
            return
        }
        L.i("event=stylus_recognize_ok strokes=${snapshot.size} text=$text")
        mainHandler.post {
            runCatching { ime.currentInputConnection?.commitText(text, 1) }
                .onFailure { L.w("event=stylus_commit_failed msg=${it.message}") }
        }
    }

    // ------------------------------------------------------------------ 会话

    private fun beginSession(ime: InputMethodService) {
        sessionIme = ime
        // 0) 会话空闲超时拉到框架上限：默认很短，写着写着会"断掉"（会话被结束、手写窗口收起）。
        //    搜狗那边的 doSetStylusHandwritingSessionTimeout 干的是同一件事。
        runCatching {
            ime.setStylusHandwritingSessionTimeout(
                InputMethodService.getStylusHandwritingIdleTimeoutMax()
            )
        }.onFailure { L.w("event=stylus_timeout_failed msg=${it.message}") }

        // 1) 收起虚拟键盘：手写时不要让键盘挡着（按钮改由悬浮工具条提供）
        runCatching { ime.requestHideSelf(0) }
            .onFailure { L.w("event=stylus_hide_self_failed msg=${it.message}") }

        // 1.5) 打开光标信息回传：应用会把可见字符框与插入符位置回传（"点一下搬光标"要用）
        runCatching { ime.currentInputConnection?.requestCursorUpdates(CURSOR_UPDATE_MONITOR) }
            .onFailure { L.w("event=stylus_cursor_req_failed msg=${it.message}") }

        // 2) 自建悬浮层（全屏画布 + 工具条）；会话开始时清掉上一轮的残留
        geomDumped = false

        synchronized(stateLock) {
            strokes.clear()
            current = null
        }
        L.i("event=stylus_session_begin")
    }

    private fun endSession(ime: InputMethodService) {
        workerHandler?.removeCallbacks(recognizeTask)
        runCatching { hcrClear?.invoke(null, ime) }
        synchronized(stateLock) {
            strokes.clear()
            current = null
        }
        usingHcr = false
        L.i("event=stylus_session_end")
    }

    // ------------------------------------------------------------------ 扩展能力



    /**
     * 把一批笔迹整体平移到"包围盒左上角 = (0,0)"。
     *
     * **这是识别率的关键**：早期版本喂给笔引擎的是框架手写窗口里的**局部坐标**
     * （0..1497，正好贴着书写区域左上角），识别很准；后来为了自绘墨迹改成屏幕坐标
     * （`rawX/rawY`，可能从 (1000, 800) 这种位置开始），成功率就明显掉下来 ——
     * 引擎的输入是按书写区域预期的，整体偏移会让笔画落到它不认识的盒子里。
     * 平移不改形状，只把位置归零，所以对识别只有好处。
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
     * 懒开系统笔引擎。`InputMethodService` 本身就是 `Context`（Service → ContextWrapper），
     * 所以直接把输入法实例当 Context 用即可。
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

    /**
     * 几何诊断：每个会话第一次落笔时把坐标与悬浮层尺寸落盘。
     *
     * 本机 logcat 三个常规缓冲区全空（见 [L]），"画哪儿去了"只能靠文件取证 ——
     * 上一轮就是靠它定位到框架手写窗口只有 1497 宽。root 侧 cat 即可：
     * `/data/data/com.xiaomi.type/files/stylus_geom.txt`
     */
    private fun dumpGeomOnce(ime: InputMethodService, x: Float, y: Float) {
        if (geomDumped) return
        geomDumped = true
        val dm = ime.resources.displayMetrics
        runCatching {
            File(ime.filesDir, "stylus_geom.txt").writeText(
                "event   x=$x y=$y（屏幕坐标）\n" +
                    "screen  ${dm.widthPixels}x${dm.heightPixels} density=${dm.density}\n"
            )
        }.onFailure { L.e("event=stylus_geom_dump_failed", it) }
    }

    private companion object {
        const val CLS_MI_IME = "com.mi.ime.MiInputMethodService"

        // MotionEvent 的动作码，直接对齐小爱 HCR 的 action 语义
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2

        /** AOSP `InputConnection.CURSOR_UPDATE_MONITOR`（公开 SDK 未导出，故写字面量）。 */
        const val CURSOR_UPDATE_MONITOR = 1

        /** AOSP `HandwritingGesture.GESTURE_SUCCESS`（同上，公开 SDK 未导出）。 */
        const val GESTURE_SUCCESS = 0

        /** 点击判定：位移阈值（px），超过就算"在写字"而不是"点一下"。 */
        const val TAP_SLOP = 16f

        /** 点击判定：时长阈值（ms）。 */
        const val TAP_MS = 300L
    }
}
