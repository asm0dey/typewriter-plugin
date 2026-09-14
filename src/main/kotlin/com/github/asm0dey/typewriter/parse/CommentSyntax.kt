package com.github.asm0dey.typewriter.parse

import com.intellij.lang.Language
import com.intellij.lang.LanguageCommenters

data class CommentSyntax(
    val linePrefix: String?,
    val blockPrefix: String?,
    val blockSuffix: String?,
) {
    val hasAny: Boolean get() = linePrefix != null || (blockPrefix != null && blockSuffix != null)

    /** Strips whichever delimiters [commentText] actually carries. */
    fun bodyOf(commentText: String): String {
        if (blockPrefix != null && blockSuffix != null &&
            commentText.startsWith(blockPrefix) && commentText.endsWith(blockSuffix)
        ) {
            return commentText.substring(blockPrefix.length, commentText.length - blockSuffix.length)
        }
        if (linePrefix != null && commentText.startsWith(linePrefix)) {
            return commentText.substring(linePrefix.length)
        }
        return commentText
    }

    companion object {
        fun of(language: Language): CommentSyntax {
            val commenter = LanguageCommenters.INSTANCE.forLanguage(language)
                ?: return CommentSyntax(null, null, null)
            return CommentSyntax(
                linePrefix = commenter.lineCommentPrefix?.takeIf { it.isNotEmpty() },
                blockPrefix = commenter.blockCommentPrefix?.takeIf { it.isNotEmpty() },
                blockSuffix = commenter.blockCommentSuffix?.takeIf { it.isNotEmpty() },
            )
        }
    }
}
