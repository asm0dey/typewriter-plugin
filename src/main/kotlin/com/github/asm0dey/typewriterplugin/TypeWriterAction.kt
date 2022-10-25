package com.github.asm0dey.typewriterplugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.IdeActions.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TypeWriterAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val document = editor.document
        val caret = editor.caretModel.primaryCaret
        val scheduledExecutorService = Executors.newScheduledThreadPool(1)

        val dialog = TypeWriterDialog(project)
        fun callAction(actionName: String) = EditorActionManager.getInstance()
            .getActionHandler(actionName)
            .execute(editor, caret, e.dataContext)

        if (dialog.showAndGet()) {
            val charIterator = dialog.text.lines().joinToString("\n") {
                it.replace(Regex("^\\s+"), "")
            }.trimEnd('\n').iterator()

            var nl = false
            scheduledExecutorService.scheduleAtFixedRate({
                WriteCommandAction.runWriteCommandAction(project) {
                    when (val next = charIterator.next()) {
                        '\n' -> {
                            callAction(ACTION_EDITOR_START_NEW_LINE)
                            nl = true
                        }

                        '}' -> {
                            if (!nl) {
                                document.insertString(caret.offset, next.toString())
                            } else {
                                callAction(ACTION_EDITOR_BACKSPACE)
                                callAction(ACTION_EDITOR_MOVE_CARET_DOWN)
                            }
                            nl = false
                        }

                        else -> {
                            document.insertString(caret.offset, next.toString())
                            callAction(ACTION_EDITOR_MOVE_CARET_RIGHT)
                            nl = false
                        }
                    }
                }

            }, 0L, dialog.delay.toLong(), TimeUnit.MILLISECONDS)
        }
    }

}
