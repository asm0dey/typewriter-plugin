package com.github.asm0dey.typewriterplugin.commands

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction

class ReformatCommand(private val event: AnActionEvent) : Command {
    private val editor = event.getData(CommonDataKeys.EDITOR)
    private val project = event.project

    override fun run() {
        if (editor == null || project == null) return

        // Execute the reformat action using ActionManager
        WriteCommandAction.runWriteCommandAction(project) {
            val actionManager = ActionManager.getInstance()
            val reformatAction = actionManager.getAction("ReformatCode")
            if (reformatAction != null) {
                actionManager.tryToExecute(reformatAction, event.inputEvent, null, null, true)
            }
        }
    }
}
