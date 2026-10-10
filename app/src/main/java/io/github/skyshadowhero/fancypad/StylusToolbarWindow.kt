package io.github.skyshadowhero.fancypad

import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 手写工具条：**挂在框架那块手写窗口里**当子 View，不新开窗口。
 *
 * ## 为什么不能自己开窗口（真机踩过，代价很大）
 *
 * 最早我用 `TYPE_INPUT_METHOD` + 输入法自己的 window token 单开了一个小窗口。结果
 * **一开工具条手写就整个废掉**：笔写不了、工具条也不出现，还留下僵尸的
 * `INTERCEPTS_STYLUS` 拦截面把笔的事件全吃掉。
 *
 * 原因：会话里我们 `requestHideSelf(0)` 把输入法窗口藏起来，而那个共用 token 的窗口
 * 会让这个 token 重新“可见” —— IMMS 对手写会话有“IME 窗口一变可见就中止”的逻辑，
 * 于是 `startHandwritingSession` 失败，会话根本建不起来。
 *
 * 换窗口类型也走不通：输入法**没有** `SYSTEM_ALERT_WINDOW` / `INTERNAL_SYSTEM_WINDOW`
 *（`granted=0`，实机查过），悬浮窗与输入法弹窗都用不了。
 *
 * 所以：塞进手写窗口的容器。它跟着会话出现/消失，**完全不碰 IME 的窗口状态**。
 *
 * ## 代价（说清楚）
 *
 * 要能点按钮，就得让手写窗口接受触摸（它本来是 `NOT_TOUCHABLE`）。所以工具条在的时候，
 * **手指触摸会被这块全屏窗口吃掉**；笔不受影响（笔走手写会话通道）。工具条一撤，
 * 立刻把 `NOT_TOUCHABLE` 还回去 —— 这也是它只在会话期间存在的原因。
 *
 * ## Compose 怎么进来
 *
 * 小爱的 `MiInputMethodService` 运行时实现了 `LifecycleOwner`（反编译核实过），
 * 直接当 `ComposeView` 宿主。这里刻意不用 `rememberSaveable` / `viewModel()`，
 * 因此也不需要 SavedStateRegistryOwner / ViewModelStoreOwner。
 */
internal class StylusToolbarWindow(private val ime: InputMethodService) {

    /** 宿主回调（实现在 [StylusImeHooks]）。 */
    interface Actions {
        fun onUndo()
        fun onRedo()
        fun onDelete()
        fun onSend()
        fun onPunctuation(text: String)
        fun onShowKeyboard()
    }

    private var root: View? = null
    private var lp: FrameLayout.LayoutParams? = null
    private var inkRef: StylusInkOverlay? = null
    private var added = false

    private val punctuationOpen = mutableStateOf(false)
    private var canUndoState: androidx.compose.runtime.State<Boolean> = mutableStateOf(false)
    private var canRedoState: androidx.compose.runtime.State<Boolean> = mutableStateOf(false)

    /** 合成笔事件的 downTime（点按一致性）。 */
    private var penDownTime = 0L

    /** 上次摆放的位置（同一个输入法进程内保留）。 */
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE

    /** [ink] 是手写窗口的持有者，工具条挂进它的容器。 */
    fun show(
        ink: StylusInkOverlay,
        actions: Actions,
        canUndo: androidx.compose.runtime.State<Boolean>,
        canRedo: androidx.compose.runtime.State<Boolean>,
    ) {
        if (added) return
        canUndoState = canUndo
        canRedoState = canRedo
        val container = ink.overlayContainer() ?: run {
            L.w("event=toolbar_no_container（手写窗口还没就绪）")
            return
        }
        val view = runCatching { buildView(actions) }.onFailure {
            L.e("event=toolbar_build_failed", it)
        }.getOrNull() ?: return

        val x = (if (lastX != Int.MIN_VALUE) lastX else screenW() / 6).coerceAtLeast(0)
        val y = (if (lastY != Int.MIN_VALUE) lastY else screenH() * 3 / 4).coerceAtLeast(0)
        val params = view.layoutParams as FrameLayout.LayoutParams
        params.leftMargin = x
        params.topMargin = y
        runCatching { container.addView(view, params) }.onFailure {
            L.e("event=toolbar_attach_failed", it)
            return
        }
        root = view
        lp = params
        inkRef = ink
        added = true
        punctuationOpen.value = false
        // ★ 绝不改手写窗口的触摸标志。
        //
        // 改过（撤掉 NOT_TOUCHABLE）→ 那块全屏窗口会把笔的后续事件当普通触摸接走，
        // 手写通道拿不到 UP，笔画永远完不成：真机表现就是"会话建立了（诊断里有 down、
        // req=true）但什么也写不出来"。所以工具条改用**笔**操作：笔事件本来就都经过我们
        //（onStylusMotion），由我们做命中判定并派发合成事件给视图，按钮照常响应。
        L.i("event=toolbar_shown x=$x y=$y")
    }

    fun hide() {
        if (!added) return
        root?.let { v -> runCatching { (v.parent as? ViewGroup)?.removeView(v) } }
        root = null
        lp = null
        added = false
        punctuationOpen.value = false
        inkRef = null
        L.i("event=toolbar_hidden")
    }

    val isShown: Boolean get() = added

    // ------------------------------------------------------------------ 视图

    private fun buildView(actions: Actions): View {
        val ctx = ime
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            )
        }

        // 拖拽把手（对齐搜狗那条小灰条）
        val handle = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply { bottomMargin = dp(2) }
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(0x33000000)
            }
        }
        wrapper.addView(handle)

        val compose = ComposeView(ctx).apply {
            // 小爱的 MiInputMethodService 运行时就是 LifecycleOwner；编译期框架类型不是，故运行时转换
            val owner = ime as? androidx.lifecycle.LifecycleOwner
            if (owner != null) setViewTreeLifecycleOwner(owner)
            else L.w("event=toolbar_no_lifecycle_owner")
            setContent {
                val controller = remember { ThemeController(ColorSchemeMode.System) }
                MiuixTheme(controller = controller) {
                    StylusToolbar(
                        canUndo = canUndoState,
                        canRedo = canRedoState,
                        punctuationOpen = punctuationOpen,
                        onTogglePunctuation = { punctuationOpen.value = !punctuationOpen.value },
                        onUndo = actions::onUndo,
                        onRedo = actions::onRedo,
                        onDelete = actions::onDelete,
                        onSend = actions::onSend,
                        onPunctuation = { mark ->
                            punctuationOpen.value = false
                            actions.onPunctuation(mark)
                        },
                        onShowKeyboard = actions::onShowKeyboard,
                    )
                }
            }
        }
        wrapper.addView(
            compose,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        // 拖动不在这里处理：手写窗口不可触摸，触摸监听收不到真实事件。
        // 拖拽由 [StylusImeHooks] 用笔坐标驱动（见 dispatchPen/moveBy 的说明）。
        return wrapper
    }

    // ------------------------------------------------------- 用笔操作（窗口不可触摸）

    /** 屏幕坐标是否落在工具条上。 */
    fun contains(screenX: Float, screenY: Float): Boolean {
        val v = root ?: return false
        if (v.width == 0 || v.height == 0) return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return screenX >= loc[0] && screenX < loc[0] + v.width &&
            screenY >= loc[1] && screenY < loc[1] + v.height
    }

    /**
     * 把一个笔事件合成后派发给工具条视图。
     *
     * 手写窗口是 `NOT_TOUCHABLE`，所以按钮收不到真实触摸；但笔事件都经过输入法
     *（[StylusImeHooks.onStylusMotion]），我们把坐标换算到视图本地空间再
     * `dispatchTouchEvent`，Compose 那边就当成一次正常点击/拖动。
     *
     * @return 视图是否消费了（消费=点在按钮上；false=落在空处，可由调用方当作拖动）
     */
    fun dispatchPen(action: Int, screenX: Float, screenY: Float): Boolean {
        val v = root ?: return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val x = screenX - loc[0]
        val y = screenY - loc[1]
        val now = android.os.SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) penDownTime = now
        val ev = MotionEvent.obtain(penDownTime, now, action, x, y, 0)
        ev.source = android.view.InputDevice.SOURCE_STYLUS
        return try {
            v.dispatchTouchEvent(ev)
        } catch (t: Throwable) {
            L.w("event=toolbar_dispatch_failed msg=${t.message}")
            false
        } finally {
            ev.recycle()
        }
    }

    /** 拖动：按屏幕坐标位移改子 View 的 margin。 */
    fun moveBy(dx: Float, dy: Float) {
        val p = lp ?: return
        p.leftMargin = (p.leftMargin + dx).toInt().coerceAtLeast(0)
        p.topMargin = (p.topMargin + dy).toInt().coerceAtLeast(0)
        root?.requestLayout()
    }

    /** 记下当前位置（拖动结束时调用）。 */
    fun rememberPosition() {
        val p = lp ?: return
        lastX = p.leftMargin
        lastY = p.topMargin
        L.i("event=toolbar_moved x=${p.leftMargin} y=${p.topMargin}")
    }

    private fun screenW() = ime.resources.displayMetrics.widthPixels
    private fun screenH() = ime.resources.displayMetrics.heightPixels
    private fun dp(v: Int) = (v * ime.resources.displayMetrics.density).toInt()
}
