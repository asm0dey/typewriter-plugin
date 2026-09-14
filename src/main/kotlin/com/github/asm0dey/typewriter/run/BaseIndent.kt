package com.github.asm0dey.typewriter.run

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager

/**
 * The indentation the target context calls for at the point a run begins, contributed by
 * the document being typed into rather than by the snippet. See design spec section 7,
 * "Base indent".
 */
object BaseIndent {

    /** The caret's column on its own line, as a zero-based character offset from line start. */
    fun caretColumn(document: Document, caretOffset: Int): Int =
        caretOffset - document.getLineStartOffset(document.getLineNumber(caretOffset))

    /**
     * Computed once at pre-flight. Mid-line, the caret's own column is the indent (continuation
     * lines should align to where typing starts). On a blank line, the IDE is asked what
     * indentation the target context calls for, falling back to the caret column when
     * [CodeStyleManager.getLineIndent] returns null (plain text, unknown file types, and any
     * language with no formatter).
     */
    fun compute(project: Project, file: PsiFile, document: Document, caretOffset: Int): String {
        val lineStart = document.getLineStartOffset(document.getLineNumber(caretOffset))
        val before = document.getText(TextRange(lineStart, caretOffset))
        val column = caretOffset - lineStart
        if (before.isNotBlank()) return " ".repeat(column)
        return CodeStyleManager.getInstance(project).getLineIndent(file, caretOffset)
            ?: " ".repeat(column)
    }

    /**
     * Prepends [indent] to every line after the first. The first line is padded by the
     * shortfall between [indent] and [caretColumn] — the caret is already at [caretColumn], so
     * only the difference is needed to reach [indent]. Empty lines are left empty.
     */
    fun apply(payload: String, indent: String, caretColumn: Int): String {
        val firstLinePad = " ".repeat((indent.length - caretColumn).coerceAtLeast(0))
        return payload.lines().mapIndexed { index, line ->
            when {
                line.isEmpty() -> line
                index == 0 -> firstLinePad + line
                else -> indent + line
            }
        }.joinToString("\n")
    }
}
