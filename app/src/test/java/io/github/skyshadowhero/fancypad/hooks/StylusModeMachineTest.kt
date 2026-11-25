package io.github.skyshadowhero.fancypad.hooks

import io.github.skyshadowhero.fancypad.hooks.StylusModeMachine.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三态迁移的契约：屏幕键盘 / 已连接键盘 / 随手写。
 *
 * 这些用例对应真机上最容易错的几条：
 * - 连接实体键盘**不能**打断正在进行的随手写；
 * - 退出随手写后落在哪种状态，看退出那一刻有没有实体键盘，而不是进入前的状态；
 * - 延时展示面板用 generation 作废（避免把面板贴到已经退出的会话上）。
 */
class StylusModeMachineTest {

    @Test
    fun startsInSoftKeyboard() {
        val m = StylusModeMachine()
        assertEquals(Mode.SOFT_KEYBOARD, m.mode)
    }

    @Test
    fun keyboardAvailabilityOnlyTogglesBetweenSoftAndHard() {
        val m = StylusModeMachine()

        assertTrue(m.onKeyboardAvailability(true))
        assertEquals(Mode.HARD_KEYBOARD, m.mode)

        // 重复上报同一种可用性不产生迁移
        assertFalse(m.onKeyboardAvailability(true))
        assertEquals(Mode.HARD_KEYBOARD, m.mode)

        assertTrue(m.onKeyboardAvailability(false))
        assertEquals(Mode.SOFT_KEYBOARD, m.mode)
    }

    @Test
    fun connectingKeyboardDoesNotInterruptHandwriting() {
        val m = StylusModeMachine()
        m.enterStylus()

        assertFalse(m.onKeyboardAvailability(true))
        assertEquals(Mode.STYLUS, m.mode)

        assertFalse(m.onKeyboardAvailability(false))
        assertEquals(Mode.STYLUS, m.mode)
    }

    @Test
    fun enterStylusWorksFromBothRestingStates() {
        val fromSoft = StylusModeMachine()
        assertTrue(fromSoft.enterStylus())
        assertEquals(Mode.STYLUS, fromSoft.mode)

        val fromHard = StylusModeMachine()
        fromHard.onKeyboardAvailability(true)
        assertTrue(fromHard.enterStylus())
        assertEquals(Mode.STYLUS, fromHard.mode)

        // 已经在随手写里重复进入不算迁移，也不能作废排队中的面板展示
        assertFalse(fromHard.enterStylus())
    }

    @Test
    fun exitStylusLandsOnStateMatchingKeyboardAvailability() {
        val withKeyboard = StylusModeMachine()
        withKeyboard.onKeyboardAvailability(true)
        withKeyboard.enterStylus()
        assertTrue(withKeyboard.exitStylus(true))
        assertEquals(Mode.HARD_KEYBOARD, withKeyboard.mode)

        val withoutKeyboard = StylusModeMachine()
        withoutKeyboard.enterStylus()
        assertTrue(withoutKeyboard.exitStylus(false))
        assertEquals(Mode.SOFT_KEYBOARD, withoutKeyboard.mode)
    }

    @Test
    fun exitFromRestingStateIsNoOp() {
        val m = StylusModeMachine()
        val ticket = m.ticket()
        assertFalse(m.exitStylus(true))
        assertEquals(Mode.SOFT_KEYBOARD, m.mode)
        assertFalse(m.isStale(ticket))
    }

    @Test
    fun ticketBecomesStaleOnRealTransition() {
        val m = StylusModeMachine()
        m.onKeyboardAvailability(true)
        val ticket = m.ticket()

        assertFalse(m.isStale(ticket))
        m.enterStylus()
        assertTrue(m.isStale(ticket))
    }

    @Test
    fun resetReturnsToSoftKeyboardAndInvalidatesTickets() {
        val m = StylusModeMachine()
        m.onKeyboardAvailability(true)
        m.enterStylus()
        val ticket = m.ticket()

        m.reset()
        assertEquals(Mode.SOFT_KEYBOARD, m.mode)
        assertTrue(m.isStale(ticket))
    }
}
