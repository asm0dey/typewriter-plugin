package com.github.asm0dey.typewriter

import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.junit5.RunInEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * plugin.xml's notification group, both settings pages, and every static `<action>`/`<group>` in
 * `<actions>` now resolve their user-visible text through `messages/TypeWriterBundle.properties`
 * (bundle="messages.TypeWriterBundle" + key=..., or the platform's default `action.<id>.text` /
 * `group.<id>.text` key for the `<actions resource-bundle="...">` block) instead of an inline
 * attribute. A wrong bundle path or key name fails only at runtime -- a missing key falls back to
 * an empty string, not the raw key or the old literal -- so this drives the real platform
 * resolution (ActionManager, NotificationGroupManager) rather than just reading the properties
 * file, to catch a typo that a compile cannot.
 */
@RunInEdt(writeIntent = true)
class PluginXmlResourceBundleTest : TypeWriterFixtureTestCase() {

    @Test
    fun testActionTextsResolveThroughTheBundle() {
        val expected = mapOf(
            "typewriter.newSnippet" to "TypeWriter: New Snippet...",
            "typewriter.typeNext" to "TypeWriter: Type Next",
            "typewriter.typePrevious" to "TypeWriter: Type Previous",
            "typewriter.undoRun" to "TypeWriter: Undo Run",
            "typewriter.typeSnippet" to "TypeWriter: Type Snippet...",
            "typewriter.editSnippet" to "TypeWriter: Edit Snippet...",
        )
        val actionManager = ActionManager.getInstance()
        for ((id, text) in expected) {
            val action = actionManager.getAction(id)
            assertEquals(text, action?.templatePresentation?.text, "action \"$id\" text")
        }
    }

    @Test
    fun testGroupTextResolvesThroughTheBundle() {
        val group = ActionManager.getInstance().getAction("typewriter.snippets")
        assertEquals("TypeWriter", group?.templatePresentation?.text)
    }

    @Test
    fun testNotificationGroupDisplayNameResolvesThroughTheBundle() {
        assertEquals(true, NotificationGroupManager.getInstance().isGroupRegistered("TypeWriter"))
        assertEquals("TypeWriter", NotificationGroup.getGroupTitle("TypeWriter"))
    }
}
