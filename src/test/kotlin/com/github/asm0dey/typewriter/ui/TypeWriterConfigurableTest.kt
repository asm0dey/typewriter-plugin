package com.github.asm0dey.typewriter.ui

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.junit5.RunInEdt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [TypeWriterConfigurable] cannot be shown headlessly -- there is no display in this JVM to
 * render a Settings panel into -- so its `isModified`/`apply`/`reset` substance is exercised
 * directly here, against the exact Swing fields [TypeWriterConfigurable.createComponent] lays
 * out (they are `internal`, not `private`, for exactly this reason). No test in this file ever
 * calls `createComponent()` or shows a window: setting `.text` / `.value` / `.isSelected` on a
 * Swing component works fine headless, only painting it needs a real display.
 *
 * Every field the panel edits gets its own isModified test -- a Configurable whose isModified
 * misses a field silently discards that field's edit when the user presses OK.
 */
@RunInEdt(writeIntent = true)
class TypeWriterConfigurableTest : TypeWriterFixtureTestCase() {

    private fun settings() = ApplicationManager.getApplication().getService(TypeWriterSettings::class.java)

    private lateinit var savedState: TypeWriterSettings.State

    private fun rememberState() {
        if (!::savedState.isInitialized) savedState = settings().state
    }

    @AfterEach
    fun restoreSettings() {
        if (::savedState.isInitialized) settings().loadState(savedState)
    }

    private val knownState = TypeWriterSettings.State(
        globalDir = "/home/demo/.typewriter",
        speedMs = 111,
        jitterMs = 22,
        newlineMs = 333,
        sentinel = "tw:",
        formatOnPlay = true,
    )

    private fun configurableWithKnownState(): TypeWriterConfigurable {
        rememberState()
        settings().loadState(knownState)
        val configurable = TypeWriterConfigurable()
        configurable.reset()
        return configurable
    }

    @Test
    fun testDisplayName() {
        assertEquals("TypeWriter", configurableWithKnownState().displayName)
    }

    @Test
    fun testResetLoadsCurrentSettingsIntoEveryField() {
        val configurable = configurableWithKnownState()
        assertEquals(knownState.globalDir, configurable.globalDir.text)
        assertEquals(knownState.speedMs, configurable.speed.value)
        assertEquals(knownState.jitterMs, configurable.jitter.value)
        assertEquals(knownState.newlineMs, configurable.newline.value)
        assertEquals(knownState.sentinel, configurable.sentinel.text)
        assertEquals(knownState.formatOnPlay, configurable.formatOnPlay.isSelected)
    }

    @Test
    fun testIsModifiedFalseImmediatelyAfterReset() {
        assertFalse(configurableWithKnownState().isModified)
    }

    @Test
    fun testIsModifiedDetectsGlobalDirChange() {
        val configurable = configurableWithKnownState()
        configurable.globalDir.text = "/somewhere/else"
        assertTrue(configurable.isModified)
    }

    @Test
    fun testIsModifiedDetectsSpeedChange() {
        val configurable = configurableWithKnownState()
        configurable.speed.value = knownState.speedMs + 1
        assertTrue(configurable.isModified)
    }

    @Test
    fun testIsModifiedDetectsJitterChange() {
        val configurable = configurableWithKnownState()
        configurable.jitter.value = knownState.jitterMs + 1
        assertTrue(configurable.isModified)
    }

    @Test
    fun testIsModifiedDetectsNewlineChange() {
        val configurable = configurableWithKnownState()
        configurable.newline.value = knownState.newlineMs + 1
        assertTrue(configurable.isModified)
    }

    @Test
    fun testIsModifiedDetectsSentinelChange() {
        val configurable = configurableWithKnownState()
        configurable.sentinel.text = "xx:"
        assertTrue(configurable.isModified)
    }

    @Test
    fun testIsModifiedDetectsFormatOnPlayChange() {
        val configurable = configurableWithKnownState()
        configurable.formatOnPlay.isSelected = !knownState.formatOnPlay
        assertTrue(configurable.isModified)
    }

    @Test
    fun testApplyPersistsEveryFieldAndClearsModified() {
        val configurable = configurableWithKnownState()
        configurable.globalDir.text = "/new/dir"
        configurable.speed.value = 900
        configurable.jitter.value = 40
        configurable.newline.value = 700
        configurable.sentinel.text = "go:"
        configurable.formatOnPlay.isSelected = !knownState.formatOnPlay

        configurable.apply()

        val persisted = settings().state
        assertEquals("/new/dir", persisted.globalDir)
        assertEquals(900, persisted.speedMs)
        assertEquals(40, persisted.jitterMs)
        assertEquals(700, persisted.newlineMs)
        assertEquals("go:", persisted.sentinel)
        assertEquals(!knownState.formatOnPlay, persisted.formatOnPlay)
        assertFalse(configurable.isModified)
    }

    @Test
    fun testResetAfterExternalSettingsChangePicksUpNewValues() {
        val configurable = configurableWithKnownState()
        settings().loadState(knownState.copy(speedMs = 555))

        configurable.reset()

        assertEquals(555, configurable.speed.value)
        assertFalse(configurable.isModified)
    }
}
