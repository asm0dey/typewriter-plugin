package com.github.asm0dey.typewriterplugin.commands

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.project.Project
import java.util.concurrent.Executors

class WriteCommand(
    val project: Project,
    val e: AnActionEvent,
    val content: String,
    val delay: Long,
) : Command {

    private val scheduledExecutorService = Executors.newScheduledThreadPool(1)

    override fun run() {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val charIterator = content.lines().joinToString("\n") {
            it.replace(Regex("^\\s+"), "")
        }.trimEnd('\n').iterator()
        val document = editor.document
        val caret = editor.caretModel.primaryCaret
        fun callAction(actionName: String) = EditorActionManager.getInstance()
            .getActionHandler(actionName)
            .execute(editor, caret, e.dataContext)

        var nl = false

//        scheduledExecutorService.scheduleAtFixedRate({
        WriteCommandAction.runWriteCommandAction(project) {
            while (charIterator.hasNext()) {
                when (val next = charIterator.next()) {
                    '\n' -> {
                        callAction(IdeActions.ACTION_EDITOR_START_NEW_LINE)
                        nl = true
                    }

                    '}' -> {
                        if (!nl) {
                            document.insertString(caret.offset, next.toString())
                        } else {
                            callAction(IdeActions.ACTION_EDITOR_BACKSPACE)
                            callAction(IdeActions.ACTION_EDITOR_MOVE_CARET_DOWN)
                        }
                        nl = false
                    }

                    else -> {
                        document.insertString(caret.offset, next.toString())
                        callAction(IdeActions.ACTION_EDITOR_MOVE_CARET_RIGHT)
                        nl = false
                    }
                }
                Thread.sleep(delay)
            }
        }
    }
}
