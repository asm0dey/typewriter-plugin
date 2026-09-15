package com.github.asm0dey.typewriter.ui

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.intellij.testFramework.junit5.RunInEdt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [TypeWriterProjectConfigurable] cannot be shown headlessly, so `isModified`/`apply`/`reset` are
 * exercised directly against its `internal` `projectDir` field, exactly like
 * [TypeWriterConfigurableTest] does for the app-level panel -- no test here calls
 * `createComponent()` or shows a window.
 */
@RunInEdt(writeIntent = true)
class TypeWriterProjectConfigurableTest : TypeWriterFixtureTestCase() {

    private fun settings() = fixture.project.getService(TypeWriterProjectSettings::class.java)

    private lateinit var savedState: TypeWriterProjectSettings.State

    private fun rememberState() {
        if (!::savedState.isInitialized) savedState = settings().state
    }

    @AfterEach
    fun restoreSettings() {
        if (::savedState.isInitialized) settings().loadState(savedState)
    }

    private fun configurableWithKnownState(dir: String = "01-jcon26"): TypeWriterProjectConfigurable {
        rememberState()
        settings().loadState(TypeWriterProjectSettings.State(projectDir = dir))
        val configurable = TypeWriterProjectConfigurable(fixture.project)
        configurable.reset()
        return configurable
    }

    @Test
    fun testDisplayName() {
        assertEquals("Project", configurableWithKnownState().displayName)
    }

    @Test
    fun testResetLoadsCurrentProjectDir() {
        val configurable = configurableWithKnownState("01-jcon26")
        assertEquals("01-jcon26", configurable.projectDir.text)
    }

    @Test
    fun testIsModifiedFalseImmediatelyAfterReset() {
        assertFalse(configurableWithKnownState().isModified)
    }

    @Test
    fun testIsModifiedDetectsProjectDirChange() {
        val configurable = configurableWithKnownState()
        configurable.projectDir.text = "somewhere-else"
        assertTrue(configurable.isModified)
    }

    @Test
    fun testApplyPersistsProjectDirAndClearsModified() {
        val configurable = configurableWithKnownState()
        configurable.projectDir.text = "talks/devnexus"

        configurable.apply()

        assertEquals("talks/devnexus", settings().state.projectDir)
        assertFalse(configurable.isModified)
    }

    @Test
    fun testResetAfterExternalSettingsChangePicksUpNewValue() {
        val configurable = configurableWithKnownState()
        settings().loadState(TypeWriterProjectSettings.State(projectDir = "changed-elsewhere"))

        configurable.reset()

        assertEquals("changed-elsewhere", configurable.projectDir.text)
        assertFalse(configurable.isModified)
    }
}
