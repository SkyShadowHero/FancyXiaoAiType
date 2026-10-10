package io.github.skyshadowhero.fancypad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

/**
 * 随手写笔迹画布 —— **只画，不识别**（识别在 [StylusImeHooks] 里走讯飞 HCR / 系统笔引擎）。
 *
 * 事件不是走 View 的 `onTouchEvent` 进来的，而是由 [StylusImeHooks] 在
 * `onStylusHandwritingMotionEvent` 里直接调 [addPoint] 喂进来。原因：那条回调本来就要处理
 * 每一笔（识别要用），再让框架把事件回放给 View 只会多一条路径、还可能把同一笔喂两次。
 * 所以这里 `onTouchEvent` 不接管（窗口本身也是 `FLAG_NOT_TOUCHABLE`）。
 *
 * 重绘只按**线段包围盒**失效：这块屏 3200×2136，每个 MOVE 都整屏重绘会明显掉帧
 *（模块别处（顶栏渐进模糊的 backdrop）就是踩过这个坑才改成按需挂载的）。
 */
internal class StylusInkView(context: Context) : View(context) {

    /** 一条笔迹：一次落笔到抬笔。路径增量构建，绘制时直接画，不做二次拟合。 */
    private class Stroke {
        val path = Path()
        var lastX = 0f
        var lastY = 0f
    }

    private val strokes = ArrayList<Stroke>(32)
    private var active: Stroke? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = PrefKeys.STYLUS_INK_COLOR_DEFAULT
        strokeWidth = 6f
    }

    /** 复用同一个 Rect，避免每个 MOVE 都分配对象。 */
    private val dirty = Rect()

    init {
        // 画布必须透明：它铺在宿主应用内容之上，不透明就等于盖住用户正在写的界面
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
    }

    /** 应用颜色与线宽（每次会话开始时调一次；改设置要下次起笔才生效）。 */
    fun applyInk(color: Int, widthPx: Float) {
        paint.color = color
        paint.strokeWidth = widthPx.coerceAtLeast(1f)
        invalidate()
    }

    /** 清空墨迹（新会话 / 上屏之后 / 会话结束）。 */
    fun clear() {
        if (strokes.isEmpty() && active == null) return
        strokes.clear()
        active = null
        invalidate()
    }

    /**
     * 抹掉最后一条笔迹。
     *
     * 给"点一下"用的：轻点只有在抬笔那一刻才判得出来，而它的 DOWN/MOVE 早画上去了
     * （一个几乎看不见的小点）。这里把它单独撤掉，而不是 [clear] 整块 ——
     * 整块清会把刚写完、还没识别上屏的字的墨迹一起抹掉。
     *
     * 撤销是低频动作，直接整块重绘，不值得为它算脏区。
     */
    fun removeLastStroke() {
        active = null
        if (strokes.isEmpty()) return
        strokes.removeAt(strokes.lastIndex)
        invalidate()
    }

    /**
     * 喂一个笔迹点。坐标必须是**本 View 的局部坐标**（调用方负责把屏幕坐标减掉画布位置）。
     *
     * [action] 用 `MotionEvent` 语义：0=DOWN、2=MOVE、1=UP、3=CANCEL。
     * 只应在主线程调用（内部会触发 `invalidate`）。
     */
    fun addPoint(x: Float, y: Float, action: Int) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                val s = Stroke()
                s.path.moveTo(x, y)
                s.lastX = x
                s.lastY = y
                strokes.add(s)
                active = s
                invalidateSegment(x, y, x, y)
            }

            MotionEvent.ACTION_MOVE -> {
                val s = active ?: return
                s.path.lineTo(x, y)
                invalidateSegment(s.lastX, s.lastY, x, y)
                s.lastX = x
                s.lastY = y
            }

            MotionEvent.ACTION_UP -> {
                val s = active ?: return
                s.path.lineTo(x, y)
                invalidateSegment(s.lastX, s.lastY, x, y)
                s.lastX = x
                s.lastY = y
                active = null
            }

            MotionEvent.ACTION_CANCEL -> active = null
        }
    }

    /**
     * 只重绘线段包围盒。
     *
     * 局部重绘的这两个重载在 SDK 35+ 都被标了 deprecated（官方倾向直接 `invalidate()`），
     * 但这里必须保留：画布是**整屏**（本机 3200×2136），每个 MOVE 整屏重绘会明显掉帧，
     * 而局部重绘在真机上照旧生效。所以按下不表、只抑制告警。
     */
    @Suppress("DEPRECATION")
    private fun invalidateSegment(x0: Float, y0: Float, x1: Float, y1: Float) {
        // 线宽 + 圆头/圆角的外扩，再加 2px 抗锯齿余量
        val pad = paint.strokeWidth + 2f
        dirty.set(
            (minOf(x0, x1) - pad).toInt(),
            (minOf(y0, y1) - pad).toInt(),
            (maxOf(x0, x1) + pad).toInt() + 1,
            (maxOf(y0, y1) + pad).toInt() + 1,
        )
        invalidate(dirty)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (s in strokes) {
            canvas.drawPath(s.path, paint)
        }
    }
}
