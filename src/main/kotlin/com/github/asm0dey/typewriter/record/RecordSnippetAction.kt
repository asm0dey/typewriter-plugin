package com.github.asm0dey.typewriter.record

import com.github.asm0dey.typewriter.ui.snippetPopup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware

/** Pick a snippet and record it, into the focused editor's context, to a video file. */
class RecordSnippetAction : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        snippetPopup(project, "Record Snippet to Video")
            ?.setItemChosenCallback { project.getService(RecordingService::class.java).start(editor, it) }
            ?.createPopup()
            ?.showCenteredInCurrentWindow(project)
    }
}
