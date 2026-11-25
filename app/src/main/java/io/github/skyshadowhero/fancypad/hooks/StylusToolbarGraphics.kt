package io.github.skyshadowhero.fancypad.hooks

import io.github.skyshadowhero.fancypad.*

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser
import kotlin.math.roundToInt

/** 面板图标种类。所有图形都在运行时绘制，不能依赖模块 APK 的资源 ID。 */
internal enum class StylusToolbarIcon { UNDO, REDO, SPACE, BACKSPACE, GEAR, KEYBOARD }

/** 搜狗随手写工具箱的昼夜配色。 */
internal data class StylusToolbarPalette(
    val isNight: Boolean,
    val panelBackground: Int,
    val panelShadow: Int,
    val chip: Int,
    val chipDisabled: Int,
    val glyph: Int,
    val glyphDisabled: Int,
    val icon: Int,
    val handle: Int,
    val send: Int,
    val sendText: Int,
    val feedbackPressed: Int,
    val feedbackHovered: Int,
) {
    companion object {
        fun from(context: Context): StylusToolbarPalette {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            val send = if (night) 0xFF4788FF.toInt() else 0xFF3482FF.toInt()
            return StylusToolbarPalette(
                isNight = night,
                panelBackground = if (night) 0xFF2C2C2E.toInt() else Color.WHITE,
                panelShadow = 0x191A1A1A,
                chip = if (night) 0x0FFFFFFF else 0x0A000000,
                chipDisabled = if (night) 0x0FFFFFFF else 0x0A000000,
                glyph = if (night) 0xCCFFFFFF.toInt() else 0x99000000.toInt(),
                glyphDisabled = if (night) 0x4CFFFFFF else 0x4C000000,
                icon = if (night) 0xCCFFFFFF.toInt() else 0x99000000.toInt(),
                handle = if (night) 0x19FFFFFF else 0x14000000,
                send = send,
                sendText = if (night) 0xE6FFFFFF.toInt() else Color.WHITE,
                // ButtonForegroundDrawable / Folme 的前景反馈：浅色黑、深色白。
                feedbackPressed = if (night) 0x14FFFFFF else 0x1F000000,
                feedbackHovered = if (night) 0x0FFFFFFF else 0x0F000000,
            )
        }
    }
}

/** Folme / ButtonForegroundDrawable 的圆角前景层；颜色由工具栏按 hover/pressed 动画驱动。 */
internal class StylusToolbarFeedbackDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    var feedbackColor: Int = Color.TRANSPARENT
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        if (Color.alpha(feedbackColor) == 0 || bounds.isEmpty) return
        paint.color = feedbackColor
        val radius = minOf(bounds.width(), bounds.height()) / 2f
        canvas.drawRoundRect(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
            radius,
            radius,
            paint,
        )
    }

    override fun setAlpha(alpha: Int) {
        feedbackColor = Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(feedbackColor),
            Color.green(feedbackColor),
            Color.blue(feedbackColor),
        )
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in the Android framework")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * 搜狗原矢量图的 pathData 副本。
 *
 * 注入到输入法进程后，View 的 Context 属于 `com.xiaomi.type`，模块的 `R.drawable` / `R.color`
 * 均不可解析；因此这里只使用常量、[PathParser] 和 [Drawable]，不进行任何模块资源查询。
 */
internal object StylusToolbarDrawables {
    private const val BLUE = 0xFF0D84FF.toInt()

    fun chip(
        context: Context,
        icon: StylusToolbarIcon,
        enabled: Boolean,
        colors: StylusToolbarPalette,
    ): Drawable {
        val glyph = when (icon) {
            StylusToolbarIcon.UNDO -> UNDO_PATH
            StylusToolbarIcon.REDO -> REDO_PATH
            StylusToolbarIcon.SPACE -> SPACE_PATH
            StylusToolbarIcon.BACKSPACE -> BACKSPACE_PATH
            else -> error("$icon is not a round toolbar button")
        }
        return PathDrawable(
            density = context.resources.displayMetrics.density,
            viewportWidth = 38f,
            viewportHeight = 38f,
            intrinsicWidthDp = 38f,
            intrinsicHeightDp = 38f,
            layerData = listOf(
                LayerData(CHIP_PATH, if (enabled) colors.chip else colors.chipDisabled),
                LayerData(glyph, if (enabled) colors.glyph else colors.glyphDisabled, evenOdd = true),
            ),
        )
    }

    fun icon(context: Context, icon: StylusToolbarIcon, colors: StylusToolbarPalette): Drawable =
        when (icon) {
            StylusToolbarIcon.GEAR -> PathDrawable(
                context.resources.displayMetrics.density,
                16f,
                17f,
                16f,
                17f,
                listOf(LayerData(GEAR_PATH, colors.icon, evenOdd = true)),
            )
            StylusToolbarIcon.KEYBOARD -> PathDrawable(
                context.resources.displayMetrics.density,
                18f,
                14f,
                18f,
                14f,
                listOf(LayerData(KEYBOARD_PATH, colors.icon, evenOdd = true)),
            )
            else -> error("$icon is not a standalone toolbar icon")
        }

    fun enter(context: Context, colors: StylusToolbarPalette): Drawable = PathDrawable(
        density = context.resources.displayMetrics.density,
        viewportWidth = 86f,
        viewportHeight = 32f,
        intrinsicWidthDp = 86f,
        intrinsicHeightDp = 32f,
        layerData = listOf(
            LayerData(ENTER_PILL_PATH, BLUE),
            // 搜狗 at9.xml 的回车箭头固定为纯白，不随深色模式降到 E6 alpha。
            LayerData(ENTER_GLYPH_PATH, Color.WHITE, evenOdd = true),
        ),
    )

    fun handle(context: Context, pressed: Boolean, colors: StylusToolbarPalette): Drawable = PathDrawable(
        density = context.resources.displayMetrics.density,
        viewportWidth = 40f,
        viewportHeight = 4f,
        intrinsicWidthDp = 40f,
        intrinsicHeightDp = 4f,
        layerData = listOf(LayerData(HANDLE_PATH, if (pressed) BLUE else colors.handle)),
    )

    private data class LayerData(val data: String, val color: Int, val evenOdd: Boolean = false)

    private class PathDrawable(
        density: Float,
        private val viewportWidth: Float,
        private val viewportHeight: Float,
        intrinsicWidthDp: Float,
        intrinsicHeightDp: Float,
        layerData: List<LayerData>,
    ) : Drawable() {
        private data class Layer(val path: Path, val color: Int)

        private val intrinsicWidth = (intrinsicWidthDp * density).roundToInt()
        private val intrinsicHeight = (intrinsicHeightDp * density).roundToInt()
        private val layers = layerData.map { source ->
            val path = requireNotNull(PathParser.createPathFromPathData(source.data)) {
                "Invalid stylus icon path"
            }
            if (source.evenOdd) path.fillType = Path.FillType.EVEN_ODD
            Layer(path, source.color)
        }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private var drawableAlpha = 255
        private var drawableColorFilter: ColorFilter? = null

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty || viewportWidth <= 0f || viewportHeight <= 0f) return
            val save = canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            canvas.scale(b.width() / viewportWidth, b.height() / viewportHeight)
            paint.colorFilter = drawableColorFilter
            for (layer in layers) {
                paint.color = layer.color
                paint.alpha = Color.alpha(layer.color) * drawableAlpha / 255
                canvas.drawPath(layer.path, paint)
            }
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) {
            drawableAlpha = alpha.coerceIn(0, 255)
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            drawableColorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in the Android framework")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = intrinsicWidth

        override fun getIntrinsicHeight(): Int = intrinsicHeight
    }

    private const val CHIP_PATH = "M0,19C0,8.5066 8.5066,0 19,0C29.4934,0 38,8.5066 38,19C38,29.4934 29.4934,38 19,38C8.5066,38 0,29.4934 0,19Z"
    private const val UNDO_PATH = "M15.2512,12.2184C15.5448,11.9262 16.0197,11.9274 16.3119,12.221C16.6041,12.5146 16.6029,12.9894 16.3093,13.2816L13.5776,16H21.4203C24.778,16 27.5,18.722 27.5,22.0797V23.75C27.5,24.1642 27.1642,24.5 26.75,24.5C26.3358,24.5 26,24.1642 26,23.75V22.0797C26,19.5504 23.9496,17.5 21.4203,17.5H13.5776L16.3093,20.2184C16.6029,20.5106 16.6041,20.9854 16.3119,21.279C16.0197,21.5726 15.5448,21.5738 15.2512,21.2816L11.3017,17.3513C11.1186,17.2146 11,16.9961 11,16.75C11,16.5039 11.1186,16.2854 11.3017,16.1487L15.2512,12.2184Z"
    private const val REDO_PATH = "M23.2488,12.2184C22.9552,11.9262 22.4803,11.9274 22.1881,12.221C21.8959,12.5146 21.8971,12.9894 22.1907,13.2816L24.9224,16H17.0797C13.722,16 11,18.722 11,22.0797V23.75C11,24.1642 11.3358,24.5 11.75,24.5C12.1642,24.5 12.5,24.1642 12.5,23.75V22.0797C12.5,19.5504 14.5504,17.5 17.0797,17.5H24.9224L22.1907,20.2184C21.8971,20.5106 21.8959,20.9854 22.1881,21.279C22.4803,21.5726 22.9552,21.5738 23.2488,21.2816L27.1983,17.3513C27.3814,17.2146 27.5,16.9961 27.5,16.75C27.5,16.5039 27.3814,16.2854 27.1983,16.1487L23.2488,12.2184Z"
    private const val SPACE_PATH = "M11.5,16.25C11.9142,16.25 12.25,16.5858 12.25,17V18.8C12.25,19.3724 12.2506,19.7566 12.2748,20.0525C12.2982,20.3396 12.3401,20.4769 12.3862,20.5675C12.5061,20.8027 12.6973,20.9939 12.9325,21.1138C13.0231,21.1599 13.1604,21.2018 13.4475,21.2252C13.7434,21.2494 14.1276,21.25 14.7,21.25H24.3C24.8724,21.25 25.2566,21.2494 25.5525,21.2252C25.8396,21.2018 25.9769,21.1599 26.0675,21.1138C26.3027,20.9939 26.4939,20.8027 26.6138,20.5675C26.6599,20.4769 26.7018,20.3396 26.7252,20.0525C26.7494,19.7566 26.75,19.3724 26.75,18.8V17C26.75,16.5858 27.0858,16.25 27.5,16.25C27.9142,16.25 28.25,16.5858 28.25,17V18.8305C28.25,19.3646 28.25,19.8104 28.2203,20.1747C28.1892,20.5546 28.1221,20.9112 27.9503,21.2485C27.6866,21.7659 27.2659,22.1866 26.7485,22.4503C26.4112,22.6221 26.0546,22.6892 25.6747,22.7203C25.3104,22.75 24.8646,22.75 24.3304,22.75H14.6696C14.1354,22.75 13.6896,22.75 13.3253,22.7203C12.9454,22.6892 12.5888,22.6221 12.2515,22.4503C11.7341,22.1866 11.3134,21.7659 11.0497,21.2485C10.8779,20.9112 10.8108,20.5546 10.7797,20.1747C10.75,19.8104 10.75,19.3646 10.75,18.8304L10.75,17C10.75,16.5858 11.0858,16.25 11.5,16.25Z"
    private const val BACKSPACE_PATH = "M16.4627,12L16.5426,12.0001H23.9155L23.9442,12.0001C24.4798,12.0001 24.9239,12 25.2861,12.0296C25.6628,12.0604 26.0122,12.1267 26.3412,12.2943C26.8493,12.5532 27.2623,12.9662 27.5212,13.4743C27.6888,13.8033 27.7551,14.1527 27.7859,14.5295C27.8155,14.8916 27.8155,15.3357 27.8155,15.8713V15.9001V21.5001V21.5288C27.8155,22.0644 27.8155,22.5085 27.7859,22.8707C27.7551,23.2475 27.6888,23.5968 27.5212,23.9258C27.2623,24.4339 26.8493,24.8469 26.3412,25.1058C26.0122,25.2734 25.6628,25.3397 25.286,25.3705C24.9239,25.4001 24.4798,25.4001 23.9442,25.4001H23.9155H16.5426L16.4627,25.4001H16.4627C15.9835,25.4004 15.6155,25.4006 15.2657,25.3055C14.9575,25.2217 14.6666,25.0839 14.4066,24.8985C14.1114,24.688 13.8785,24.4032 13.5752,24.0322L13.5246,23.9703L11.2328,21.1703L11.2167,21.1507L11.2167,21.1507L11.2167,21.1507C10.9348,20.8063 10.6993,20.5185 10.5224,20.2664C10.3371,20.0022 10.1873,19.7381 10.101,19.4316C9.9663,18.9532 9.9663,18.4469 10.101,17.9685C10.1873,17.662 10.3371,17.3979 10.5224,17.1337C10.6993,16.8816 10.9349,16.5938 11.2167,16.2494L11.2328,16.2298L13.5246,13.4298L13.5752,13.368C13.8785,12.997 14.1114,12.7121 14.4066,12.5017C14.6666,12.3162 14.9575,12.1784 15.2657,12.0946C15.6155,11.9995 15.9835,11.9998 16.4627,12ZM16.5426,13.4001C15.9472,13.4001 15.7794,13.4058 15.633,13.4456C15.4846,13.4859 15.3446,13.5523 15.2194,13.6416C15.0959,13.7296 14.9851,13.8559 14.608,14.3166L12.3162,17.1166C12.0141,17.4856 11.812,17.7332 11.6684,17.9378C11.53,18.1352 11.4755,18.2525 11.4486,18.3478C11.3838,18.5782 11.3838,18.8219 11.4486,19.0523C11.4755,19.1476 11.53,19.265 11.6684,19.4623C11.812,19.6669 12.0141,19.9145 12.3162,20.2835L14.608,23.0835C14.9851,23.5443 15.0959,23.6705 15.2194,23.7586C15.3446,23.8478 15.4846,23.9142 15.633,23.9545C15.7794,23.9943 15.9472,24.0001 16.5426,24.0001H23.9155C24.4871,24.0001 24.8737,23.9995 25.172,23.9751C25.4623,23.9514 25.6069,23.9087 25.7056,23.8584C25.9502,23.7337 26.1491,23.5349 26.2738,23.2902C26.3241,23.1915 26.3668,23.0469 26.3905,22.7567C26.4149,22.4583 26.4155,22.0717 26.4155,21.5001V15.9001C26.4155,15.3285 26.4149,14.9419 26.3905,14.6435C26.3668,14.3533 26.3241,14.2087 26.2738,14.1099C26.1491,13.8653 25.9502,13.6664 25.7056,13.5418C25.6069,13.4914 25.4623,13.4487 25.172,13.425C24.8737,13.4006 24.4871,13.4001 23.9155,13.4001H16.5426ZM22.732,21.3161C22.4587,21.5895 22.0155,21.5895 21.7421,21.3162L20.1158,19.69L18.4895,21.3163C18.2162,21.5897 17.773,21.5897 17.4996,21.3163C17.2262,21.043 17.2262,20.5997 17.4996,20.3264L19.1258,18.7L17.4993,17.0737C17.226,16.8003 17.226,16.3571 17.4993,16.0837C17.7727,15.8103 18.2159,15.8103 18.4893,16.0837L20.1157,17.7101L21.7421,16.0836C22.0154,15.8102 22.4586,15.8102 22.732,16.0836C23.0054,16.3569 23.0054,16.8002 22.732,17.0735L21.1057,18.7L22.732,20.3262C23.0054,20.5995 23.0054,21.0428 22.732,21.3161Z"
    private const val GEAR_PATH = "M8.1896,0.059C7.8196,-0.0197 7.437,-0.0197 7.0669,0.059C6.6463,0.1484 6.2628,0.3702 5.7627,0.6593L5.6783,0.7081L1.9501,2.8606L1.8656,2.9093C1.3652,3.1978 0.9814,3.419 0.6936,3.7387C0.4404,4.0198 0.2492,4.3511 0.1322,4.7109C-0.0007,5.12 -0.0004,5.563 0,6.1406L0.0001,6.2381V10.5431L0,10.6406C-0.0004,11.2182 -0.0007,11.6612 0.1322,12.0702C0.2492,12.4301 0.4404,12.7614 0.6936,13.0426C0.9814,13.3622 1.3652,13.5834 1.8656,13.8719L1.8656,13.8719L1.9501,13.9206L5.6783,16.0731L5.7627,16.1219C6.2628,16.411 6.6463,16.6328 7.0669,16.7222C7.437,16.8009 7.8196,16.8009 8.1896,16.7222C8.6103,16.6328 8.9938,16.411 9.4939,16.1219L9.5783,16.0731L13.3065,13.9206L13.391,13.8719C13.8914,13.5834 14.2752,13.3622 14.563,13.0426C14.8162,12.7614 15.0074,12.4301 15.1243,12.0702C15.2572,11.6612 15.2569,11.2182 15.2565,10.6406L15.2565,10.5431V6.2381L15.2565,6.1406C15.2569,5.563 15.2572,5.12 15.1243,4.7109C15.0074,4.3511 14.8162,4.0198 14.563,3.7387C14.2752,3.419 13.8914,3.1978 13.391,2.9093L13.3065,2.8606L9.5783,0.7081L9.4939,0.6593C8.9938,0.3702 8.6103,0.1484 8.1896,0.059ZM7.358,1.4284C7.5362,1.3905 7.7204,1.3905 7.8986,1.4284C8.0742,1.4657 8.2545,1.5604 8.8783,1.9206L12.6065,4.0731C13.2303,4.4332 13.4024,4.542 13.5226,4.6754C13.6445,4.8108 13.7366,4.9703 13.7929,5.1436C13.8484,5.3144 13.8565,5.5178 13.8565,6.2381V10.5431C13.8565,11.2634 13.8484,11.4668 13.7929,11.6376C13.7366,11.8109 13.6445,11.9704 13.5226,12.1058C13.4024,12.2392 13.2303,12.348 12.6065,12.7081L8.8783,14.8606C8.2545,15.2208 8.0742,15.3155 7.8986,15.3528C7.7204,15.3907 7.5362,15.3907 7.358,15.3528C7.1824,15.3155 7.0021,15.2208 6.3783,14.8606L2.6501,12.7081C2.0263,12.348 1.8542,12.2392 1.734,12.1058C1.6121,11.9704 1.52,11.8109 1.4637,11.6376C1.4082,11.4668 1.4001,11.2634 1.4001,10.5431V6.2381C1.4001,5.5178 1.4082,5.3144 1.4637,5.1436C1.52,4.9703 1.6121,4.8108 1.734,4.6754C1.8542,4.542 2.0263,4.4332 2.6501,4.0731L6.3783,1.9206C7.0021,1.5604 7.1824,1.4657 7.358,1.4284ZM5.3283,8.3906C5.3283,7.1203 6.358,6.0906 7.6283,6.0906C8.8986,6.0906 9.9283,7.1203 9.9283,8.3906C9.9283,9.6608 8.8986,10.6906 7.6283,10.6906C6.358,10.6906 5.3283,9.6608 5.3283,8.3906ZM7.6283,4.6906C5.5848,4.6906 3.9283,6.3471 3.9283,8.3906C3.9283,10.4341 5.5848,12.0906 7.6283,12.0906C9.6718,12.0906 11.3283,10.4341 11.3283,8.3906C11.3283,6.3471 9.6718,4.6906 7.6283,4.6906Z"
    private const val KEYBOARD_PATH = "M3.9,0H3.8713H3.8713C3.3356,-0 2.8916,-0 2.5294,0.0296C2.1526,0.0604 1.8033,0.1266 1.4742,0.2943C0.9662,0.5531 0.5531,0.9662 0.2943,1.4742C0.1266,1.8033 0.0604,2.1526 0.0296,2.5294C-0,2.8916 -0,3.3356 0,3.8713V3.8713V3.9V9.5V9.5287V9.5287C-0,10.0644 -0,10.5084 0.0296,10.8706C0.0604,11.2474 0.1266,11.5967 0.2943,11.9258C0.5531,12.4338 0.9662,12.8469 1.4742,13.1057C1.8033,13.2734 2.1526,13.3396 2.5294,13.3704C2.8916,13.4 3.3356,13.4 3.8713,13.4H3.9H13.5H13.5287C14.0644,13.4 14.5084,13.4 14.8706,13.3704C15.2474,13.3396 15.5967,13.2734 15.9258,13.1057C16.4338,12.8469 16.8469,12.4338 17.1057,11.9258C17.2734,11.5967 17.3396,11.2474 17.3704,10.8706C17.4,10.5084 17.4,10.0643 17.4,9.5287V9.5V3.9V3.8713C17.4,3.3357 17.4,2.8916 17.3704,2.5294C17.3396,2.1526 17.2734,1.8033 17.1057,1.4742C16.8469,0.9662 16.4338,0.5531 15.9258,0.2943C15.5967,0.1266 15.2474,0.0604 14.8706,0.0296C14.5084,-0 14.0644,-0 13.5287,0H13.5287H13.5H3.9ZM2.1098,1.5417C2.2086,1.4914 2.3532,1.4486 2.6434,1.4249C2.9418,1.4006 3.3284,1.4 3.9,1.4H13.5C14.0716,1.4 14.4582,1.4006 14.7566,1.4249C15.0468,1.4486 15.1914,1.4914 15.2902,1.5417C15.5348,1.6663 15.7337,1.8652 15.8583,2.1098C15.9086,2.2086 15.9514,2.3532 15.9751,2.6434C15.9995,2.9418 16,3.3284 16,3.9V9.5C16,10.0716 15.9995,10.4582 15.9751,10.7566C15.9514,11.0468 15.9086,11.1914 15.8583,11.2902C15.7337,11.5348 15.5348,11.7337 15.2902,11.8583C15.1914,11.9086 15.0468,11.9514 14.7566,11.9751C14.4582,11.9995 14.0716,12 13.5,12H3.9C3.3284,12 2.9418,11.9995 2.6434,11.9751C2.3532,11.9514 2.2086,11.9086 2.1098,11.8583C1.8652,11.7337 1.6663,11.5348 1.5417,11.2902C1.4914,11.1914 1.4486,11.0468 1.4249,10.7566C1.4006,10.4582 1.4,10.0716 1.4,9.5V3.9C1.4,3.3284 1.4006,2.9418 1.4249,2.6434C1.4486,2.3532 1.4914,2.2086 1.5417,2.1098C1.6663,1.8652 1.8652,1.6663 2.1098,1.5417ZM6.4,9.2C6.0134,9.2 5.7,9.5134 5.7,9.9C5.7,10.2866 6.0134,10.6 6.4,10.6H11C11.3866,10.6 11.7,10.2866 11.7,9.9C11.7,9.5134 11.3866,9.2 11,9.2H6.4ZM3.2,3.7C3.2,3.1477 3.6477,2.7 4.2,2.7C4.7523,2.7 5.2,3.1477 5.2,3.7C5.2,4.2523 4.7523,4.7 4.2,4.7C3.6477,4.7 3.2,4.2523 3.2,3.7ZM7.2,2.7C6.6477,2.7 6.2,3.1477 6.2,3.7C6.2,4.2523 6.6477,4.7 7.2,4.7C7.7523,4.7 8.2,4.2523 8.2,3.7C8.2,3.1477 7.7523,2.7 7.2,2.7ZM9.2,3.7C9.2,3.1477 9.6477,2.7 10.2,2.7C10.7523,2.7 11.2,3.1477 11.2,3.7C11.2,4.2523 10.7523,4.7 10.2,4.7C9.6477,4.7 9.2,4.2523 9.2,3.7ZM13.2,2.7C12.6477,2.7 12.2,3.1477 12.2,3.7C12.2,4.2523 12.6477,4.7 13.2,4.7C13.7523,4.7 14.2,4.2523 14.2,3.7C14.2,3.1477 13.7523,2.7 13.2,2.7ZM3.2,6.7C3.2,6.1477 3.6477,5.7 4.2,5.7C4.7523,5.7 5.2,6.1477 5.2,6.7C5.2,7.2523 4.7523,7.7 4.2,7.7C3.6477,7.7 3.2,7.2523 3.2,6.7ZM7.2,5.7C6.6477,5.7 6.2,6.1477 6.2,6.7C6.2,7.2523 6.6477,7.7 7.2,7.7C7.7523,7.7 8.2,7.2523 8.2,6.7C8.2,6.1477 7.7523,5.7 7.2,5.7ZM9.2,6.7C9.2,6.1477 9.6477,5.7 10.2,5.7C10.7523,5.7 11.2,6.1477 11.2,6.7C11.2,7.2523 10.7523,7.7 10.2,7.7C9.6477,7.7 9.2,7.2523 9.2,6.7ZM13.2,5.7C12.6477,5.7 12.2,6.1477 12.2,6.7C12.2,7.2523 12.6477,7.7 13.2,7.7C13.7523,7.7 14.2,7.2523 14.2,6.7C14.2,6.1477 13.7523,5.7 13.2,5.7Z"
    private const val ENTER_PILL_PATH = "M16,0L70,0A16,16 0,0 1,86 16L86,16A16,16 0,0 1,70 32L16,32A16,16 0,0 1,0 16L0,16A16,16 0,0 1,16 0z"
    private const val ENTER_GLYPH_PATH = "M50.8,11C50.8,10.5582 50.4418,10.2 50,10.2C49.5582,10.2 49.2,10.5582 49.2,11V13.8C49.2,14.3733 49.1994,14.7551 49.1754,15.0485C49.1522,15.3325 49.1112,15.4625 49.0692,15.5448C48.9542,15.7706 48.7706,15.9542 48.5448,16.0692C48.4625,16.1112 48.3325,16.1522 48.0485,16.1754C47.7551,16.1994 47.3733,16.2 46.8,16.2H36.9314L39.5657,13.5657C39.8781,13.2533 39.8781,12.7467 39.5657,12.4343C39.2533,12.1219 38.7467,12.1219 38.4343,12.4343L34.4657,16.4029C34.136,16.7327 34.136,17.2673 34.4657,17.5971L38.4343,21.5657C38.7467,21.8781 39.2533,21.8781 39.5657,21.5657C39.8781,21.2533 39.8781,20.7467 39.5657,20.4343L36.9314,17.8H46.8L46.8321,17.8C47.3648,17.8 47.8124,17.8 48.1787,17.7701C48.5617,17.7388 48.9257,17.6709 49.2712,17.4948C49.798,17.2264 50.2264,16.798 50.4948,16.2712C50.6709,15.9257 50.7388,15.5617 50.7701,15.1788C50.8,14.8125 50.8,14.3649 50.8,13.8322V13.8321V13.8V11Z"
    private const val HANDLE_PATH = "M2,0L38,0A2,2 0,0 1,40 2L40,2A2,2 0,0 1,38 4L2,4A2,2 0,0 1,0 2L0,2A2,2 0,0 1,2 0z"
}
