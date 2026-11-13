package io.github.skyshadowhero.fancypad

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置常量契约。
 *
 * 每个滑块都是「min / default / max」三件套，界面用 min..max 做量程、Hook 侧用 default 兜底。
 * 三者一旦错位（默认值大于上限、上限小于下限），症状是滑块拖不动或一进来就告警，
 * 而且只在特定页面出现，很容易漏测。这里按表全量校验一遍。
 *
 * 不放进表的情况：单位不同的（如 [PrefKeys.CURSOR_SCALE_DEFAULT] 是百分比、
 * MIN/MAX 是倍率）、以及上限按屏幕宽度动态算的间隙（[PrefKeys.GAP_MAX_RATIO]）。
 */
class PrefKeysContractTest {

    private data class Range(val label: String, val min: Float, val default: Float, val max: Float)

    private val ranges = listOf(
        // 按键圆角 / 外边距
        Range("corner", PrefKeys.CORNER_MIN, PrefKeys.CORNER_DEFAULT, PrefKeys.CORNER_MAX),
        Range("margin", PrefKeys.MARGIN_MIN, PrefKeys.MARGIN_HORIZONTAL_DEFAULT, PrefKeys.MARGIN_MAX),
        // 触屏候选词
        Range(
            "candidate_corner",
            PrefKeys.CANDIDATE_CORNER_MIN, PrefKeys.CANDIDATE_CORNER_DEFAULT, PrefKeys.CANDIDATE_CORNER_MAX,
        ),
        Range(
            "candidate_spacing",
            PrefKeys.CANDIDATE_SPACING_MIN, PrefKeys.CANDIDATE_SPACING_DEFAULT, PrefKeys.CANDIDATE_SPACING_MAX,
        ),
        // 悬浮键盘：圆角
        Range(
            "floatbar_corner",
            PrefKeys.FLOATBAR_CORNER_MIN, PrefKeys.FLOATBAR_CORNER_DEFAULT, PrefKeys.FLOATBAR_CORNER_MAX,
        ),
        // 悬浮键盘：工具栏
        Range("toolbar_shadow", PrefKeys.TOOLBAR_SHADOW_MIN, PrefKeys.TOOLBAR_SHADOW_DEFAULT, PrefKeys.TOOLBAR_SHADOW_MAX),
        Range(
            "toolbar_button_spacing",
            PrefKeys.TOOLBAR_BUTTON_SPACING_MIN,
            PrefKeys.TOOLBAR_BUTTON_SPACING_DEFAULT,
            PrefKeys.TOOLBAR_BUTTON_SPACING_MAX,
        ),
        Range(
            "toolbar_vpadding",
            PrefKeys.TOOLBAR_VPADDING_MIN, PrefKeys.TOOLBAR_VPADDING_DEFAULT, PrefKeys.TOOLBAR_VPADDING_MAX,
        ),
        Range(
            "toolbar_padding_start",
            PrefKeys.TOOLBAR_SIDE_PADDING_MIN,
            PrefKeys.TOOLBAR_PADDING_START_DEFAULT,
            PrefKeys.TOOLBAR_SIDE_PADDING_MAX,
        ),
        Range(
            "toolbar_padding_end",
            PrefKeys.TOOLBAR_SIDE_PADDING_MIN,
            PrefKeys.TOOLBAR_PADDING_END_DEFAULT,
            PrefKeys.TOOLBAR_SIDE_PADDING_MAX,
        ),
        Range(
            "toolbar_handle_offset_start",
            PrefKeys.TOOLBAR_HANDLE_OFFSET_START_MIN,
            PrefKeys.TOOLBAR_HANDLE_OFFSET_START_DEFAULT,
            PrefKeys.TOOLBAR_HANDLE_OFFSET_START_MAX,
        ),
        // 悬浮键盘：候选窗口字号
        Range(
            "cand_win_cand_font",
            PrefKeys.CAND_WIN_CAND_FONT_MIN, PrefKeys.CAND_WIN_CAND_FONT_DEFAULT, PrefKeys.CAND_WIN_CAND_FONT_MAX,
        ),
        Range(
            "cand_win_number_font",
            PrefKeys.CAND_WIN_NUMBER_FONT_MIN,
            PrefKeys.CAND_WIN_NUMBER_FONT_DEFAULT,
            PrefKeys.CAND_WIN_NUMBER_FONT_MAX,
        ),
        Range(
            "cand_win_pinyin_font",
            PrefKeys.CAND_WIN_PINYIN_FONT_MIN,
            PrefKeys.CAND_WIN_PINYIN_FONT_DEFAULT,
            PrefKeys.CAND_WIN_PINYIN_FONT_MAX,
        ),
        // 悬浮键盘：候选窗口几何
        Range(
            "cand_win_max_width",
            PrefKeys.CAND_WIN_MAX_WIDTH_MIN,
            PrefKeys.CAND_WIN_MAX_WIDTH_DEFAULT,
            PrefKeys.CAND_WIN_MAX_WIDTH_MAX,
        ),
        Range(
            "cand_win_h_padding",
            PrefKeys.CAND_WIN_H_PADDING_MIN,
            PrefKeys.CAND_WIN_H_PADDING_DEFAULT,
            PrefKeys.CAND_WIN_H_PADDING_MAX,
        ),
        Range(
            "cand_win_pinyin_top",
            PrefKeys.CAND_WIN_PINYIN_MIN, PrefKeys.CAND_WIN_PINYIN_TOP_DEFAULT, PrefKeys.CAND_WIN_PINYIN_MAX,
        ),
        Range(
            "cand_win_pinyin_bottom",
            PrefKeys.CAND_WIN_PINYIN_MIN, PrefKeys.CAND_WIN_PINYIN_BOTTOM_DEFAULT, PrefKeys.CAND_WIN_PINYIN_MAX,
        ),
        Range(
            "cand_win_spacing",
            PrefKeys.CAND_WIN_SPACING_MIN, PrefKeys.CAND_WIN_SPACING_DEFAULT, PrefKeys.CAND_WIN_SPACING_MAX,
        ),
        // 描边宽度
        Range("border_width", PrefKeys.BORDER_WIDTH_MIN, PrefKeys.BORDER_WIDTH_DEFAULT, PrefKeys.BORDER_WIDTH_MAX),
        // 悬浮键盘倍率（下限 1 = 不缩小，默认就是输入法原生上限 1.1）
        Range(
            "float_kb_max_scale",
            PrefKeys.FLOAT_KB_MAX_SCALE_MIN,
            PrefKeys.FLOAT_KB_MAX_SCALE_DEFAULT,
            PrefKeys.FLOAT_KB_MAX_SCALE_MAX,
        ),
        // 右键菜单
        Range("appmenu_corner", PrefKeys.APPMENU_CORNER_MIN, PrefKeys.APPMENU_CORNER_DEFAULT, PrefKeys.APPMENU_CORNER_MAX),
        Range("appmenu_text", PrefKeys.APPMENU_TEXT_MIN, PrefKeys.APPMENU_TEXT_DEFAULT, PrefKeys.APPMENU_TEXT_MAX),
        Range(
            "appmenu_padding_h",
            PrefKeys.APPMENU_PADDING_H_MIN,
            PrefKeys.APPMENU_PADDING_H_DEFAULT,
            PrefKeys.APPMENU_PADDING_H_MAX,
        ),
        Range(
            "appmenu_padding_v",
            PrefKeys.APPMENU_PADDING_V_MIN,
            PrefKeys.APPMENU_PADDING_V_DEFAULT,
            PrefKeys.APPMENU_PADDING_V_MAX,
        ),
        // 随手写
        Range("stylus_delay", PrefKeys.STYLUS_DELAY_MIN, PrefKeys.STYLUS_DELAY_DEFAULT, PrefKeys.STYLUS_DELAY_MAX),
        Range(
            "stylus_ink_width",
            PrefKeys.STYLUS_INK_WIDTH_MIN, PrefKeys.STYLUS_INK_WIDTH_DEFAULT, PrefKeys.STYLUS_INK_WIDTH_MAX,
        ),
    )

    @Test
    fun everyRangeIsOrdered() {
        for (r in ranges) {
            assertTrue("${r.label}: min(${r.min}) > max(${r.max})", r.min < r.max)
            assertTrue(
                "${r.label}: 默认值 ${r.default} 不在 ${r.min}..${r.max} 内",
                r.default in r.min..r.max,
            )
        }
    }

    @Test
    fun keyHeightUsesItsOwnUpperBound() {
        // 键高默认 51.5dp，比「间距 40dp」的上限还大，所以它用单独的 SPACE_KEY_H_MAX；
        // 若有人把两者合并，这里的默认值就会越过安全阈值（60dp）直接触发告警
        assertTrue(PrefKeys.SPACE_KEY_H_MAX > PrefKeys.SPACE_MAX)
        assertTrue(PrefKeys.SPACE_KEY_H_LAND_DEFAULT <= PrefKeys.SPACE_KEY_H_MAX)
        assertTrue(PrefKeys.SPACE_KEY_H_PORT_DEFAULT <= PrefKeys.SPACE_KEY_H_MAX)
        assertTrue(PrefKeys.SPACE_KEY_HORIZ_LAND_DEFAULT <= PrefKeys.SPACE_MAX)
        assertTrue(PrefKeys.SPACE_KEY_HORIZ_PORT_DEFAULT <= PrefKeys.SPACE_MAX)
        assertTrue(PrefKeys.SPACE_ROW_LAND_DEFAULT <= PrefKeys.SPACE_MAX)
        assertTrue(PrefKeys.SPACE_ROW_PORT_DEFAULT <= PrefKeys.SPACE_MAX)
        // 默认键高必须落在「上限 6/10」的安全区内，否则一进页面就弹告警
        assertTrue(PrefKeys.SPACE_KEY_H_LAND_DEFAULT <= PrefKeys.SPACE_KEY_H_MAX * 0.6f)
    }

    @Test
    fun defaultPresetIsWithinCursorScaleRange() {
        // 光标大小：默认 100%，量程 30%~300%（界面按百分比，Hook 侧再换成倍率）
        assertTrue(PrefKeys.CURSOR_SCALE_DEFAULT >= PrefKeys.CURSOR_SCALE_MIN * 100)
        assertTrue(PrefKeys.CURSOR_SCALE_DEFAULT <= PrefKeys.CURSOR_SCALE_MAX * 100)
    }

    @Test
    fun floatingKeyboardDefaultMeansNoUnlock() {
        // 默认上限必须等于输入法原生上限，否则「关着开关也在放大」
        assertTrue(PrefKeys.FLOAT_KB_MAX_SCALE_DEFAULT <= PrefKeys.FLOAT_KB_NATIVE_MAX_SCALE)
        assertTrue(PrefKeys.FLOAT_KB_NATIVE_MIN_SCALE < PrefKeys.FLOAT_KB_NATIVE_MAX_SCALE)
    }

    @Test
    fun captionButtonOrderContainsEveryKeyOnce() {
        val keys = PrefKeys.CAPTION_BUTTON_DEFAULT_ORDER.split(',')
        val known = listOf(
            PrefKeys.CB_FULLSCREEN, PrefKeys.CB_CASTING, PrefKeys.CB_SPLIT_LEFT, PrefKeys.CB_SPLIT_RIGHT,
            PrefKeys.CB_FREEFORM, PrefKeys.CB_NEW_WINDOW, PrefKeys.CB_CLOSE, PrefKeys.CB_FORCE_CLOSE,
        )
        assertTrue("默认顺序里有重复 key", keys.size == keys.distinct().size)
        assertTrue("默认顺序漏了按钮：${known - keys.toSet()}", keys.toSet().containsAll(known))
    }
}
