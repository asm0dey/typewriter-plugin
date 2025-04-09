package com.github.asm0dey.typewriterplugin

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.components.service
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import javax.swing.KeyStroke

/**
 * Action to register keyboard shortcuts for snippets.
 * This action is called when the plugin is loaded to register all shortcuts for snippets.
 */
class RegisterSnippetShortcutsAction {
    companion object {
        /**
         * Registers keyboard shortcuts for all snippets.
         * This method is called when the plugin is loaded.
         */
        fun registerShortcuts() {
            val snippetStorage = service<SnippetStorage>()
            val snippets = snippetStorage.getAllSnippets()
            val keymap = KeymapManager.getInstance().activeKeymap

            for (snippet in snippets) {
                if (snippet.shortcut.isNotBlank()) {
                    val actionId = "com.github.asm0dey.typewriterplugin.DirectSnippetExecution.${snippet.shortcut}"
                    val action = DirectSnippetExecutionAction(snippet)

                    // Register the action
                    ActionManager.getInstance().registerAction(actionId, action)

                    try {
                        // Check for valid modifier keys
                        val validModifiers = setOf("ctrl", "control", "meta", "alt", "shift", "altGraph", "button1", "button2", "button3")
                        val parts = snippet.shortcut.toLowerCase().split(" ").map { it.trim() }

                        // Check if all modifiers are valid
                        val modifiers = parts.dropLast(1)
                        val invalidModifiers = modifiers.filter { it !in validModifiers }

                        if (invalidModifiers.isEmpty()) {
                            // Parse the shortcut string and create a KeyboardShortcut
                            val keyStroke = KeyStroke.getKeyStroke(snippet.shortcut)
                            if (keyStroke != null) {
                                val shortcut = KeyboardShortcut(keyStroke, null)

                                // Add the shortcut to the keymap
                                keymap.addShortcut(actionId, shortcut)
                            }
                        }
                    } catch (e: Exception) {
                        // Log error or handle invalid shortcut format
                        println("Failed to register shortcut for snippet: ${snippet.shortcut}")
                    }
                }
            }
        }
    }
}

/**
 * Action to execute a specific snippet directly.
 * This action is triggered when the user presses the shortcut assigned to a snippet.
 */
class DirectSnippetExecutionAction(private val snippet: Snippet) : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val scheduler = service<TypewriterExecutorService>()
        TypeWriterAction.executeTyping(
            e,
            snippet.text,
            snippet.openingSequence,
            snippet.closingSequence,
            snippet.delay.toLong(),
            snippet.jitter,
            scheduler
        )
    }
}

/**
 * Startup activity that registers keyboard shortcuts for snippets when the plugin is loaded.
 */
class SnippetShortcutRegistrar : StartupActivity {
    override fun runActivity(project: Project) {
        RegisterSnippetShortcutsAction.registerShortcuts()
    }
}
