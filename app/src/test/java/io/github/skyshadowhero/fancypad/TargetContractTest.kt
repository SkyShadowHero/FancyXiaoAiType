package io.github.skyshadowhero.fancypad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资源名表的契约测试。
 *
 * 这套表是「跨输入法版本」的唯一锚点：`XposedEntry.resolveNames` 只把
 * [Target.RES_NAMES] / [Target.MARGIN_NAMES] / [Target.FLOATING_NAMES] 里的名字
 * 解析成资源 ID 并登记进 `nameToId`，而 `resolveOverride` / `resolveScale` 全靠
 * `nameToId[...]` 命中。**常量写了但没登记进这三张表 = 那个设置项永远不生效**，
 * 而且不会报错（只是「拖了没反应」），是最难查的一类问题。
 */
class TargetContractTest {

    /** 三张登记表合起来 = nameToId 的全部来源。 */
    private val registered: Set<String> =
        Target.RES_NAMES.keys + Target.MARGIN_NAMES.toSet() + Target.FLOATING_NAMES.toSet()

    /** resolveOverride / resolveScale 里逐个比较的常量，必须都在登记表里。 */
    private val constantsUsedByOverride = listOf(
        Target.NAME_KEY_HEIGHT_LAND,
        Target.NAME_KEY_HEIGHT_PORT,
        Target.NAME_KEY_SPACING_LAND,
        Target.NAME_KEY_SPACING_PORT,
        Target.NAME_ROW_SPACING_LAND,
        Target.NAME_ROW_SPACING_PORT,
        Target.NAME_BUBBLE_CORNER,
        Target.NAME_CANDIDATE_CORNER,
        Target.NAME_CANDIDATE_SPACING,
        Target.NAME_TOOLBAR_SHADOW,
        Target.NAME_TOOLBAR_BUTTON_SPACING,
        Target.NAME_TOOLBAR_VPADDING,
        Target.NAME_TOOLBAR_HEIGHT,
        Target.NAME_TOOLBAR_HANDLE_HEIGHT,
        Target.NAME_TOOLBAR_HANDLE_OFFSET_TOP,
        Target.NAME_TOOLBAR_HANDLE_OFFSET_START,
        Target.NAME_TOOLBAR_PADDING_START,
        Target.NAME_TOOLBAR_PADDING_END,
        Target.NAME_TOOLBAR_BORDER_WIDTH,
        Target.NAME_CAND_WIN_MAX_WIDTH,
        Target.NAME_CAND_WIN_H_PADDING,
        Target.NAME_CAND_WIN_PINYIN_TOP,
        Target.NAME_CAND_WIN_PINYIN_BOTTOM,
        Target.NAME_CAND_WIN_SHADOW,
        Target.NAME_CAND_WIN_SPACING,
        Target.NAME_CAND_WIN_BORDER_WIDTH,
        Target.NAME_CAND_WIN_CAND_FONT,
        Target.NAME_CAND_WIN_CAND_LINE_HEIGHT,
        Target.NAME_CAND_WIN_NUMBER_FONT,
        Target.NAME_CAND_WIN_NUMBER_LINE_HEIGHT,
        Target.NAME_CAND_WIN_PINYIN_FONT,
        Target.NAME_CAND_WIN_PINYIN_LINE_HEIGHT,
    )

    @Test
    fun everyOverrideConstantIsRegistered() {
        val missing = constantsUsedByOverride.filterNot { it in registered }
        assertTrue("这些资源名没登记进 nameToId，对应设置项永远不会生效：$missing", missing.isEmpty())
    }

    @Test
    fun gapAndCornerNameGroupsAreInResNames() {
        // 这三组走的是 idIn(resId, Target.XXX_NAMES)，同样依赖 RES_NAMES 的键
        for (name in Target.GAP_LAND_NAMES + Target.GAP_PORT_NAMES + Target.CORNER_NAMES) {
            assertTrue("$name 不在 RES_NAMES 里", name in Target.RES_NAMES.keys)
        }
    }

    @Test
    fun floatingGroupsAreAllRegistered() {
        for (name in Target.CANDIDATE_NAMES + Target.FLOATBAR_NAMES) {
            assertTrue("$name 不在 FLOATING_NAMES 里", name in Target.FLOATING_NAMES)
        }
    }

    @Test
    fun resourceIdsAreNonZeroAndUnique() {
        // 0 是「解析失败」的语义，出现 0 会让 nameToId 里留下永远不命中的项；
        // 重复 ID 通常意味着抄错了某个 resources.arsc 数值。
        val ids = Target.RES_NAMES.values.toList()
        assertTrue("RES_NAMES 里出现 0：${Target.RES_NAMES.filterValues { it == 0 }}", ids.none { it == 0 })
        assertEquals("RES_NAMES 里有重复的资源 ID", ids.size, ids.distinct().size)
    }

    @Test
    fun hardcodedGapIdsMatchTheirNames() {
        // 硬编码 ID 只作为「按名解析失败」时的兜底，必须与同名键一致，否则兜底会指错资源
        assertEquals(Target.RES_CENTER_GAP_LAND, Target.RES_NAMES["pad_qwerty_landscape_split_center_gap"])
        assertEquals(Target.RES_CENTER_GAP_PORT, Target.RES_NAMES["pad_qwerty_portrait_split_center_gap"])
        assertEquals(Target.RES_KEY_CORNER, Target.RES_NAMES["keyboard_key_corner_radius"])
        assertEquals(Target.RES_BUBBLE_CORNER, Target.RES_NAMES["key_preview_bubble_corner_radius"])
    }

    @Test
    fun gapIdSetsAreDisjoint() {
        // 横屏与竖屏两组不能有交集，否则同一次覆写会被两条分支抢
        val land = Target.LANDSCAPE_GAP_RES_IDS.toSet()
        val port = Target.PORTRAIT_GAP_RES_IDS.toSet()
        assertTrue("横竖屏间隙 ID 集合有交集", (land intersect port).isEmpty())
        assertNotEquals(0, land.size)
        assertNotEquals(0, port.size)
    }

    @Test
    fun materialGateMatchesLinkedKeySet() {
        // MaterialGate.decide 用类名字符串判定白名单集合；写成别的类名会静默失效
        assertEquals("java.util.LinkedHashMap\$LinkedKeySet", Target.MATERIAL_GATE_SET_CLASS)
    }
}
