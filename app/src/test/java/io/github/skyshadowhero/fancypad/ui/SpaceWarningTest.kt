package io.github.skyshadowhero.fancypad.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 尺寸过大告警的阈值规则。
 *
 * AppShell 里「间隙」和「按键间距」共用这一套判定，两处标准必须一致；
 * 改比例/改成 >= 都会让提示变味，所以钉住。
 */
class SpaceWarningTest {

    @Test
    fun thresholdIsSixtyPercentOfMax() {
        assertEquals(60f, SpaceWarning.threshold(100f), 0.0001f)
        assertEquals(0f, SpaceWarning.threshold(0f), 0.0001f)
    }

    @Test
    fun itemAtThresholdIsNotExceeded() {
        // 恰好等于阈值不算超限（严格大于才告警）
        val item = SpaceWarning.Item("间隙", 60f, 100f)
        assertEquals(60f, item.limit, 0.0001f)
        assertFalse(item.exceeded)
    }

    @Test
    fun itemAboveThresholdIsExceeded() {
        assertTrue(SpaceWarning.Item("间隙", 60.5f, 100f).exceeded)
        assertTrue(SpaceWarning.Item("间隙", 99f, 100f).exceeded)
    }

    @Test
    fun exceededKeepsInputOrderAndDropsSafeItems() {
        val items = listOf(
            SpaceWarning.Item("a", 10f, 100f),   // 安全
            SpaceWarning.Item("b", 90f, 100f),   // 超限
            SpaceWarning.Item("c", 70f, 100f),   // 超限
        )
        val out = SpaceWarning.exceeded(items)
        assertEquals(listOf("b", "c"), out.map { it.label })
    }

    @Test
    fun emptyInputYieldsNoWarning() {
        assertTrue(SpaceWarning.exceeded(emptyList()).isEmpty())
    }
}
