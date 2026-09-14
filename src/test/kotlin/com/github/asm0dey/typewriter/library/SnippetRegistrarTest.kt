package com.github.asm0dey.typewriter.library

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.junit5.RunInEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@RunInEdt(writeIntent = true)
class SnippetRegistrarTest : TypeWriterFixtureTestCase() {

    @Test
    fun testRegisterAndUnregisterByRelativePath() {
        val id = SnippetLibrary.actionId("unit-test-snippet.java")
        SnippetRegistrar.register(listOf("unit-test-snippet.java"))
        assertNotNull(ActionManager.getInstance().getAction(id))
        assertTrue(SnippetRegistrar.registeredIds().contains(id))

        SnippetRegistrar.register(emptyList())
        assertNull(ActionManager.getInstance().getAction(id))
        assertFalse(SnippetRegistrar.registeredIds().contains(id))
    }

    @Test
    fun testRegisteringTwiceIsIdempotent() {
        SnippetRegistrar.register(listOf("idem.java"))
        SnippetRegistrar.register(listOf("idem.java"))
        assertNotNull(ActionManager.getInstance().getAction(SnippetLibrary.actionId("idem.java")))
        SnippetRegistrar.register(emptyList())
    }

    @Test
    fun testActionTextIsTheRelativePath() {
        SnippetRegistrar.register(listOf("jcon26/01.java"))
        val action = ActionManager.getInstance().getAction(SnippetLibrary.actionId("jcon26/01.java"))
        assertNotNull(action)
        assertEquals("Type: jcon26/01.java", action!!.templatePresentation.text)
        SnippetRegistrar.register(emptyList())
    }
}
