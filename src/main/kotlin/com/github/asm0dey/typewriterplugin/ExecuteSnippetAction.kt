package com.github.asm0dey.typewriterplugin

import com.github.asm0dey.typewriterplugin.TypeWriterBundle.message
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Action to execute a snippet by its shortcut.
 * This action is triggered when the user presses the shortcut assigned to a snippet.
 */
class ExecuteSnippetAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Show a dialog to select a snippet
        val snippetStorage = service<SnippetStorage>()
        val snippets = snippetStorage.getAllSnippets()

        if (snippets.isEmpty()) {
            return
        }

        val dialog = SnippetSelectionDialog(project, snippets)
        if (!dialog.showAndGet()) {
            return
        }

        val selectedSnippet = dialog.selectedSnippet ?: return

        // Execute the selected snippet
        val scheduler = service<TypewriterExecutorService>()
        TypeWriterAction.executeTyping(
            e,
            selectedSnippet.text,
            selectedSnippet.openingSequence,
            selectedSnippet.closingSequence,
            selectedSnippet.delay.toLong(),
            selectedSnippet.jitter,
            scheduler
        )
    }
}

/**
 * Dialog to select a snippet to execute.
 */
class SnippetSelectionDialog(
    project: Project,
    private val snippets: List<Snippet>
) : DialogWrapper(project) {
    var selectedSnippet: Snippet? = null

    init {
        title = message("snippet.selection.title")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label(message("snippet.selection.prompt"))
        }

        snippets.forEach { snippet ->
            row {
                button(snippet.shortcut) {
                    selectedSnippet = snippet
                    close(DialogWrapper.OK_EXIT_CODE)
                }

                // Show a preview of the snippet text
                val previewText = snippet.text.take(30) + if (snippet.text.length > 30) "…" else ""
                label(previewText)
            }
        }
    }
}
