package com.github.asm0dey.typewriterplugin

import com.github.asm0dey.typewriterplugin.commands.PauseCommand
import com.github.asm0dey.typewriterplugin.commands.WriteCharCommand
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction

class TypeWriterAction : DumbAwareAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val dialog = TypeWriterDialog(project)

        if (!dialog.showAndGet()) return
        val text = dialog.text
        val openingSequence = Regex.escapeReplacement(dialog.openingSequence)
        val closingSequence = Regex.escapeReplacement(dialog.closingSequence)
        val matches = """$openingSequence(.*?)$closingSequence""".toRegex().findAll(text)
        val scheduler = service<TypewriterExecutorService>()

        val delay = dialog.delay.toLong()
        val iterator = matches.iterator()
        var cur: MatchResult? = null
        while (true) {
            val next = if (iterator.hasNext()) iterator.next() else null
            val content = text.substring(cur?.range?.last?.plus(1) ?: 0, next?.range?.first ?: text.length)
            val commands = WriteCharCommand.fromText(e, content, delay.toInt())
            for (command in commands) scheduler.enqueue(command)
            if (next != null)
                scheduler.enqueue(PauseCommand(2000))
            cur = next
            if (cur == null) break
        }
    }
}
