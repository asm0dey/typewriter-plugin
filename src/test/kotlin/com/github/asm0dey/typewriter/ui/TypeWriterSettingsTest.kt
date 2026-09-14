package com.github.asm0dey.typewriter.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TypeWriterSettingsTest {

    @Test
    fun testDefaults() {
        val state = TypeWriterSettings.State()
        assertEquals(100, state.speedMs)
        assertEquals(20, state.jitterMs)
        assertEquals(300, state.newlineMs)
        assertEquals("tw:", state.sentinel)
        assertTrue(state.formatOnPlay)
        assertTrue(state.globalDir.endsWith(".typewriter"))
    }

    @Test
    fun testDefaultTimingMirrorsState() {
        val settings = TypeWriterSettings()
        settings.loadState(TypeWriterSettings.State(speedMs = 42, jitterMs = 7, newlineMs = 9))
        val timing = settings.defaultTiming()
        assertEquals(42, timing.speedMs)
        assertEquals(7, timing.jitterMs)
        assertEquals(9, timing.newlineMs)
    }

    @Test
    fun testProjectDirDefault() {
        assertEquals(".typewriter", TypeWriterProjectSettings.State().projectDir)
    }
}
