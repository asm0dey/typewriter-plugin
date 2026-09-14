package com.github.asm0dey.typewriter.library

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
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

    // Spec section 8 "Actions": actions are "added to a declared group so they cluster in the
    // keymap tree" -- <group id="typewriter.snippets" .../> in plugin.xml. Registering must join
    // that group, not just the ActionManager registry; unregistering must leave it again. A
    // second register() call with the same path must not add a duplicate child.
    @Test
    fun testRegisteredActionJoinsTheDeclaredGroupAndLeavesItOnUnregister() {
        val relativePath = "grouped.java"
        val id = SnippetLibrary.actionId(relativePath)
        val group = ActionManager.getInstance().getAction("typewriter.snippets") as DefaultActionGroup

        SnippetRegistrar.register(listOf(relativePath))
        val action = ActionManager.getInstance().getAction(id)!!
        assertTrue(group.containsAction(action), "newly registered action should join the declared group")

        // Idempotent: re-registering the same path must not add a second child for it.
        SnippetRegistrar.register(listOf(relativePath))
        assertEquals(1, group.getChildren(ActionManager.getInstance()).count { it === action })

        SnippetRegistrar.register(emptyList())
        assertFalse(group.containsAction(action), "unregistering should remove the action from the group")
    }
}
