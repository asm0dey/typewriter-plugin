package com.github.asm0dey.typewriter.format

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager

data class FormatResult(val text: String, val warning: String?)

object SnippetFormatter {

    fun format(project: Project, fileType: FileType, name: String, text: String): FormatResult {
        val formatted = try {
            reformat(project, fileType, name, text)
        } catch (e: Exception) {
            return FormatResult(text, "formatting failed (${e.javaClass.simpleName}); typed as authored")
        }

        if (!sameNonWhitespace(text, formatted)) {
            return FormatResult(text, "the formatter changed more than whitespace; typed as authored")
        }
        reconcileLines(text, formatted)?.let { return FormatResult(it, null) }

        // The full reformat wanted to move content ACROSS lines (`</b><c>` split onto two, say),
        // which spec section 6's net rule forbids. Falling straight back to verbatim here threw
        // away every indentation fix in the snippet along with the one offending split -- the
        // reported case was an XML file whose root element sat under 28 spaces and stayed there,
        // because one unrelated line elsewhere could not be reconciled.
        //
        // adjustLineIndent fixes each line's indent where it stands and cannot reflow, so it is
        // structurally incapable of the split that got the first attempt rejected. The guards below
        // are re-run rather than assumed: it is still the platform's formatter deciding what an
        // indent should be.
        val indented = try {
            indentOnly(project, fileType, name, text)
        } catch (e: Exception) {
            return FormatResult(text, "formatting failed (${e.javaClass.simpleName}); typed as authored")
        }
        if (!sameNonWhitespace(text, indented)) {
            return FormatResult(text, "the formatter changed more than whitespace; typed as authored")
        }
        return reconcileLines(text, indented)
            ?.let { FormatResult(it, null) }
            ?: FormatResult(text, "the formatter changed the line structure; typed as authored")
    }

    /**
     * Re-indents each line in place, without reflowing anything.
     *
     * Bottom-up, deliberately: adjusting one line's indent shifts every offset after it, so walking
     * downwards would feed stale offsets into later calls.
     */
    private fun indentOnly(project: Project, fileType: FileType, name: String, text: String): String {
        val psi = PsiFileFactory.getInstance(project)
            .createFileFromText(name, fileType, text, 0L, true)
        val document = PsiDocumentManager.getInstance(project).getDocument(psi) ?: return text
        val manager = CodeStyleManager.getInstance(project)
        WriteCommandAction.runWriteCommandAction(project) {
            for (line in document.lineCount - 1 downTo 0) {
                manager.adjustLineIndent(psi, document.getLineStartOffset(line))
            }
        }
        return psi.text
    }

    /**
     * `createFileFromText` is declared `@NotNull` and falls back to a plain-text PSI file when no
     * ParserDefinition matches, so there is no "this file type has no formatter" case to handle here.
     * Were a future platform version to return null anyway, the resulting NPE is an Exception and
     * [format]'s catch already degrades to typing the snippet as authored.
     */
    private fun reformat(project: Project, fileType: FileType, name: String, text: String): String {
        val psi = PsiFileFactory.getInstance(project)
            .createFileFromText(name, fileType, text, 0L, true)
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformatText(psi, 0, psi.textLength)
        }
        return psi.text
    }

    internal fun sameNonWhitespace(a: String, b: String) =
        a.filterNot { it.isWhitespace() } == b.filterNot { it.isWhitespace() }

    /**
     * Keep the original's line structure, take the formatted lines' indentation and
     * spacing. Safe because the guard already proved the non-whitespace streams match,
     * so non-blank lines correspond one to one. Null when they do not — line wrapping
     * split something.
     */
    internal fun reconcileLines(original: String, formatted: String): String? {
        val formattedNonBlank = formatted.lines().filter { it.isNotBlank() }
        val originalNonBlank = original.lines().filter { it.isNotBlank() }
        if (formattedNonBlank.size != originalNonBlank.size) return null
        var i = 0
        return original.lines().joinToString("\n") { if (it.isBlank()) "" else formattedNonBlank[i++] }
    }
}
