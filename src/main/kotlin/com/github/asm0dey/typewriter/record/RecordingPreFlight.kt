package com.github.asm0dey.typewriter.record

import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.run.Check
import com.intellij.codeInsight.actions.CodeInsightAction
import com.intellij.openapi.actionSystem.ActionManager

/** Actions that need a real window (popups, lookups) may render differently offscreen. */
fun recordingWarnings(relativePath: String, steps: List<Step>): List<Check.Warning> {
    val manager = ActionManager.getInstance()
    return steps.asSequence()
        .filterIsInstance<Step.Action>()
        .map { it.actionId }
        .distinct()
        .filter {
            it == "CodeCompletion" || it == "SmartTypeCompletion" || it.startsWith("EditorChooseLookupItem") ||
                manager.getAction(it) is CodeInsightAction
        }
        .map { Check.Warning("$relativePath: the video may differ from a live run at action $it") }
        .toList()
}
