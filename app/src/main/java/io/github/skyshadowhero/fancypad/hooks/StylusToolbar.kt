package io.github.skyshadowhero.fancypad.hooks

import io.github.skyshadowhero.fancypad.*

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.lang.reflect.Proxy
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 随手写操作面板 —— **一比一还原搜狗输入法的「随手写工具箱」**。
 *
 * 尺寸、配色、图标、拖动与按钮语义都取自搜狗（反编译资产见
 * `.workbuddy/ime-analysis/sogou/`）：
 *
 * ```
 * res/layout/iw.xml                       工具箱布局（com.sogou.xiaomi.stylus.d.a）
 * com/miui/ime/MiuiHandWritingIMEStylus   会话 / 按钮动作
 * com/miui/handwriting/view/keyboard/     面板本体（ImeMenuViewHolder / ShadowLayout / IconButton）
 * ```
 *
 * 面板是 194×116dp、圆角 18dp 的浮层：上面一条 40×4dp 拖动条，中间一排圆形图标键
 * （撤回 / 重做 / 空格 / 退格），下面一排（设置 / 发送 / 键盘）。按下时整块放大到 1.05、
 * 拖动条变蓝；松手落位并记住比例位置。
 *
 * ## 挂在哪儿 —— 输入法窗口的 decorView
 *
 * 和搜狗一样把浮层塞进输入法窗口：
 *
 * ```
 * ime.window.window.decorView.addView(overlay, MATCH_PARENT, MATCH_PARENT)
 * ```
 *
 * 输入法不退，面板就不退；框架对手写窗口（InkWindow）做的 hide/destroy 影响不到它。
 * 早先挂进 InkWindow 那版会出现「字迹没了 UI 也没了」和幽灵窗口。
 *
 * 浮层自己**不动窗口尺寸**：键盘的 View 只被置成 `INVISIBLE`（不是 GONE），
 * IME 窗口继续作为稳定宿主；随手写状态由 `StylusImeHooks.onComputeInsets` 上报零占用高度，
 * 应用可以延伸到底部。窗口本身是 TRANSPARENT 的，键盘不画了露出来的是应用自己的背景。
 *
 * ## 触摸区域：只有面板那一块吃事件
 *
 * 照抄搜狗 `ImeMenuViewHolder` 的 `OnComputeInternalInsetsListener`：
 * 把输入法窗口的可触摸区域**限定成面板矩形**（`TOUCHABLE_INSETS_REGION`）。于是
 * 面板以外的笔/手指**穿透给应用** —— 应用自己的 `HandwritingInitiator` 才能照常开手写会话。
 * 这一条是"笔能在任意位置落笔"的前提，缺了它整块窗口会把落笔吃掉。
 *
 * ## 拖动
 *
 * 判定**只有一套**（手指和笔共用 [onPointer]）：DOWN 记起点 → MOVE 超过 `touchSlop` 才拖 →
 * UP 落位存盘。和搜狗一致：**从面板空白处（拖动条 / 行间隙 / 底部留白）起拖**；
 * 按钮上按下算点按钮，不拖动（免得连点退格变成拖面板）。
 */
class StylusToolbar {

    enum class Action { UNDO, REDO, SPACE, BACKSPACE, SETTINGS, SEND, KEYBOARD }

    /** 按钮回调。 */
    var onAction: ((Action) -> Unit)? = null

    private var ime: InputMethodService? = null

    private var root: FrameLayout? = null
    private var panel: ShadowPanel? = null
    private var handle: ImageView? = null
    private var sendText: TextView? = null
    private var sendImage: ImageView? = null
    private var undoButton: ImageView? = null
    private var redoButton: ImageView? = null
    private var palette: StylusToolbarPalette? = null

    private val buttons = LinkedHashMap<Action, View>()
    private val feedbackLayers = IdentityHashMap<View, StylusToolbarFeedbackDrawable>()
    private val feedbackAnimators = IdentityHashMap<View, ValueAnimator>()

    /** 被我们藏起来的输入法 View（退出随手写时按原样恢复）。 */
    private val hiddenChildren = ArrayList<View>()

    /**
     * 小爱悬浮键盘的工具栏不在 IME decor 里，而是单独的 Compose Popup 窗口（`w2.p`）。
     * [hideKeyboardContent] 遍历不到它，因此由输入法 Hook 在构造时登记，随手写期间单独隐藏。
     */
    private data class KeyboardPopupState(
        val visibility: Int,
        var wasNotTouchable: Boolean?,
    )

    private val keyboardPopupWindows = java.util.Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val observedKeyboardPopupWindows = java.util.Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val hiddenKeyboardPopupWindows = IdentityHashMap<View, KeyboardPopupState>()
    private var suppressKeyboardPopups = false

    /** 外接键盘时系统会给 IME 主窗口加 NOT_TOUCHABLE；随手写面板显示期间要暂时清掉。 */
    private var stylusImeWindow: Window? = null
    private var imeWindowWasNotTouchable: Boolean? = null

    private var canUndo = false
    private var canRedo = false
    private var action = 0

    // ---- 指针 ----
    private var downRawX = 0f
    private var downRawY = 0f
    private var panelDownLeft = 0
    private var panelDownTop = 0
    private var dragging = false
    private var pressedAction: Action? = null
    private var pressedInside = false
    private var hoveredAction: Action? = null
    private var hoverRawX = 0f
    private var hoverRawY = 0f
    private var pointerActive = false

    /** 拖动时临时摘掉，避免系统继续用按下前的旧面板矩形做窗口命中。 */
    private var insetsListenerInstalled = false
    private var insetsObserver: ViewTreeObserver? = null

    // ---- 退格长按连发（搜狗：60ms 起，每发减 1ms，最快 5ms） ----
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatDelay = REPEAT_START_MS
    private var repeating = false
    private var repeatStarted = false

    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (!repeating) return
            onAction?.invoke(Action.BACKSPACE)
            repeatDelay = (repeatDelay - REPEAT_STEP_MS).coerceAtLeast(REPEAT_MIN_MS)
            repeatHandler.postDelayed(this, repeatDelay.toLong())
        }
    }

    private val longPressRunnable = Runnable {
        if (pressedAction != Action.BACKSPACE || !pressedInside) return@Runnable
        repeating = true
        repeatStarted = true
        repeatDelay = REPEAT_START_MS
        onAction?.invoke(Action.BACKSPACE)
        repeatHandler.postDelayed(repeatRunnable, REPEAT_START_MS.toLong())
    }

    // ---- 窗口可触摸区域（ViewTreeObserver.InternalInsetsInfo 是 @hide，走反射）----
    private val insetsInfoClass: Class<*> by lazy {
        Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
    }
    private val insetsListenerClass: Class<*> by lazy {
        Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
    }
    private val insetsListener: Any by lazy {
        Proxy.newProxyInstance(
            StylusToolbar::class.java.classLoader,
            arrayOf(insetsListenerClass),
        ) { proxy, method, args ->
            when (method.name) {
                "onComputeInternalInsets" -> {
                    runCatching { applyTouchRegion(args?.getOrNull(0)) }
                    null
                }
                "equals" -> args?.getOrNull(0) === proxy
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "FancyPadStylusInsets"
                else -> null
            }
        }
    }

    private fun applyTouchRegion(info: Any?) {
        if (info == null) return
        val r = root ?: return
        // panel 始终是 root 的子 View；仅检查 panel.parent 会在 root 已从 IME 窗口移除后
        // 继续上报旧矩形，导致普通键盘只有原工具栏区域能点击。
        if (!r.isAttachedToWindow || r.parent == null) return
        val p = panel ?: return
        if (p.width <= 0) return
        runCatching {
            val region = insetsInfoClass.getField("touchableRegion").get(info) as? Region ?: return
            val left = p.left + p.translationX.roundToInt()
            val top = p.top + p.translationY.roundToInt()
            region.set(left, top, left + p.width, top + p.height)
            insetsInfoClass.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
                .invoke(info, TOUCHABLE_INSETS_REGION)
        }
    }

    // ------------------------------------------------------------------ 对外

    val isShowing: Boolean
        get() = root?.isAttachedToWindow == true

    fun show(ime: InputMethodService, editorInfo: android.view.inputmethod.EditorInfo?) {
        this.ime = ime
        updateForEditorInfo(editorInfo)
        val decor = runCatching { ime.window?.window?.decorView as? ViewGroup }.getOrNull()
        if (decor == null) {
            L.w("event=stylus_toolbar_no_decor")
            return
        }
        val ctx = decor.context
        val nextPalette = StylusToolbarPalette.from(ctx)
        if (panel == null) {
            build(ctx, nextPalette)
        } else if (palette != nextPalette) {
            applyPalette(ctx, nextPalette)
        }
        val r = root ?: return
        updateForEditorInfo(editorInfo)
        suppressKeyboardPopups = true
        hideKeyboardPopupWindows()
        if (r.parent !== decor) {
            uninstallInsetsListener(r)
            runCatching { (r.parent as? ViewGroup)?.removeView(r) }
            hideKeyboardContent(decor, r)
            prepareShowAnimation(r)
            val added = runCatching {
                decor.addView(r, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                true
            }.onFailure { L.e("event=stylus_toolbar_add_failed", it) }.getOrDefault(false)
            if (!added) {
                restoreKeyboardContent()
                suppressKeyboardPopups = false
                restoreKeyboardPopupWindows()
                return
            }
            r.post {
                place()
                playShowAnimation(r)
            }
        } else {
            hideKeyboardContent(decor, r)
        }
        installInsetsListener(r)
        makeImeWindowTouchable(ime, r)
        L.i("event=stylus_toolbar_shown")
    }

    fun hide(forceImeTouchable: Boolean = false) {
        repeatHandler.removeCallbacksAndMessages(null)
        repeating = false
        repeatStarted = false
        dragging = false
        pressedAction?.let { setActionPressedFlag(it, false) }
        pressedAction = null
        pressedInside = false
        hoveredAction = null
        pointerActive = false
        resetButtonFeedback()
        val r = root
        if (r != null) {
            r.animate().cancel()
            panel?.animate()?.cancel()
            r.alpha = 1f
            panel?.scaleX = 1f
            panel?.scaleY = 1f
            uninstallInsetsListener(r)
            runCatching { (r.parent as? ViewGroup)?.removeView(r) }
        }
        restoreKeyboardContent()
        suppressKeyboardPopups = false
        restoreKeyboardPopupWindows()
        // 切回软键盘时必须明确清掉 NOT_TOUCHABLE；普通退出（例如连接实体键盘）则仍按
        // 进入前状态恢复，避免改变系统原本的窗口策略。
        restoreImeWindowTouchability(forceTouchable = forceImeTouchable)
        requestImeInsetsRefresh()
        L.i("event=stylus_toolbar_hidden")
    }

    /** 换输入框 / 键盘重建之后重新藏一次（新挂上来的 View 是可见的）。 */
    fun refreshKeyboardContent() {
        val r = root ?: return
        val decor = runCatching { ime?.window?.window?.decorView as? ViewGroup }.getOrNull() ?: return
        if (r.parent === decor) {
            hideKeyboardContent(decor, r)
            hideKeyboardPopupWindows()
            ime?.let { makeImeWindowTouchable(it, r) }
        }
    }

    /** 由 `StylusImeHooks` 的 Compose Popup 构造 Hook 调用。 */
    fun trackKeyboardPopupWindow(view: View) {
        keyboardPopupWindows.add(view)
        if (observedKeyboardPopupWindows.add(view)) {
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    if (suppressKeyboardPopups) hideKeyboardPopupWindow(v)
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
        if (suppressKeyboardPopups) hideKeyboardPopupWindow(view)
    }

    /** 笔是否落在面板上（屏幕坐标）。 */
    fun hitTest(screenX: Float, screenY: Float): Boolean {
        val p = panel ?: return false
        if (!isShowing || p.width <= 0) return false
        val loc = IntArray(2)
        p.getLocationOnScreen(loc)
        return screenX >= loc[0] && screenX < loc[0] + p.width &&
            screenY >= loc[1] && screenY < loc[1] + p.height
    }

    /** 面板矩形（屏幕坐标），诊断用。 */
    fun panelScreenRect(): Rect? {
        val p = panel ?: return null
        if (!isShowing || p.width <= 0) return null
        val loc = IntArray(2)
        p.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + p.width, loc[1] + p.height)
    }

    /**
     * 把一笔**笔事件**交给面板（手写会话期间笔事件不经过 View 树，必须自己转一份）。
     *
     * @return true = 这一笔属于面板（调用方要整笔剔除，不进墨迹/识别链路）。
     */
    fun dispatchPen(ev: MotionEvent): Boolean {
        val p = panel ?: return false
        if (!isShowing || p.width <= 0) return false
        if (!pointerActive && ev.actionMasked != MotionEvent.ACTION_DOWN) return true
        onPointer(ev, p)
        return true
    }

    val penActive: Boolean
        get() = pointerActive

    fun setCanUndo(value: Boolean) {
        canUndo = value
        val button = undoButton ?: return
        button.isEnabled = value
        if (!value) button.isPressed = false
        val colors = palette ?: StylusToolbarPalette.from(button.context)
        button.setImageDrawable(StylusToolbarDrawables.chip(button.context, StylusToolbarIcon.UNDO, value, colors))
        if (!value) clearActionState(Action.UNDO)
        updateActionFeedback(Action.UNDO, immediate = true)
    }

    fun setCanRedo(value: Boolean) {
        canRedo = value
        val button = redoButton ?: return
        button.isEnabled = value
        if (!value) button.isPressed = false
        val colors = palette ?: StylusToolbarPalette.from(button.context)
        button.setImageDrawable(StylusToolbarDrawables.chip(button.context, StylusToolbarIcon.REDO, value, colors))
        if (!value) clearActionState(Action.REDO)
        updateActionFeedback(Action.REDO, immediate = true)
    }

    /** 按输入框的 imeOptions 换发送键（搜狗：搜索 / 发送 / 确定 / 下一步 / 完成，无 action 时显示回车图标）。 */
    fun updateForEditorInfo(info: android.view.inputmethod.EditorInfo?) {
        action = (info?.imeOptions ?: 0) and android.view.inputmethod.EditorInfo.IME_MASK_ACTION
        val label = labelFor(action)
        resetViewFeedback(sendText)
        resetViewFeedback(sendImage)
        if (label == null) {
            sendText?.visibility = View.GONE
            sendImage?.visibility = View.VISIBLE
        } else {
            sendImage?.visibility = View.GONE
            sendText?.visibility = View.VISIBLE
            sendText?.text = label
        }
        updateActionFeedback(Action.SEND, immediate = true)
    }

    private fun labelFor(action: Int): String? = when (action) {
        android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH -> "搜索"
        android.view.inputmethod.EditorInfo.IME_ACTION_GO -> "确定"
        android.view.inputmethod.EditorInfo.IME_ACTION_SEND -> "发送"
        android.view.inputmethod.EditorInfo.IME_ACTION_NEXT -> "下一步"
        android.view.inputmethod.EditorInfo.IME_ACTION_DONE -> "完成"
        android.view.inputmethod.EditorInfo.IME_ACTION_PREVIOUS -> "上一项"
        else -> null
    }

    // ------------------------------------------------------------------ 视图（搜狗 iw.xml）

    private fun build(ctx: Context, colors: StylusToolbarPalette) {
        val dp = { v: Float -> (v * ctx.resources.displayMetrics.density).roundToInt() }
        palette = colors
        resetButtonFeedback()
        buttons.clear()
        feedbackLayers.clear()
        feedbackAnimators.clear()

        val frame = FrameLayout(ctx).apply {
            isClickable = false
            isFocusable = false
            clipChildren = false
            clipToPadding = false
        }

        val p = ShadowPanel(ctx, colors).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            layoutParams = FrameLayout.LayoutParams(dp(194f), dp(116f)).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            setOnTouchListener { _, ev ->
                onPointer(ev, this@apply)
                true
            }
            setOnHoverListener { _, ev ->
                onHover(ev)
                true
            }
            isClickable = true
            isFocusable = false
        }

        // 拖动条：22dp 高，中间一根 40×4dp 的圆条
        val h = ImageView(ctx).apply {
            setImageDrawable(StylusToolbarDrawables.handle(ctx, pressed = false, colors))
            scaleType = ImageView.ScaleType.CENTER
            isClickable = false
        }
        p.addView(h, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22f)))
        handle = h

        // 第一排：撤回 / 重做 / 空格 / 退格（38dp 圆形图标键）
        val row1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        p.addView(row1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38f)))
        undoButton = addChip(row1, ctx, Action.UNDO, StylusToolbarIcon.UNDO, enabled = false, dp(12f), dp)
        redoButton = addChip(row1, ctx, Action.REDO, StylusToolbarIcon.REDO, enabled = false, dp(6f), dp)
        addChip(row1, ctx, Action.SPACE, StylusToolbarIcon.SPACE, enabled = true, dp(6f), dp)
        addChip(row1, ctx, Action.BACKSPACE, StylusToolbarIcon.BACKSPACE, enabled = true, dp(6f), dp)

        // 第二排：设置 / 发送 / 键盘
        val row2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        p.addView(
            row2,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32f)).apply {
                topMargin = dp(13f)
            },
        )

        val gear = addIcon(row2, ctx, Action.SETTINGS, StylusToolbarIcon.GEAR, dp(15.63f))
        gear.layoutParams = LinearLayout.LayoutParams(dp(32f), dp(32f)).apply {
            leftMargin = dp(15.63f)
        }
        gear.setPadding(dp(8f), dp(8f), dp(8f), dp(8f))

        val send = TextView(ctx).apply {
            text = "发送"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(colors.sendText)
            background = sendPill(colors, dp)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            isClickable = false
        }
        bindFeedback(send)
        row2.addView(
            send,
            LinearLayout.LayoutParams(dp(86f), dp(32f)).apply { leftMargin = dp(6.37f) },
        )
        sendText = send

        val enterIcon = ImageView(ctx).apply {
            setImageDrawable(StylusToolbarDrawables.enter(ctx, colors))
            visibility = View.GONE
            isClickable = false
        }
        bindFeedback(enterIcon)
        row2.addView(
            enterIcon,
            LinearLayout.LayoutParams(dp(86f), dp(32f)).apply { leftMargin = dp(6.37f) },
        )
        sendImage = enterIcon

        val keyboard = addIcon(row2, ctx, Action.KEYBOARD, StylusToolbarIcon.KEYBOARD, dp(6f))
        keyboard.layoutParams = LinearLayout.LayoutParams(dp(33.4f), dp(29.4f)).apply {
            leftMargin = dp(6f)
        }
        keyboard.setPadding(dp(8f), dp(8f), dp(8f), dp(8f))

        frame.addView(p)
        root = frame
        panel = p

        setCanUndo(canUndo)
        setCanRedo(canRedo)
    }

    private fun addChip(
        parent: LinearLayout,
        ctx: Context,
        action: Action,
        icon: StylusToolbarIcon,
        enabled: Boolean,
        startMargin: Int,
        dp: (Float) -> Int,
    ): ImageView {
        val v = ImageView(ctx).apply {
            setImageDrawable(
                StylusToolbarDrawables.chip(
                    ctx,
                    icon,
                    enabled,
                    requireNotNull(palette),
                ),
            )
            isClickable = false
            scaleType = ImageView.ScaleType.FIT_XY
        }
        bindFeedback(v)
        // 38×38dp：搜狗把圆形键底和图标画在同一张 vector 里
        parent.addView(
            v,
            LinearLayout.LayoutParams(dp(38f), dp(38f)).apply { leftMargin = startMargin },
        )
        buttons[action] = v
        return v
    }

    private fun addIcon(
        parent: LinearLayout,
        ctx: Context,
        action: Action,
        icon: StylusToolbarIcon,
        startMargin: Int,
    ): ImageView {
        val v = ImageView(ctx).apply {
            setImageDrawable(StylusToolbarDrawables.icon(ctx, icon, requireNotNull(palette)))
            isClickable = false
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        bindFeedback(v)
        parent.addView(v, LinearLayout.LayoutParams(0, 0).apply { leftMargin = startMargin })
        buttons[action] = v
        return v
    }

    private fun sendPill(colors: StylusToolbarPalette, dp: (Float) -> Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16f).toFloat()
            setColor(colors.send)
        }

    /** 搜狗入场：根视图淡入，面板从 0.9 倍回到 1 倍。 */
    private fun prepareShowAnimation(r: View) {
        r.animate().cancel()
        panel?.animate()?.cancel()
        r.alpha = 0f
        panel?.apply {
            scaleX = SHOW_START_SCALE
            scaleY = SHOW_START_SCALE
        }
    }

    private fun playShowAnimation(r: View) {
        val p = panel ?: return
        if (r.parent == null) return
        p.pivotX = p.width / 2f
        p.pivotY = p.height / 2f
        val interpolator = DecelerateInterpolator()
        r.animate()
            .alpha(1f)
            .setDuration(SHOW_ANIMATION_MS)
            .setInterpolator(interpolator)
            .start()
        p.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(SHOW_ANIMATION_MS)
            .setInterpolator(interpolator)
            .start()
    }

    /** 主题昼夜切换时无需重建窗口，原地刷新所有代码绘制的颜色与图标。 */
    private fun applyPalette(ctx: Context, colors: StylusToolbarPalette) {
        palette = colors
        panel?.setPalette(colors)
        setCanUndo(canUndo)
        setCanRedo(canRedo)
        (buttons[Action.SPACE] as? ImageView)?.setImageDrawable(
            StylusToolbarDrawables.chip(ctx, StylusToolbarIcon.SPACE, enabled = true, colors),
        )
        (buttons[Action.BACKSPACE] as? ImageView)?.setImageDrawable(
            StylusToolbarDrawables.chip(ctx, StylusToolbarIcon.BACKSPACE, enabled = true, colors),
        )
        (buttons[Action.SETTINGS] as? ImageView)?.setImageDrawable(
            StylusToolbarDrawables.icon(ctx, StylusToolbarIcon.GEAR, colors),
        )
        (buttons[Action.KEYBOARD] as? ImageView)?.setImageDrawable(
            StylusToolbarDrawables.icon(ctx, StylusToolbarIcon.KEYBOARD, colors),
        )
        handle?.setImageDrawable(
            StylusToolbarDrawables.handle(ctx, pressed = pointerActive && pressedAction == null, colors),
        )
        sendImage?.setImageDrawable(StylusToolbarDrawables.enter(ctx, colors))
        sendText?.apply {
            setTextColor(colors.sendText)
            background = sendPill(colors) { value ->
                (value * resources.displayMetrics.density).roundToInt()
            }
        }
        for (action in Action.entries) updateActionFeedback(action, immediate = true)
    }

    /** 搜狗 `ShadowLayout`：整块面板自己画圆角底 + `setShadowLayer` 阴影（不用 elevation）。 */
    private class ShadowPanel(ctx: Context, colors: StylusToolbarPalette) : LinearLayout(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var radius = 0f
        private var shadowRadius = 0f
        private var shadowDx = 0f
        private var shadowDy = 0f
        private var shadowColor = colors.panelShadow
        private var fill = colors.panelBackground

        init {
            applyStyle(colors)
        }

        private fun applyStyle(colors: StylusToolbarPalette) {
            val res = resources
            radius = 18f * res.displayMetrics.density
            shadowRadius = 14f * res.displayMetrics.density
            shadowDx = 0f
            shadowDy = 4f * res.displayMetrics.density
            shadowColor = colors.panelShadow
            fill = colors.panelBackground
            setWillNotDraw(false)
            paint.color = fill
            paint.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor)
        }

        fun setPalette(colors: StylusToolbarPalette) {
            applyStyle(colors)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRoundRect(
                0f,
                0f,
                measuredWidth.toFloat(),
                measuredHeight.toFloat(),
                radius,
                radius,
                paint,
            )
        }
    }

    // ------------------------------------------------------------------ 键盘内容的藏 / 恢复

    /**
     * 把输入法窗口里除浮层以外的子 View 置成 `INVISIBLE`。
     *
     * 用 `INVISIBLE` 不用 `GONE`：保持 IME 宿主与工具栏坐标稳定；应用是否避让由
     * `StylusImeHooks.onComputeInsets` 明确控制，不依赖键盘 View 的布局结果。
     */
    private fun hideKeyboardContent(decor: ViewGroup, overlay: View) {
        for (i in 0 until decor.childCount) {
            val child = decor.getChildAt(i)
            if (child === overlay) continue
            if (child.visibility == View.VISIBLE) {
                child.visibility = View.INVISIBLE
                if (hiddenChildren.none { it === child }) hiddenChildren.add(child)
            }
        }
    }

    private fun restoreKeyboardContent() {
        for (v in hiddenChildren) runCatching { v.visibility = View.VISIBLE }
        hiddenChildren.clear()
    }

    private fun hideKeyboardPopupWindows() {
        for (view in keyboardPopupWindows.toList()) hideKeyboardPopupWindow(view)
    }

    private fun hideKeyboardPopupWindow(view: View) {
        val params = keyboardPopupLayoutParams(view)
        val firstHide = !hiddenKeyboardPopupWindows.containsKey(view)
        val state = hiddenKeyboardPopupWindows.getOrPut(view) {
            KeyboardPopupState(
                visibility = view.visibility,
                wasNotTouchable = params?.let { it.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0 },
            )
        }
        if (state.wasNotTouchable == null && params != null) {
            state.wasNotTouchable = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
        }

        // INVISIBLE 只是不画，WindowManager 的触摸通道仍在；透明窗口会继续截走面板点击。
        // 必须同时把真实窗口设成 NOT_TOUCHABLE。w2.p 重写 LayoutParams 后本方法还会被再次调用。
        view.visibility = View.INVISIBLE
        UnifiedFloatingShadow.setHostSuppressed(view, true)
        if (params != null && params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            updateKeyboardPopupLayout(view, params)
        }
        if (firstHide) L.i("event=stylus_keyboard_popup_hidden class=${view.javaClass.name} notTouchable=true")
    }

    private fun restoreKeyboardPopupWindows() {
        for ((view, state) in hiddenKeyboardPopupWindows) {
            runCatching {
                keyboardPopupLayoutParams(view)?.let { params ->
                    params.flags = if (state.wasNotTouchable == true) {
                        params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    } else {
                        params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    }
                    updateKeyboardPopupLayout(view, params)
                }
                view.visibility = state.visibility
                UnifiedFloatingShadow.setHostSuppressed(view, false)
            }
        }
        hiddenKeyboardPopupWindows.clear()
    }

    private fun keyboardPopupLayoutParams(view: View): WindowManager.LayoutParams? {
        (view.layoutParams as? WindowManager.LayoutParams)?.let { return it }
        return runCatching {
            val getter = view.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && it.returnType == WindowManager.LayoutParams::class.java
            } ?: return@runCatching null
            getter.invoke(view) as? WindowManager.LayoutParams
        }.getOrNull()
    }

    private fun updateKeyboardPopupLayout(view: View, params: WindowManager.LayoutParams) {
        if (!view.isAttachedToWindow) return
        runCatching {
            val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.updateViewLayout(view, params)
        }.onFailure { L.w("event=stylus_keyboard_popup_update_failed msg=${it.message}") }
    }

    private fun makeImeWindowTouchable(ime: InputMethodService, overlay: View) {
        val window = runCatching { ime.window?.window }.getOrNull() ?: return
        if (stylusImeWindow !== window) {
            restoreImeWindowTouchability()
            stylusImeWindow = window
            imeWindowWasNotTouchable =
                window.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
        }
        if (window.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            L.i("event=stylus_ime_window_touchable physicalKeyboard=true")
        }
        overlay.requestLayout()
        overlay.invalidate()
    }

    private fun restoreImeWindowTouchability(forceTouchable: Boolean = false) {
        val window = stylusImeWindow ?: if (forceTouchable) {
            runCatching { ime?.window?.window }.getOrNull()
        } else {
            null
        }
        val wasNotTouchable = imeWindowWasNotTouchable
        if (window != null) {
            runCatching {
                if (!forceTouchable && wasNotTouchable == true) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                }
                window.decorView.requestLayout()
                window.decorView.requestApplyInsets()
            }
        }
        stylusImeWindow = null
        imeWindowWasNotTouchable = null
    }

    // ------------------------------------------------------------------ 位置

    private fun prefs(): android.content.SharedPreferences? =
        ime?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun place() {
        val p = panel ?: return
        val r = root ?: return
        if (r.width <= 0 || p.width <= 0) return
        val sp = prefs()
        val fx = sp?.getFloat(keyX(), -1f) ?: -1f
        val fy = sp?.getFloat(keyY(), -1f) ?: -1f
        val (minX, maxX, minY, maxY) = bounds()
        val x: Int
        val y: Int
        if (fx < 0f || fy < 0f) {
            // 搜狗默认：(屏宽 - 面板宽)/2 居中；底边距屏幕安全区底 52dp
            val d = r.resources.displayMetrics.density
            x = ((r.width - p.width) / 2f).roundToInt().coerceIn(minX, maxX)
            y = (maxY - (52f * d).roundToInt()).coerceIn(minY, maxY)
        } else {
            val w = (maxX - minX).coerceAtLeast(1)
            val h = (maxY - minY).coerceAtLeast(1)
            x = (minX + fx * w).roundToInt().coerceIn(minX, maxX)
            y = (minY + fy * h).roundToInt().coerceIn(minY, maxY)
        }
        applyPosition(x, y)
    }

    /** 可放范围：左右各留 20dp、底 28dp（搜狗的 `u0/u1/tz`）。 */
    private fun bounds(): IntArray {
        val p = panel ?: return intArrayOf(0, 0, 0, 0)
        val r = root ?: return intArrayOf(0, 0, 0, 0)
        val d = r.resources.displayMetrics.density
        val insetX = (20f * d).roundToInt()
        val insetBottom = (28f * d).roundToInt()
        val minX = insetX
        val maxX = (r.width - insetX - p.width).coerceAtLeast(minX)
        val minY = 0
        val maxY = (r.height - insetBottom - p.height).coerceAtLeast(minY)
        return intArrayOf(minX, maxX, minY, maxY)
    }

    private fun applyPosition(x: Int, y: Int) {
        val p = panel ?: return
        val lp = p.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.leftMargin == x && lp.topMargin == y &&
            p.translationX == 0f && p.translationY == 0f
        ) {
            return
        }
        lp.leftMargin = x
        lp.topMargin = y
        lp.gravity = Gravity.TOP or Gravity.START
        p.translationX = 0f
        p.translationY = 0f
        p.layoutParams = lp
    }

    private fun currentMargin(): Pair<Int, Int> {
        val p = panel ?: return 0 to 0
        val lp = p.layoutParams as? FrameLayout.LayoutParams ?: return 0 to 0
        return lp.leftMargin to lp.topMargin
    }

    private fun savePosition() {
        val p = panel ?: return
        val (minX, maxX, minY, maxY) = bounds()
        val (left, top) = currentMargin()
        val w = (maxX - minX).coerceAtLeast(1)
        val h = (maxY - minY).coerceAtLeast(1)
        prefs()?.edit()
            ?.putFloat(keyX(), (left - minX).toFloat() / w)
            ?.putFloat(keyY(), (top - minY).toFloat() / h)
            ?.apply()
    }

    private fun keyX(): String =
        if (isPortrait()) "toolbar_x_port_ratio" else "toolbar_x_land_ratio"

    private fun keyY(): String =
        if (isPortrait()) "toolbar_y_port_ratio" else "toolbar_y_land_ratio"

    private fun isPortrait(): Boolean =
        (ime?.resources?.configuration?.orientation ?: Configuration.ORIENTATION_LANDSCAPE) ==
            Configuration.ORIENTATION_PORTRAIT

    // ------------------------------------------------------------------ Folme 按钮反馈

    private fun bindFeedback(view: View) {
        val layer = StylusToolbarFeedbackDrawable()
        view.foreground = layer
        feedbackLayers[view] = layer
    }

    private fun actionViews(action: Action): List<View> =
        if (action == Action.SEND) {
            listOfNotNull(sendText, sendImage)
        } else {
            listOfNotNull(buttons[action])
        }

    private fun clearActionState(action: Action) {
        if (pressedAction == action) {
            repeatHandler.removeCallbacksAndMessages(null)
            repeating = false
            repeatStarted = false
            pressedAction = null
            pressedInside = false
        }
        if (hoveredAction == action) hoveredAction = null
    }

    private fun setActionPressedFlag(action: Action, pressed: Boolean) {
        for (view in actionViews(action)) view.isPressed = pressed
    }

    /**
     * 搜狗 IconButton/SearchButton 都挂 Folme touch；圆形键与发送用 NORMAL hover，
     * 设置/键盘在 XML 中是 hover_type=1（FLOATED）。这里按 Folme 的默认比例与距离复刻。
     */
    private fun updateActionFeedback(action: Action, immediate: Boolean = false) {
        val colors = palette ?: return
        val enabled = actionViews(action).any { it.isEnabled }
        val pressed = enabled && pressedAction == action && pressedInside
        val hovered = enabled && hoveredAction == action && !pressed
        val tint = when {
            pressed -> colors.feedbackPressed
            hovered -> colors.feedbackHovered
            else -> Color.TRANSPARENT
        }

        for (view in actionViews(action)) {
            val active = view.visibility == View.VISIBLE
            animateFeedback(view, if (active) tint else Color.TRANSPARENT, immediate)

            val floated = action == Action.SETTINGS || action == Action.KEYBOARD
            val scale = when {
                !active -> 1f
                pressed -> touchScale(view)
                hovered && floated -> hoverScale(view)
                else -> 1f
            }
            val translation = if (active && hovered && floated) hoverTranslation(view) else 0f to 0f
            animateTransform(view, scale, translation.first, translation.second, immediate)
        }
    }

    private fun animateFeedback(view: View, target: Int, immediate: Boolean) {
        val layer = feedbackLayers[view] ?: return
        feedbackAnimators.remove(view)?.cancel()
        if (immediate || layer.feedbackColor == target) {
            layer.feedbackColor = target
            return
        }
        val animator = ValueAnimator.ofArgb(layer.feedbackColor, target).apply {
            duration = FEEDBACK_ANIMATION_MS
            addUpdateListener { layer.feedbackColor = it.animatedValue as Int }
        }
        feedbackAnimators[view] = animator
        animator.start()
    }

    private fun animateTransform(
        view: View,
        scale: Float,
        translationX: Float,
        translationY: Float,
        immediate: Boolean,
    ) {
        view.animate().cancel()
        if (immediate) {
            view.scaleX = scale
            view.scaleY = scale
            view.translationX = translationX
            view.translationY = translationY
            return
        }
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .translationX(translationX)
            .translationY(translationY)
            .setDuration(BUTTON_ANIMATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** FolmeTouch：max((最大边 - 10dp) / 最大边, 0.9)。 */
    private fun touchScale(view: View): Float {
        val size = maxOf(view.width, view.height).toFloat()
        if (size <= 0f) return TOUCH_MIN_SCALE
        val dist = 10f * view.resources.displayMetrics.density
        return maxOf((size - dist) / size, TOUCH_MIN_SCALE)
    }

    /** FolmeHover FLOATED：min((最大边 + 12px) / 最大边, 1.15)。 */
    private fun hoverScale(view: View): Float {
        val size = maxOf(view.width, view.height).toFloat()
        if (size <= 0f) return 1f
        return minOf((size + HOVER_SCALE_DISTANCE_PX) / size, HOVER_MAX_SCALE)
    }

    /** FolmeHover 的磁吸位移；常量就是源码里的 px，不按 density 再放大。 */
    private fun hoverTranslation(view: View): Pair<Float, Float> {
        if (view.width <= 0 || view.height <= 0) return 0f to 0f
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        // getLocationOnScreen 已含当前 translation；先减回布局原点，避免 MOVE 时自激抖动。
        val left = loc[0] - view.translationX
        val top = loc[1] - view.translationY
        val cx = left + view.width / 2f
        val cy = top + view.height / 2f
        fun dist(size: Int): Float {
            val fraction = ((size - 40f) / 360f).coerceIn(0f, 1f)
            return (15f * (1f - fraction)).coerceAtMost(15f)
        }
        val distance = minOf(dist(view.width), dist(view.height))
        val x = ((hoverRawX - cx) / view.width).coerceIn(-1f, 1f) * distance
        val y = ((hoverRawY - cy) / view.height).coerceIn(-1f, 1f) * distance
        return x to y
    }

    private fun resetViewFeedback(view: View?) {
        if (view == null) return
        feedbackAnimators.remove(view)?.cancel()
        feedbackLayers[view]?.feedbackColor = Color.TRANSPARENT
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.isPressed = false
    }

    private fun resetButtonFeedback() {
        for (animator in feedbackAnimators.values) animator.cancel()
        feedbackAnimators.clear()
        for (view in feedbackLayers.keys.toList()) resetViewFeedback(view)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onHover(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                hoverRawX = ev.rawX
                hoverRawY = ev.rawY
                val next = hitButton(ev)
                val previous = hoveredAction
                if (next != previous) {
                    hoveredAction = next
                    previous?.let { updateActionFeedback(it) }
                }
                next?.let { updateActionFeedback(it) }
            }

            MotionEvent.ACTION_HOVER_EXIT -> {
                val previous = hoveredAction
                hoveredAction = null
                previous?.let { updateActionFeedback(it) }
            }
        }
    }

    // ------------------------------------------------------------------ 指针（手指 / 笔同一套）

    @SuppressLint("ClickableViewAccessibility")
    private fun onPointer(ev: MotionEvent, p: ShadowPanel) {
        if (p.width <= 0) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerActive = true
                downRawX = ev.rawX
                downRawY = ev.rawY
                val (l, t) = currentMargin()
                panelDownLeft = l
                panelDownTop = t
                dragging = false
                repeatStarted = false
                val hit = hitButton(ev)
                pressedAction = hit
                pressedInside = hit != null
                if (hit != null) {
                    setActionPressedFlag(hit, true)
                    updateActionFeedback(hit)
                    if (hit == Action.BACKSPACE) {
                        repeating = false
                        repeatHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    }
                } else {
                    // 空白处按下：搜狗的反馈是整块放大 1.05 + 拖动条变蓝
                    val local = panelLocalPoint(p, ev)
                    p.pivotX = local.first
                    p.pivotY = local.second
                    root?.let(::uninstallInsetsListener)
                    p.animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE).setDuration(120).start()
                    palette?.let { colors ->
                        handle?.setImageDrawable(StylusToolbarDrawables.handle(p.context, pressed = true, colors))
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val pressed = pressedAction
                if (pressed != null) {
                    val inside = hitButton(ev) == pressed
                    if (inside != pressedInside) {
                        pressedInside = inside
                        setActionPressedFlag(pressed, inside)
                        updateActionFeedback(pressed)
                        if (!inside) {
                            repeatHandler.removeCallbacksAndMessages(null)
                            repeating = false
                        }
                    }
                } else if (!dragging) {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    // 搜狗按两个轴各 3px 判断 click / drag，不使用系统 touchSlop。
                    if (abs(dx) > MOVE_THRESHOLD_PX || abs(dy) > MOVE_THRESHOLD_PX) {
                        dragging = true
                        dragTo(dx, dy)
                    }
                } else if (dragging) {
                    dragTo(ev.rawX - downRawX, ev.rawY - downRawY)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pointerActive = false
                repeatHandler.removeCallbacksAndMessages(null)
                repeating = false
                val wasDragging = dragging
                dragging = false
                if (wasDragging) {
                    commitDrag()
                }
                val hit = pressedAction
                val releasedInside = hit != null && pressedInside && hitButton(ev) == hit
                hit?.let { setActionPressedFlag(it, false) }
                pressedAction = null
                pressedInside = false
                hit?.let { updateActionFeedback(it) }
                if (hit != null && releasedInside && ev.actionMasked == MotionEvent.ACTION_UP) {
                    if (!repeatStarted) onAction?.invoke(hit)
                } else if (hit == null && ev.actionMasked == MotionEvent.ACTION_UP && !wasDragging) {
                    // 空白处轻点：只做视觉反馈，不产生动作
                }
                repeatStarted = false
                p.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                palette?.let { colors ->
                    handle?.setImageDrawable(StylusToolbarDrawables.handle(p.context, pressed = false, colors))
                }
                // KEYBOARD 动作会在上面的回调中移除 root；此时不能把限定面板矩形的
                // listener 又装回去，否则普通键盘仍只剩原工具栏区域可点击。
                if (isShowing) root?.let(::installInsetsListener)
            }
        }
    }

    /** 普通 View 事件的 x/y 是面板局部坐标，手写回调转来的事件却可能是窗口坐标。 */
    private fun panelLocalPoint(p: View, ev: MotionEvent): Pair<Float, Float> {
        val loc = IntArray(2)
        p.getLocationOnScreen(loc)
        return (ev.rawX - loc[0]).coerceIn(0f, p.width.toFloat()) to
            (ev.rawY - loc[1]).coerceIn(0f, p.height.toFloat())
    }

    private fun dragTo(dx: Float, dy: Float) {
        val p = panel ?: return
        val (minX, maxX, minY, maxY) = bounds()
        val nx = (panelDownLeft + dx).coerceIn(minX.toFloat(), maxX.toFloat())
        val ny = (panelDownTop + dy).coerceIn(minY.toFloat(), maxY.toFloat())
        p.translationX = nx - panelDownLeft
        p.translationY = ny - panelDownTop
    }

    private fun commitDrag() {
        val p = panel ?: return
        val (l, t) = currentMargin()
        applyPosition((l + p.translationX).roundToInt(), (t + p.translationY).roundToInt())
        p.post { savePosition() }
    }

    private fun hitButton(ev: MotionEvent): Action? {
        val loc = IntArray(2)
        for ((action, view) in buttons) {
            if (view.visibility != View.VISIBLE || !view.isEnabled) continue
            view.getLocationOnScreen(loc)
            if (ev.rawX >= loc[0] && ev.rawX < loc[0] + view.width &&
                ev.rawY >= loc[1] && ev.rawY < loc[1] + view.height
            ) {
                return action
            }
        }
        // 「发送」在文本胶囊和图标胶囊之间切换，两者都算
        for (view in listOfNotNull(sendText, sendImage)) {
            if (view.visibility != View.VISIBLE) continue
            view.getLocationOnScreen(loc)
            if (ev.rawX >= loc[0] && ev.rawX < loc[0] + view.width &&
                ev.rawY >= loc[1] && ev.rawY < loc[1] + view.height
            ) {
                return Action.SEND
            }
        }
        return null
    }

    // ------------------------------------------------------------------ 可触摸区域

    private fun installInsetsListener(r: View) {
        val observer = r.viewTreeObserver
        if (insetsListenerInstalled && insetsObserver === observer && observer.isAlive) return
        if (insetsListenerInstalled) uninstallInsetsListener(r)
        runCatching {
            observer.javaClass.getMethod("addOnComputeInternalInsetsListener", insetsListenerClass)
                .invoke(observer, insetsListener)
        }.onSuccess {
            insetsListenerInstalled = true
            insetsObserver = observer
            r.requestLayout()
        }.onFailure { L.w("event=stylus_toolbar_insets_failed msg=${it.message}") }
    }

    private fun uninstallInsetsListener(r: View) {
        if (!insetsListenerInstalled) return
        runCatching {
            val observer = insetsObserver ?: r.viewTreeObserver
            if (observer.isAlive) {
                observer.javaClass.getMethod("removeOnComputeInternalInsetsListener", insetsListenerClass)
                    .invoke(observer, insetsListener)
            }
        }.onFailure { L.w("event=stylus_toolbar_insets_remove_failed msg=${it.message}") }
        insetsListenerInstalled = false
        insetsObserver = null
    }

    /** 让 InputMethodService 自己的 Insets 监听器重新上报完整键盘触摸区域。 */
    private fun requestImeInsetsRefresh() {
        val decor = runCatching { ime?.window?.window?.decorView }.getOrNull() ?: return
        decor.requestLayout()
        decor.requestApplyInsets()
        // hide() 从按钮的触摸回调内执行，再补一帧可避免当前事件分发保留旧 region。
        decor.post {
            if (!isShowing) {
                decor.requestLayout()
                decor.requestApplyInsets()
            }
        }
    }

    private companion object {
        const val PREFS = "fancypad_stylus_toolbar"

        /** InternalInsetsInfo.TOUCHABLE_INSETS_REGION */
        const val TOUCHABLE_INSETS_REGION = 3

        const val MOVE_THRESHOLD_PX = 3f
        const val PRESS_SCALE = 1.05f
        const val SHOW_START_SCALE = 0.9f
        const val SHOW_ANIMATION_MS = 180L

        const val TOUCH_MIN_SCALE = 0.9f
        const val HOVER_MAX_SCALE = 1.15f
        const val HOVER_SCALE_DISTANCE_PX = 12f
        const val BUTTON_ANIMATION_MS = 120L
        const val FEEDBACK_ANIMATION_MS = 100L

        const val LONG_PRESS_MS = 500L
        const val REPEAT_START_MS = 60
        const val REPEAT_STEP_MS = 1
        const val REPEAT_MIN_MS = 5
    }
}
