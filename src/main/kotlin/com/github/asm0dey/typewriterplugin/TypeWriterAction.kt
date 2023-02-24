package com.github.asm0dey.typewriterplugin

import com.github.asm0dey.typewriterplugin.commands.PauseCommand
import com.github.asm0dey.typewriterplugin.commands.WriteCommand
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import org.apache.commons.lang3.math.NumberUtils
import kotlin.math.min

class TypeWriterAction : DumbAwareAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val dialog = TypeWriterDialog(project)

        if (dialog.showAndGet()) {
            val text = dialog.text
            val delay = dialog.delay.toLong()
            val openingSequence = Regex.escapeReplacement(dialog.openingSequence)
            val closingSequence = Regex.escapeReplacement(dialog.closingSequence)
            val matches = """$openingSequence(.*?)$closingSequence""".toRegex().findAll(text)

            sequence {
                if (matches.none()) {
                    yield(WriteCommand(project, e, text, delay))
                }

                val iterator = matches.iterator()
                while (iterator.hasNext()) {
                    val item = iterator.next()

                    if (item.range.first > 0) {
                        val content = text.substring(0, item.range.first)
                        yield(WriteCommand(project, e, content, delay))
                    }
                    yield(PauseCommand(2000))

                    if (!iterator.hasNext()) {
                        val content = text.substring(item.range.last + 1, text.length)
                        if (content.isNotEmpty()) {
                            yield(WriteCommand(project, e, content, delay))
                        }
                    }
                }
            }.forEach {
                it.run()
            }
        }
    }
}
