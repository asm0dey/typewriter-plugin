package com.github.asm0dey.typewriterplugin

import com.github.asm0dey.typewriterplugin.commands.PauseCommand
import com.github.asm0dey.typewriterplugin.commands.ReformatCommand
import com.github.asm0dey.typewriterplugin.commands.WriteCharCommand
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.components.service
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.DumbAwareAction
import javax.swing.KeyStroke

class TypeWriterAction : DumbAwareAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val dialog = TypeWriterDialog(project)

        if (!dialog.showAndGet()) return

        // Save snippet if shortcut is provided
        if (dialog.shortcut.isNotBlank()) {
            val snippetStorage = service<SnippetStorage>()

            // Check if there's an existing snippet with the same shortcut
            val existingSnippet = snippetStorage.getSnippet(dialog.shortcut)

            val snippet = Snippet(
                dialog.shortcut,
                dialog.text,
                dialog.delay,
                dialog.jitter,
                dialog.openingSequence,
                dialog.closingSequence
            )

            // If there's an existing snippet with the same shortcut, unregister its action
            if (existingSnippet != null) {
                val oldActionId = "com.github.asm0dey.typewriterplugin.DirectSnippetExecution.${existingSnippet.shortcut}"
                ActionManager.getInstance().unregisterAction(oldActionId)

                // Remove the old keyboard shortcut
                try {
                    val keymap = KeymapManager.getInstance().activeKeymap
                    val shortcuts = keymap.getShortcuts(oldActionId)
                    for (shortcut in shortcuts) {
                        keymap.removeShortcut(oldActionId, shortcut)
                    }
                } catch (e: Exception) {
                    // Log error or handle any issues
                    println("Failed to remove shortcut for snippet: ${existingSnippet.shortcut}")
                }
            }

            snippetStorage.addSnippet(snippet)

            // Register the action for this shortcut
            val actionId = "com.github.asm0dey.typewriterplugin.DirectSnippetExecution.${dialog.shortcut}"
            val action = DirectSnippetExecutionAction(snippet)
            ActionManager.getInstance().registerAction(actionId, action)

            // Register the keyboard shortcut
            val keyStroke = KeyStroke.getKeyStroke(dialog.shortcut)
            if (keyStroke != null) {
                val shortcut = KeyboardShortcut(keyStroke, null)
                val keymap = KeymapManager.getInstance().activeKeymap
                keymap.addShortcut(actionId, shortcut)
            }
        }
    }

    companion object {
        fun executeTyping(
            e: AnActionEvent,
            text: String,
            openingSequence: String,
            closingSequence: String,
            delay: Long,
            jitter: Int,
            scheduler: TypewriterExecutorService
        ) {
            val escapedOpeningSequence = Regex.escape(openingSequence)
            val escapedClosingSequence = Regex.escape(closingSequence)
            val matches = """$escapedOpeningSequence(.*?)$escapedClosingSequence""".toRegex().findAll(text).iterator()
            var cur: MatchResult? = null
            while (true) {
                val next = if (matches.hasNext()) matches.next() else null
                val content = text.substring(cur?.range?.last?.plus(1) ?: 0, next?.range?.first ?: text.length)
                val commands = WriteCharCommand.fromText(e, content, delay.toInt(), jitter)
                for (command in commands) scheduler.enqueue(command)
                if (next != null) {
                    val (command, value) = next
                        .value
                        .substringAfter(openingSequence)
                        .substringBeforeLast(closingSequence)
                        .trim()
                        .split(':')
                        .map(String::trim)
                    when (command) {
                        "pause" -> scheduler.enqueue(PauseCommand(value.toLong()))
                        "reformat" -> scheduler.enqueue(ReformatCommand(e))
                    }
                }
                cur = next
                if (cur == null) break
            }
        }
    }
}
