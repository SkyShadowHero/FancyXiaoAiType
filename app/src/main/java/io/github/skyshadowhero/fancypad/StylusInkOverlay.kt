package io.github.skyshadowhero.fancypad

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 随手写笔迹层：把 [StylusInkView] 挂进**框架自己的手写窗口**。
 *
 * ## 为什么挂在框架的窗口里，而不是自建悬浮窗
 *
 * `InputMethodService.getStylusHandwritingWindow()` 返回的是 AOSP 为手写准备的
 * `InkWindow`（反编译见 `~/type/re/work/out/inkw/sources/android/inputmethodservice/InkWindow.java`）：
 * `TYPE_INPUT_METHOD`(2011)、**透明背景**、`FLAG_NOT_FOCUSABLE|FLAG_NOT_TOUCHABLE`
 * `|FLAG_LAYOUT_IN_SCREEN|FLAG_LAYOUT_NO_LIMITS`，token 就是 IME 自己的窗口 token。
 * 也就是说：申请窗口、拿 token、防触摸穿透这几件事系统已经做完了，直接用最稳；
 * 「自建全屏悬浮窗 → 全屏白」那条老路也不会遇到（窗口不是我们建的，也没有背景）。
 *
 * ## 区域大小是**内容说了算**
 *
 * 真机上 `dumpsys window` 打出来这个窗口是 `(wrapxwrap)`：
 *
 * ```
 * Window #21 ... ty=INPUT_METHOD fmt=TRANSPARENT
 *   fl=NOT_FOCUSABLE NOT_TOUCHABLE LAYOUT_IN_SCREEN LAYOUT_NO_LIMITS
 *   Requested w=1497 h=1084   frame=[851,526][2348,1610]
 * ```
 *
 * 也就是说**它不是满屏窗口**，而是按内容 wrap 出来的 —— 之前那个 1497 宽就是
 * 往里塞了一条 `MATCH_PARENT` 的带子被量出来的结果（高度也是带子高度）。
 * 既然大小由内容决定，那就由我们**显式**给尺寸：现在给的是**整屏**（屏幕宽 × 屏幕高），
 * 于是写在哪都有笔迹，也不再需要"区域跟着落笔点走"那套。
 *
 * ## 为什么是满屏
 *
 * 实测（`stylus_geom.txt` 里第一次落笔是 `raw=(630,230)`，而当时区域是
 * `[851,526][2348,1610]`）：**笔在区域外时事件照样收得到** —— 系统并不按区域裁剪事件，
 * 早先"只有屏幕中间一片有笔迹"纯粹是画布自己被窗口边界裁掉了。
 * 中间试过"满宽 + 跟着落笔点上下移动的横带"，但那会让人写着写着带子跳一下；
 * 现在直接铺满，行为最可预期。
 *
 * 注意：**窗口本身不是遮罩** —— 它的 flags 里带着 `NOT_TOUCHABLE`（见上面的 dumpsys），
 * 笔的事件穿透过去。真正会"咬住"触控笔的是 system_server 那块
 * `stylus-handwriting-event-receiver-0` surface，见 [StylusImeHooks] 里关于会话的说明。
 *
 * ## 坐标
 *
 * 喂给画布的点必须是**画布局部坐标**，所以对外提供两个偏移：
 * - [canvasOffset]：画布在屏幕上的位置 → 画线用；
 * - [windowOffset]：手写窗口内容区在屏幕上的位置 → 喂讯飞 HCR 用
 *   （引擎的写区域是按**手写窗口**坐标系设的，不是屏幕坐标）。
 *
 * 两者都在**落笔时**同步一次真实值（[sync]），之后整笔沿用缓存 ——
 * 每个 MOVE 都去问一次 View 的位置没必要（每秒上百次），而平移之后缓存会被
 * 立刻按目标值更新，本笔的墨迹不用等布局完成。
 */
internal class StylusInkOverlay {

    private var container: FrameLayout? = null
    private var inkView: StylusInkView? = null

    /** 已经挂进去的那个窗口。框架重建 `InkWindow` 之后要重新挂（见 [attach]）。 */
    private var attachedWindow: Window? = null
    private var windowManager: WindowManager? = null

    private val canvasLoc = IntArray(2)
    private val windowLoc = IntArray(2)
    private var regionW = 0
    private var regionH = 0
    private var synced = false

    val view: StylusInkView? get() = inkView

    val isAttached: Boolean get() = attachedWindow != null && inkView?.isAttachedToWindow == true

    /**
     * 把手写画布挂到框架的手写窗口上（每个会话开始时调一次；幂等）。
     *
     * 返回 false 只表示**这次没画布**（框架还没建手写窗口，或挂载被拒），
     * 识别链路完全不受影响 —— 笔迹显示是纯附加能力。
     */
    fun attach(ime: InputMethodService): Boolean {
        val win = runCatching { ime.stylusHandwritingWindow }.getOrNull()
        if (win == null) {
            L.w("event=stylus_ink_no_window（框架尚未创建手写窗口，本次不画笔迹）")
            return false
        }

        val iv = inkView ?: StylusInkView(ime).also { inkView = it }
        var cv = container
        if (cv == null) {
            cv = FrameLayout(ime)
            cv.setBackgroundColor(Color.TRANSPARENT)
            container = cv
        }

        // 显式给画布尺寸：窗口 wrap 内容，所以这里给多大，书写区就多大。
        // 现在给**整屏**（宽 × 高）：写哪儿都有笔迹，不用再跟着落笔点挪。
        val dm = ime.resources.displayMetrics
        val bandW = dm.widthPixels.coerceAtLeast(1)
        val bandH = dm.heightPixels.coerceAtLeast(1)
        val want = FrameLayout.LayoutParams(bandW, bandH, Gravity.TOP or Gravity.START)
        val current = iv.layoutParams
        if (current == null) {
            cv.addView(iv, want)
            L.i("event=stylus_ink_container_created band=${bandW}x$bandH")
        } else if (current.width != bandW || current.height != bandH) {
            iv.layoutParams = want
            L.i("event=stylus_ink_resized band=${bandW}x$bandH")
        }

        // 只有当窗口换了（或第一次）才 setContentView：同一个窗口重复设同一个 View
        // 会让 PhoneWindow 先 removeAllViews 再加回去，白白触发一次整窗重排。
        if (attachedWindow !== win) {
            val ok = runCatching {
                win.setContentView(
                    cv,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }.onFailure { L.e("event=stylus_ink_attach_failed", it) }.isSuccess
            if (!ok) return false
            attachedWindow = win
            windowManager = runCatching {
                ime.getSystemService(InputMethodService.WINDOW_SERVICE) as? WindowManager
            }.getOrNull()
            L.i("event=stylus_ink_attached window=${win.javaClass.name} band=${bandW}x$bandH")
        }

        iv.applyInk(
            HookPrefs.stylusInkColor(),
            // 线宽是 px，直接用（裸 Canvas 的 strokeWidth 就是 px）
            HookPrefs.stylusInkWidthPx(),
        )
        iv.clear()
        synced = false
        return true
    }

    /**
     * 同步一次画布/窗口在屏幕上的真实位置与区域大小。
     *
     * 只在**落笔时**调（每个会话/每一笔一次）。上一笔可能做过的平移到这里已经布局完成，
     * 所以这一步同时也是对缓存的校正。
     */
    fun sync(): Boolean {
        val cv = container ?: return false
        val iv = inkView ?: return false
        if (!cv.isAttachedToWindow || cv.width <= 0 || cv.height <= 0) {
            synced = false
            return false
        }
        cv.getLocationOnScreen(windowLoc)
        iv.getLocationOnScreen(canvasLoc)
        regionW = cv.width
        regionH = cv.height
        synced = true
        return true
    }

    /** 清空墨迹。 */
    fun clear() {
        inkView?.clear()
    }

    /**
     * 画布在屏幕上的位置（画线用：屏幕坐标减掉它就是画布局部坐标）。
     * 还没同步过（未布局）时返回 null —— 此时画不了，调用方照旧只做识别。
     */
    fun canvasOffset(): IntArray? = if (synced) canvasLoc else null

    /**
     * 手写窗口内容区在屏幕上的位置（喂引擎用）。
     *
     * 容器的局部坐标就是**手写窗口坐标系**，而引擎的 `setWritingArea` 正是按那个坐标系设的；
     * 直接喂屏幕坐标会让整批笔迹相对写区域整体偏移 —— 实测那会让识别率明显下滑。
     */
    fun windowOffset(): IntArray? = if (synced) windowLoc else null

    /** 画布尺寸（诊断用）。 */
    fun canvasSize(): String {
        val v = inkView ?: return "none"
        return "${v.width}x${v.height}"
    }

    /** 书写区信息（诊断用）。 */
    fun regionInfo(): String =
        if (synced) "top=${windowLoc[1]} size=${regionW}x$regionH" else "未同步"
}
