package com.github.asm0dey.typewriter.ui

import com.github.asm0dey.typewriter.library.SnippetDirs
import com.github.asm0dey.typewriter.library.SnippetLibrary
import com.github.asm0dey.typewriter.library.SnippetRunner
import com.github.asm0dey.typewriter.model.Snippet
import com.github.asm0dey.typewriter.run.RunService
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.IPopupChooserBuilder
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBList
import javax.swing.JList

/**
 * Shared popup scaffolding for [TypeSnippetPickerAction] and [EditSnippetAction]: every snippet
 * from both directories, filterable by [Snippet.relativePath], rendered by that same relative
 * path rather than [Snippet]'s own `toString()` -- which would otherwise leak the raw
 * `Snippet(id=..., file=..., fileType=..., ...)` data-class dump into the popup.
 */
private fun snippetPopup(project: Project, title: String): IPopupChooserBuilder<Snippet>? {
    val snippets = SnippetDirs.all(project)
    if (snippets.isEmpty()) {
        SnippetRunner.notify(project, "no snippets found", NotificationType.WARNING)
        return null
    }
    return JBPopupFactory.getInstance()
        .createPopupChooserBuilder(snippets)
        .setTitle(title)
        .setNamerForFiltering { it.relativePath }
        .setRenderer(object : JBList.StripedListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int,
                selected: Boolean, focused: Boolean,
            ) = super.getListCellRendererComponent(
                list, (value as? Snippet)?.relativePath ?: value, index, selected, focused,
            )
        })
}

/**
 * Speed-search popup of every snippet, project and global together (spec section 9, "Type
 * Snippet…"): the statically bound entry point for the long tail that does not warrant its own
 * hotkey, and -- when the chosen snippet is one of the project's sequence steps -- the entry
 * point for **start sequence here** (spec section 8, "Sequence"), the speaker's recovery path
 * when a demo goes sideways.
 *
 * Picking a snippet always types it, exactly like invoking its own bound action would. When the
 * chosen snippet also belongs to the project sequence, the cursor is set to the position **after**
 * it, matching [RunService.cursor]'s documented invariant -- "the index of the snippet that will
 * be typed NEXT" -- and the exact pattern `SequenceRunner.playAt` already uses for `Type
 * Next`/`Type Previous`: play, then advance. Setting the cursor to the chosen snippet's own index
 * instead would make the very next `Type Next` retype what the speaker just watched play here,
 * defeating the recovery this exists for -- after jumping to step N to recover, the speaker wants
 * `Type Next` to continue at N+1, not repeat N. A snippet outside the sequence (a global utility)
 * leaves the cursor untouched.
 */
class TypeSnippetPickerAction : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        snippetPopup(project, "Type Snippet")
            ?.setItemChosenCallback { snippet -> typeAndAdvance(project, e, snippet) }
            ?.createPopup()
            ?.showCenteredInCurrentWindow(project)
    }

    private fun typeAndAdvance(project: Project, e: AnActionEvent, snippet: Snippet) {
        val sequence = SnippetLibrary.sequence(SnippetDirs.project(project))
        val position = sequence.indexOfFirst { it.relativePath == snippet.relativePath }
        SnippetRunner.run(project, e.getData(CommonDataKeys.EDITOR), snippet)
        if (position >= 0) {
            project.getService(RunService::class.java).cursor = (position + 1).coerceAtMost(sequence.size - 1)
        }
    }
}

/**
 * Entry point for [SnippetDialog]: pick a snippet, then edit it and its timing.
 *
 * [SnippetDialog] requires a live [com.intellij.openapi.editor.Document] for the chosen snippet
 * and does not itself handle the absence of one -- a binary file dropped into a snippet directory
 * has none. Guarded here the same way [SnippetRunner.run] guards its own [SnippetLibrary]
 * `textOf` lookup, so picking such a file reports a balloon instead of throwing an `NPE` out of
 * the action.
 */
class EditSnippetAction : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        snippetPopup(project, "Edit Snippet")
            ?.setItemChosenCallback { snippet -> openDialog(project, snippet) }
            ?.createPopup()
            ?.showCenteredInCurrentWindow(project)
    }

    private fun openDialog(project: Project, snippet: Snippet) {
        if (FileDocumentManager.getInstance().getDocument(snippet.file) == null) {
            SnippetRunner.notify(project, "${snippet.relativePath} has no readable text", NotificationType.ERROR)
            return
        }
        SnippetDialog(project, snippet).show()
    }
}
