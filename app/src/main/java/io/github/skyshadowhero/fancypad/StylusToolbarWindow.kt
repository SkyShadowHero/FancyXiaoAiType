package io.github.skyshadowhero.fancypad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 手写工具条（**第二排**）—— 独立小窗口，手指可点、可拖拽。
 *
 * ## 和小爱自带那条的关系
 *
 * 小爱的悬浮键盘自带一条可拖动工具栏（`movable_bar_*`，按钮：键盘 / 语音 / 撤回），
 * 那是它自己的 Compose 布局（`gb/y1`，1214 行、三套硬编码按钮组合）——**我们完全不动它**。
 * 这里做的是**第二排**：我们自己的按钮，只在随手写会话里出现。
 *
 * ## 为什么必须自建窗口，以及 token 的关键区别
 *
 * 前两版都失败过，原因不同、都要避开：
 *
 * 1. **不能复用输入法主窗口的 token**。最早我用
 *    `ime.window.window.attributes.token`，于是那个 token 重新"可见" → IMMS 对手写会话有
 *    "IME 窗口一变可见就中止"的逻辑 → 会话建不起来（真机 `req=false`），还留下
 *    `INTERCEPTS_STYLUS` 僵尸拦截面把笔吃掉。
 *    现在：**`token` 留空**，让 WMS 为这个窗口**新建一个**（`TYPE_INPUT_METHOD` 对当前
 *    输入法是允许的；小爱自己的浮窗也是同类做法，见 `b3/t.java` 的构造）。
 * 2. **绝不能改手写窗口的 `FLAG_NOT_TOUCHABLE`**。撤掉它会让那块全屏窗口把笔的后续事件
 *    当普通触摸接走，笔画永远完不成（真机：会话建立、`down req=true`，然后什么都没有）。
 *    现在：手写窗口一个标志都不碰；这条工具栏是**独立窗口**，自己可触摸、手指可点。
 *
 * ## 时机
 *
 * 由 [StylusImeHooks] 在**第一个真实笔事件到达之后**才创建（那个事件证明会话已经建立），
 * 从而避开 IMMS 那个只有 200ms 的手势窗口 —— 在窗口里同步建 Compose 视图（首次组合 +
 * 主题/字体加载是几百毫秒级）会把那个窗口拖过去。
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

    private var wm: WindowManager? = null
    private var root: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var added = false

    private val punctuationOpen = mutableStateOf(false)
    private var canUndoState: State<Boolean> = mutableStateOf(false)
    private var canRedoState: State<Boolean> = mutableStateOf(false)

    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0

    /** 上次摆放的位置（同一输入法进程内保留）。 */
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE

    fun show(actions: Actions, canUndo: State<Boolean>, canRedo: State<Boolean>) {
        if (added) return
        canUndoState = canUndo
        canRedoState = canRedo
        val w = ime.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        wm = w
        val view = runCatching { buildView(actions) }.onFailure {
            L.e("event=toolbar_build_failed", it)
        }.getOrNull() ?: return

        val params = baseParams()
        // 首选：token 留空（WMS 新建 token，绝不碰输入法主窗口的 token）
        val first = runCatching { w.addView(view, params) }
        if (first.isFailure) {
            L.w("event=toolbar_add_retry msg=${first.exceptionOrNull()?.message}")
            // 兜底：输入法弹窗类型 + 输入法窗口 token（IME 通常被允许建这类窗口）
            val ok = runCatching {
                val t = ime.window?.window?.attributes?.token
                if (t == null) throw IllegalStateException("no ime token")
                params.token = t
                params.type = WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG
                w.addView(view, params)
            }
            if (ok.isFailure) {
                L.e("event=toolbar_add_failed_both", ok.exceptionOrNull()!!)
                return
            }
        }
        root = view
        lp = params
        added = true
        punctuationOpen.value = false
        L.i(
            "event=toolbar_shown type=${params.type} ownToken=${params.token == null} " +
                "x=${params.x} y=${params.y}"
        )
    }

    fun hide() {
        if (!added) return
        val w = wm
        root?.let { v -> runCatching { w?.removeViewImmediate(v) } }
        root = null
        lp = null
        added = false
        punctuationOpen.value = false
        L.i("event=toolbar_hidden")
    }

    val isShown: Boolean get() = added

    // ------------------------------------------------------------------ 窗口

    private fun baseParams(): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_INPUT_METHOD,
            // 可点、不抢焦点；LAYOUT_NO_LIMITS 才能拖到屏幕边缘
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.START
        // ★ token 留空：让 WMS 为它新建一个，避免让输入法主窗口的 token 重新"可见"
        p.token = null
        p.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        runCatching { p.setFitInsetsTypes(0) }
        p.windowAnimations = 0
        // 默认浮在屏幕下方（作为小爱那条栏之外的第二排），拖动后会记住位置
        p.x = if (lastX != Int.MIN_VALUE) lastX else screenW() / 6
        p.y = if (lastY != Int.MIN_VALUE) lastY else screenH() * 4 / 5
        return p
    }

    private fun buildView(actions: Actions): View {
        val ctx = ime
        val wrapper = FrameLayout(ctx)
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // 拖拽把手
        column.addView(
            View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(4))
                    .apply { bottomMargin = dp(2) }
                background = GradientDrawable().apply {
                    cornerRadius = dp(2).toFloat()
                    setColor(0x33000000)
                }
            }
        )

        // 一排按钮（Miuix / Compose）
        column.addView(
            ComposeView(ctx).apply {
                // 小爱的 MiInputMethodService 运行时实现了 LifecycleOwner（反编译核实过）；
                // 编译期的框架 InputMethodService 不是，所以这里做运行时转换。
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
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        wrapper.addView(column)
        attachDrag(wrapper)
        return wrapper
    }

    /** 拖拽：改窗口的 x/y。按钮会先消费点击，只有落在空白/把手上才是拖动。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(view: View) {
        view.setOnTouchListener { _, ev ->
            val p = lp ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    downX = p.x
                    downY = p.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener false
                    p.x = (downX + (ev.rawX - downRawX)).toInt()
                    p.y = (downY + (ev.rawY - downRawY)).toInt()
                    root?.let { runCatching { wm?.updateViewLayout(it, p) } }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        lastX = p.x
                        lastY = p.y
                        L.i("event=toolbar_moved x=${p.x} y=${p.y}")
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun screenW() = ime.resources.displayMetrics.widthPixels
    private fun screenH() = ime.resources.displayMetrics.heightPixels
    private fun dp(v: Int) = (v * ime.resources.displayMetrics.density).toInt()
}
