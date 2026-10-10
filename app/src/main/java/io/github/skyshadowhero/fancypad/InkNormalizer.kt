package io.github.skyshadowhero.fancypad

/**
 * 笔迹坐标归一化：把一段字迹按包围盒映射到固定的书写区里（保比、留边、居中）。
 *
 * ## 为什么必须做
 *
 * 讯飞 HCR 引擎是按 [IflytekHcrEngine] 里设的**写区域**来归一化输入的。早先我们给的是
 * 整屏 `(0,0,3200,2136)` 并直接喂屏幕坐标 —— 真机结果是一个笔画几十像素的字被引擎看成
 * 一个「点」，候选是 `·`、`、`、`502`、`222` 这种东西（实测诊断见 `stylus_iflytek.txt`）。
 *
 * 所以：**由我们来做归一化** —— 把整段字迹缩放到 `0..SIZE` 的方正区域里，写区域也设成同一块。
 * 这样无论用户在哪写、写多大，进引擎的都是"填满书写区的一个字"。
 *
 * 注意这里是**等比缩放 + 居中**，不是拉伸：变形会让汉字更难认。
 */
internal object InkNormalizer {

    /** 归一化到的目标边长（引擎的写区域也设成这个正方形）。 */
    const val SIZE = 1000

    /** 四周留白比例：字迹贴边会让引擎的边界处理变差。 */
    private const val MARGIN_RATIO = 0.06f

    /** 最近一次归一化的包围盒，仅供诊断输出。 */
    @Volatile
    var lastBox: String = "-"
        private set

    /**
     * 把若干条笔迹（屏幕坐标）等比映射到 `[0,SIZE]²` 内并居中。
     *
     * 点太少（没有有效包围盒）时原样返回 —— 引擎那边自然会认不出来，不用特殊处理。
     */
    fun toBox(strokes: List<List<PencilEngine.Pt>>): List<List<PencilEngine.Pt>> {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var n = 0
        for (s in strokes) {
            for (p in s) {
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
                n++
            }
        }
        if (n == 0 || minX > maxX || minY > maxY) return strokes

        val w = maxX - minX
        val h = maxY - minY
        // 退化成一维（横线/竖线）时给一个占位尺寸，避免除零
        val extent = maxOf(w, h, 1f)

        val inner = SIZE * (1f - 2f * MARGIN_RATIO)
        val scale = inner / extent
        // 居中：把包围盒中心对到区域中心
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val half = SIZE / 2f

        lastBox = "src=${w.toInt()}x${h.toInt()}@(${minX.toInt()},${minY.toInt()}) scale=${"%.2f".format(scale)} pts=$n"

        return strokes.map { stroke ->
            stroke.map { p ->
                PencilEngine.Pt(
                    half + (p.x - cx) * scale,
                    half + (p.y - cy) * scale,
                    p.action,
                    p.time,
                )
            }
        }
    }
}
