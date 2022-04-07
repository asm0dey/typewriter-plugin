package com.github.asm0dey.typewriterplugin

import com.intellij.codeInsight.hints.settings.language.createEditor
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.layout.panel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import com.intellij.lang.Language as IDELang


class TypeWriterAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val doc = editor.document
        val caret = editor.caretModel.primaryCaret
        val s = Executors.newScheduledThreadPool(1)

        class TypeWriterDialog : DialogWrapper(project) {
            init {
                title = "Typewriter Text"
                init()
                delay = 100
            }

            var text = ""
            var delay = 100
//            var lang = Language.JAVA
            override fun createCenterPanel(): JComponent? {
                return panel {
/*
                    row {
                        label("Language:")
                        comboBox(CollectionComboBoxModel(Language.values().toList(), Language.PLAIN), ::lang)
                    }
*/
                    row {
                        label("Text to type")
                        textArea(::text, columns = 200, rows = 3)
                    }
                    row {
                        label("Delay between characters")
                        intTextField(::delay, 4, 1..2000).let {
                            it.component.text = "200"
                        }
                        label("ms")
                    }
                }
            }
        }

        val dialog = TypeWriterDialog()
        if (dialog.showAndGet()) {
            val iter = dialog.text.iterator()
            s.scheduleAtFixedRate({
                WriteCommandAction.runWriteCommandAction(project) {
                    val next = iter.next()
                    if (next == '\n')
                        try {
                            EditorActionManager.getInstance().getActionHandler("EditorStartNewLine")
                                .execute(editor, caret, e.dataContext)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    else {
                        doc.insertString(caret.offset, next.toString())
                        caret.moveToOffset(caret.offset + 1)
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
