package com.github.asm0dey.typewriterplugin

import com.github.asm0dey.typewriterplugin.TypeWriterBundle.message
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent

@Suppress("UnstableApiUsage")
class TypeWriterDialog(project: Project) : DialogWrapper(project) {
    var text = ""
    var delay = TypeWriterConstants.defaultDelay
    var openingSequence = ""
    var closingSequence = ""

    init {
        title = message("dialog.title")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            textArea()
                .label(message("dialog.text"), LabelPosition.TOP)
                .focused()
                .bindText(::text)
                .let {
                    it.component.rows = 4
                    it.component.columns = 50
                }
        }
        row {
            intTextField(IntRange(1, 2000), 4)
                .label(message("dialog.delay"))
                .bindIntText(::delay)
                .gap(RightGap.SMALL)
                .let {
                    it.component.text = TypeWriterConstants.defaultDelay.toString()
                }
            @Suppress("DialogTitleCapitalization")
            label(message("dialog.ms"))
        }
        twoColumnsRow({
            textField()
                .label("Template opens", LabelPosition.LEFT)
                .bindText(::openingSequence)
        }, {
            textField()
                .label("Template closes", LabelPosition.LEFT)
                .bindText(::closingSequence)
        })
    }
}
