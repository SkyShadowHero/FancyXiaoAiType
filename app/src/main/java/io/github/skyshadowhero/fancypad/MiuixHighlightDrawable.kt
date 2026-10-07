package io.github.skyshadowhero.fancypad

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * Miuix 风格的扁平高亮：**满行矩形 + 状态切换带动画时长**。
 *
 * Miuix 的 `MiuixIndication`（`miuix-ui/utils/MiuixIndication.kt`）行为：
 *
 * ```kotlin
 * private const val HOVER_ALPHA_DELTA = 0.06f
 * private const val FOCUS_ALPHA_DELTA = 0.08f
 * private const val PRESS_ALPHA_DELTA = 0.10f
 *
 * // 目标 alpha 是**累加**的
 * if (isHovered) targetAlpha += HOVER_ALPHA_DELTA
 * if (isFocused) targetAlpha += FOCUS_ALPHA_DELTA
 * if (isPressed && !isHoldDown) targetAlpha += PRESS_ALPHA_DELTA
 *
 * // 过渡用 spring：进入 response 0.2~0.6，退出 response 0.2~0.35
 * PressEnterSpring = folmeSpring(damping = 1.0f,  response = 0.2f)
 * HoverEnterSpring = folmeSpring(damping = 1.0f,  response = 0.6f)
 *
 * override fun ContentDrawScope.draw() {
 *     drawContent()
 *     drawRect(color = color, alpha = alpha, size = size)   // 满尺寸矩形，画在内容之上
 * }
 * ```
 *
 * 为什么不用 `StateListDrawable`：它是**瞬时切换**（实测表现为「hover 颜色直接闪现、没有时长」）。
 * 也不用 `RippleDrawable`：那是圆形的扩散波纹（就是那个「大水波纹」），Miuix 没有。
 *
 * 这里用 `ValueAnimator` 把 alpha 平滑过渡过去；弹簧用一对缓入/缓出时长近似
 * （Miuix 的 response 0.2~0.6 换算成 150~250ms 量级），观感一致而实现简单可控。
 */
internal class MiuixHighlightDrawable(private val color: Int) : Drawable() {

    private val paint = Paint()
    private var animatedAlpha = 0f
    private var overrideAlpha = 1f
    private var lastTarget = 0f
    private var animator: ValueAnimator? = null

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        animateTo(targetAlpha(state))
        return true
    }

    /**
     * `bounds` 变了也要当成一次新的进入动画。
     *
     * 原因：框架菜单的高亮是 `ListView` 的 **selector**，而 selector **只有一个 drawable 实例** ——
     * 切换菜单项时它只是把 bounds 挪到新的一行，**状态完全没变**，`onStateChange` 不会被调用，
     * 所以只有「第一次出现」和「消失」有过渡，行与行之间切换全是闪现（实测确认）。
     * 这里在 bounds 变化且当前已经到达目标 alpha 时，把 alpha 归零重播一次进入动画。
     */
    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        runCatching {
            if (lastTarget > 0f && animatedAlpha >= lastTarget - 0.001f) {
                animatedAlpha = 0f
                animateTo(lastTarget)
            }
        }
    }

    /** Miuix 是累加的：悬停 + 聚焦 + 按下可以同时命中。 */
    private fun targetAlpha(state: IntArray): Float {
        var a = 0f
        if (state.contains(android.R.attr.state_hovered)) a += HOVER_ALPHA
        // 刻意**不**统计 state_focused：
        // 框架菜单的高亮走 `ListView` 的 selector，而这个 ListView 自己一直是 focused
        // （实测 st=---F），照 Miuix 累加会变成 hover 0.06 + focus 0.08 = 0.14 ——
        // 这正是「第二种菜单的遮罩比第一种深一档」的原因。
        if (state.contains(android.R.attr.state_pressed)) a += PRESS_ALPHA
        val target = a.coerceIn(0f, 1f)
        lastTarget = target
        return target
    }

    private fun animateTo(target: Float) {
        runCatching {
            animator?.cancel()
            val from = animatedAlpha
            if (from == target) return
            val entering = target > from
            animator = ValueAnimator.ofFloat(from, target).apply {
                duration = if (entering) ENTER_MS else EXIT_MS
                interpolator = if (entering) DecelerateInterpolator() else AccelerateInterpolator()
                addUpdateListener {
                    animatedAlpha = it.animatedValue as Float
                    invalidateSelf()
                }
                start()
            }
        }
    }

    override fun draw(canvas: Canvas) {
        val alpha = animatedAlpha * overrideAlpha
        if (alpha <= 0.002f) return
        paint.color = color
        paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        // 满 bounds 一个矩形：无圆角、无内缩、无扩散 —— 就是 Miuix 的 drawRect(size = size)
        canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        overrideAlpha = (alpha / 255f).coerceIn(0f, 1f)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    internal companion object {
        /** Miuix `HOVER_ALPHA_DELTA` */
        const val HOVER_ALPHA = 0.06f
        /** Miuix `FOCUS_ALPHA_DELTA` */
        const val FOCUS_ALPHA = 0.08f
        /** Miuix `PRESS_ALPHA_DELTA` */
        const val PRESS_ALPHA = 0.10f

        /** 进入：近似 Miuix 的 `PressEnterSpring` / `HoverEnterSpring` */
        const val ENTER_MS = 220L
        /** 退出：近似 Miuix 的 `PressExitSpring` / `HoverExitSpring` */
        const val EXIT_MS = 150L
    }
}
