package com.github.asm0dey.typewriterplugin.commands

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.actionSystem.EditorActionManager

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

        fun fromText(event: AnActionEvent, content: String, pauseBetweenCharacters: Int) = sequence {
            val characters = content
                .lines()
                .joinToString("\n") {
                    it.replace(Regex("^\\s+"), "")
                }
                .trimEnd('\n')
                .asSequence()

            yield(WriteCharCommand(characters.first(), pauseBetweenCharacters, event))
            characters.windowed(2).forEach { (f, s) ->
                yield(WriteCharCommand(s, pauseBetweenCharacters, event, f == '\n'))
            }
        }
    }

    override fun run() {
        if (document == null || caret == null) return
        WriteCommandAction.runWriteCommandAction(event.project) {
            when (char) {
                '\n' -> {
                    callAction(IdeActions.ACTION_EDITOR_START_NEW_LINE)
                }

                '}' -> {
                    if (!afterNewLine) {
                        document.insertString(caret.offset, char.toString())
                    } else {
                        callAction(IdeActions.ACTION_EDITOR_BACKSPACE)
                        callAction(IdeActions.ACTION_EDITOR_MOVE_CARET_DOWN)
                    }
                }

                else -> {
                    document.insertString(caret.offset, char.toString())
                    callAction(IdeActions.ACTION_EDITOR_MOVE_CARET_RIGHT)
                }
            }
        }
        Thread.sleep(pauseAfter.toLong())
    }
}


