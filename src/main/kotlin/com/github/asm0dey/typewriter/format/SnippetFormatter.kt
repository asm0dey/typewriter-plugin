package com.github.asm0dey.typewriter.format

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager

data class FormatResult(val text: String, val warning: String?)

object SnippetFormatter {

    fun format(project: Project, fileType: FileType, name: String, text: String): FormatResult {
        val formatted = try {
            reformat(project, fileType, name, text)
        } catch (e: Exception) {
            return FormatResult(text, "formatting failed (${e.javaClass.simpleName}); typed as authored")
        } ?: return FormatResult(text, "no formatter for ${fileType.name}; typed as authored")

        if (!sameNonWhitespace(text, formatted)) {
            return FormatResult(text, "the formatter changed more than whitespace; typed as authored")
        }
        return reconcileLines(text, formatted)
            ?.let { FormatResult(it, null) }
            ?: FormatResult(text, "the formatter changed the line structure; typed as authored")
    }

    private fun reformat(project: Project, fileType: FileType, name: String, text: String): String? {
        val psi = PsiFileFactory.getInstance(project)
            .createFileFromText(name, fileType, text, 0L, true) ?: return null
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformatText(psi, 0, psi.textLength)
        }
        return psi.text
    }

    private fun sameNonWhitespace(a: String, b: String) =
        a.filterNot { it.isWhitespace() } == b.filterNot { it.isWhitespace() }

    /**
     * Keep the original's line structure, take the formatted lines' indentation and
     * spacing. Safe because the guard already proved the non-whitespace streams match,
     * so non-blank lines correspond one to one. Null when they do not — line wrapping
     * split something.
     */
    private fun reconcileLines(original: String, formatted: String): String? {
        val formattedNonBlank = formatted.lines().filter { it.isNotBlank() }
        val originalNonBlank = original.lines().filter { it.isNotBlank() }
        if (formattedNonBlank.size != originalNonBlank.size) return null
        var i = 0
        return original.lines().joinToString("\n") { if (it.isBlank()) "" else formattedNonBlank[i++] }
    }
}
