package com.github.asm0dey.typewriter.run

import com.github.asm0dey.typewriter.model.Program
import com.github.asm0dey.typewriter.model.Snippet
import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.parse.CommentSyntax
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.LanguageFileType

/**
 * The outcome of one pre-flight check (spec section 11, "Pre-flight"). An [Error] blocks the
 * run: nothing is typed. A [Warning] is surfaced but the run proceeds.
 */
sealed interface Check {
    data class Error(val message: String) : Check
    data class Warning(val message: String) : Check
}

/** True when any [Check.Error] is present -- the run must not start. */
fun List<Check>.blocked(): Boolean = any { it is Check.Error }

/**
 * Runs every check in spec section 11's "Pre-flight" table before the first character of
 * [snippet] would be typed into [editor]. This is the real gate: [Player.runAction]'s silent
 * skip of an unknown action id is defence-in-depth for the case pre-flight was bypassed, not a
 * substitute for the check here.
 */
object PreFlight {

    fun check(
        editor: Editor?,
        snippet: Snippet,
        program: Program,
        formatWarning: String?,
    ): List<Check> {
        if (editor == null) {
            return listOf(Check.Error("no editor is focused"))
        }

        val checks = mutableListOf<Check>()

        if (!editor.document.isWritable) {
            checks += Check.Error("the target file is read-only")
        }
        if (editor.document.getOffsetGuard(editor.caretModel.offset) != null) {
            checks += Check.Error("the caret is inside a guarded region")
        }

        val targetFile = FileDocumentManager.getInstance().getFile(editor.document)
        if (targetFile != null && targetFile == snippet.file) {
            checks += Check.Error("the target editor is the snippet's own file")
        }
        if (FileDocumentManager.getInstance().getDocument(snippet.file) == null) {
            checks += Check.Error("${snippet.relativePath} has no readable text")
        }

        for ((line, message) in program.errors) {
            checks += Check.Error("${snippet.relativePath} line $line: $message")
        }

        val actionManager = ActionManager.getInstance()
        program.steps.filterIsInstance<Step.Action>()
            .map { it.actionId }
            .distinct()
            .filter { actionManager.getAction(it) == null }
            .forEach { checks += Check.Error("${snippet.relativePath}: unknown action id \"$it\"") }

        if (targetFile != null && targetFile.fileType != snippet.fileType) {
            checks += Check.Warning(
                "snippet is ${snippet.fileType.name} but the target file is ${targetFile.fileType.name}"
            )
        }
        val language = (snippet.fileType as? LanguageFileType)?.language
        if (language == null || !CommentSyntax.of(language).hasAny) {
            checks += Check.Warning(
                "${snippet.fileType.name} has no registered comment syntax; commands and formatting are unavailable"
            )
        }
        if (program.steps.isEmpty()) {
            checks += Check.Warning("${snippet.relativePath} is empty")
        }
        formatWarning?.takeUnless { it.isBlank() }?.let { checks += Check.Warning(it) }

        if (editor.caretModel.caretCount > 1) {
            checks += Check.Warning("multiple carets; only the primary caret is used")
        }

        return checks
    }
}
