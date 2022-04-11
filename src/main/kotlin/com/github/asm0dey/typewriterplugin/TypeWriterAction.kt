package com.github.asm0dey.typewriterplugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.IdeActions.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.RightGap.SMALL
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent


class TypeWriterAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val doc = editor.document
        val caret = editor.caretModel.primaryCaret
        val s = Executors.newScheduledThreadPool(1)

        class TypeWriterDialog() : DialogWrapper(project) {
            var text = ""
            var delay = 100

            init {
                title = "Typewriter Text"
                init()
            }

            override fun createCenterPanel(): JComponent? {
                return panel {
                    row {
                        label("Text to type")
                            .gap(SMALL)
                        textArea()
                            .focused()
                            .gap(SMALL)
                            .bindText(::text)
                            .let {
                                it.component.rows = 4
                                it.component.columns = 50

                            }
                    }
                    row {
                        label("Delay between characters")
                            .gap(SMALL)
                        intTextField(IntRange(1, 2000), 4)
                            .bindIntText(::delay)
                            .gap(SMALL)
                            .let {
                                it.component.text = "100"
                            }
                        label("ms")
                    }
                }
            }
        }

        val dialog = TypeWriterDialog()
        fun callAction(actionName: String) =
            EditorActionManager.getInstance().getActionHandler(actionName).execute(editor, caret, e.dataContext)

        if (dialog.showAndGet()) {

            val charIterator = dialog.text
                .lines()
                .joinToString("\n") {
                    it
                        .replace(Regex("^\\s+"), "")

                }
                .trimEnd('\n')
                .iterator()
            var nl = false
            s.scheduleAtFixedRate({
                WriteCommandAction.runWriteCommandAction(project) {
                    when (val next = charIterator.next()) {
                        '\n' -> {
//                            if (!closedBrace)
                                callAction(ACTION_EDITOR_START_NEW_LINE)
                            nl = true
                        }

                        '}' -> {
                            if (!nl)
                                doc.insertString(caret.offset, next.toString())
                            else {
                                callAction(ACTION_EDITOR_BACKSPACE)
                                callAction(ACTION_EDITOR_MOVE_CARET_DOWN)

                            }
                            nl = false
                        }

                        else -> {
                            doc.insertString(caret.offset, next.toString())
                            callAction(ACTION_EDITOR_MOVE_CARET_RIGHT)
                            nl = false
                        }
                    }
                }

            }, 0L, dialog.delay.toLong(), TimeUnit.MILLISECONDS)
        }
    }

}

/*
enum class Language {
    JAVA {
        override val langName: String = "Java"
        override val lang = IDELang.findLanguageByID("JAVA")!!
    },
    KOTLIN {
        override val langName: String = "Kotlin"
        override val lang = IDELang.findLanguageByID("kotlin")!!
    },
    PLAIN {
        override val langName: String = "Plaintext"
        override val lang = IDELang.ANY
    };

    abstract val langName: String
    abstract val lang: IDELang
}
*/
