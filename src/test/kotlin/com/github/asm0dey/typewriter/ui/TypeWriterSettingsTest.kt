package com.github.asm0dey.typewriter.ui

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.testFramework.junit5.RunInEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Resolves both services through the real platform service registry (ApplicationManager /
// Project.getService), which is why this needs the fixture's live IDE application and project
// rather than plain construction — that's the whole point of testDefaults()'s sibling tests below.
@RunInEdt(writeIntent = true)
class TypeWriterSettingsTest : TypeWriterFixtureTestCase() {

    @Test
    fun testDefaults() {
        val state = TypeWriterSettings.State()
        assertEquals(100, state.speedMs)
        assertEquals(20, state.jitterMs)
        assertEquals(300, state.newlineMs)
        assertEquals("tw:", state.sentinel)
        assertTrue(state.formatOnPlay)
        // The property that matters is OS-correctness, not a literal path: the default lives under
        // the JetBrains COMMON data directory, which carries no product or version segment, so one
        // toolkit serves every IDE the speaker demos in. Asserting equality with
        // PathManager.getCommonDataPath() rather than a hard-coded "~/..." is what keeps this
        // meaningful on Windows and macOS, where the old `~/.typewriter` default was simply wrong.
        assertEquals(
            PathManager.getCommonDataPath().resolve("typewriter").toString(),
            state.globalDir,
        )
        assertFalse(
            PathManager.getCommonDataPath().toString().contains("IntelliJIdea"),
            "the global toolkit must not sit under a product-versioned path -- it is shared across IDEs",
        )
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

    // Registration proof: TypeWriterSettings carries no plugin.xml <applicationService> entry
    // (it is a light service — @Service(APP) alone is the registration). If the light-service
    // mechanism ever failed to pick the class up, this call would return null.
    @Test
    fun testApplicationServiceResolvesThroughTheRealServiceRegistry() {
        val settings = ApplicationManager.getApplication().getService(TypeWriterSettings::class.java)
        assertNotNull(settings)
    }

    // Same proof at project level: no <projectService> entry either, @Service(PROJECT) alone
    // registers it as a light service scoped to this fixture's project.
    @Test
    fun testProjectServiceResolvesThroughTheRealServiceRegistry() {
        val settings = fixture.project.getService(TypeWriterProjectSettings::class.java)
        assertNotNull(settings)
    }
}
