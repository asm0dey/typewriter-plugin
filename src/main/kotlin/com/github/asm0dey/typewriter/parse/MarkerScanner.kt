package com.github.asm0dey.typewriter.parse

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

enum class MarkerKind { WHOLE_LINE, TRAILING, MID_LINE }

data class RawMarker(
    val startOffset: Int,
    val endOffset: Int,
    val body: String,
    val kind: MarkerKind,
    val line: Int,
)

object MarkerScanner {

    fun scan(file: PsiFile, sentinel: String): List<RawMarker> {
        val syntax = CommentSyntax.of(file.language)
        if (!syntax.hasAny) return emptyList()
        val text = file.text
        return PsiTreeUtil.findChildrenOfType(file, PsiComment::class.java)
            .mapNotNull { comment ->
                val trimmed = syntax.bodyOf(comment.text).trimStart()
                // "tw::" is the escape: it is content, not a marker.
                if (!trimmed.startsWith(sentinel) || trimmed.startsWith("$sentinel:")) return@mapNotNull null
                val start = comment.textRange.startOffset
                val end = comment.textRange.endOffset
                RawMarker(
                    startOffset = start,
                    endOffset = end,
                    body = trimmed.removePrefix(sentinel),
                    kind = classify(text, start, end),
                    line = text.take(start).count { it == '\n' } + 1,
                )
            }
    }

    /**
     * WHOLE_LINE when nothing but whitespace precedes the marker on its first line
     * and nothing but whitespace follows it on its last line. TRAILING when only the
     * tail is clear. Anything else is MID_LINE.
     */
    private fun classify(text: String, start: Int, end: Int): MarkerKind {
        val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        val headClear = text.substring(lineStart, start).isBlank()
        val tailClear = text.substring(end, lineEnd).isBlank()
        return when {
            headClear && tailClear -> MarkerKind.WHOLE_LINE
            tailClear -> MarkerKind.TRAILING
            else -> MarkerKind.MID_LINE
        }
    }
}
