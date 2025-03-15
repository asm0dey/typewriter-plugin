package com.github.asm0dey.typewriterplugin.commands

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.IdeActions.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import kotlin.random.Random

class WriteCharCommand(
    private val char: Char,
    private val pauseAfter: Int,
    private val event: AnActionEvent,
    private val afterNewLine: Boolean = false
) : Command {
    private val editor = event.getData(CommonDataKeys.EDITOR)
    private val document = editor?.document
    private val caret = editor?.caretModel?.primaryCaret
    private fun callAction(actionName: String) {
        if (editor == null) return
        editorActionManager.getActionHandler(actionName).execute(editor, caret, event.dataContext)
    }

    companion object {
        private val editorActionManager = EditorActionManager.getInstance()

        fun fromText(event: AnActionEvent, content: String, pauseBetweenCharacters: Int, jitter: Int) = sequence {
            val characters = content
                .lines()
                .joinToString("\n") {
                    it.replace(Regex("^\\s+"), "")
                }
                .toList()

            for ((index, value) in characters.withIndex()) {
                yield(
                    WriteCharCommand(
                        value,
                        pauseBetweenCharacters + Random.nextInt(-jitter, jitter + 1),
                        event,
                        index - 1 in characters.indices && characters[index - 1] == '\n'
                    )
                )
            }
        }
    }

    override fun run() {
        if (document == null || caret == null) return
        val project = event.project
        WriteCommandAction.runWriteCommandAction(project) {
            val offset = caret.offset
            val str = char.toString()
            when (char) {
                '\n' -> {
                    callAction(ACTION_EDITOR_START_NEW_LINE)
                }

                '}' -> {
                    if (!afterNewLine) {
                        document.insertString(offset, str)
                        callAction(ACTION_EDITOR_MOVE_CARET_RIGHT)
                    } else {
                        callAction(ACTION_EDITOR_BACKSPACE)
                        callAction(ACTION_EDITOR_MOVE_CARET_DOWN)
                    }
                }

                else -> {
                    document.insertString(offset, str)
                    callAction(ACTION_EDITOR_MOVE_CARET_RIGHT)
                }
            }
        }
        Thread.sleep(pauseAfter.toLong())
    }
}


