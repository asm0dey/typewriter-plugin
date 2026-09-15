package com.github.asm0dey.typewriter.ide

import com.github.asm0dey.typewriter.model.Directives
import com.github.asm0dey.typewriter.parse.CommentSyntax
import com.github.asm0dey.typewriter.ui.TypeWriterSettings
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiComment
import com.intellij.psi.util.PsiTreeUtil

/**
 * Spec section 9, "Command completion": offers the six command/directive names after the
 * sentinel, and action ids after `action `, but only inside a marker (a [PsiComment] whose body
 * starts with the configured sentinel) in a file under a configured snippet directory --
 * [SnippetFiles.isSnippet] is the same predicate [SnippetHighlightingSettingProvider] uses.
 *
 * Registered `language="any"` in plugin.xml rather than per-language: a snippet can be any file
 * type the IDE supports (spec section 8), so registering for a specific language would silently
 * miss exactly the long tail this rewrite exists to serve.
 *
 * Completion is served by `CompletionService`, not the daemon, so Task 14's `SKIP_HIGHLIGHTING`
 * (which only suppresses the highlighting pass) does not suppress this.
 */
class MarkerCompletionContributor : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile
        val virtualFile = file.virtualFile ?: return
        val project = file.project
        if (!SnippetFiles.isSnippet(project, virtualFile)) return

        val comment = PsiTreeUtil.getParentOfType(parameters.position, PsiComment::class.java, false) ?: return
        val sentinel = ApplicationManager.getApplication()
            .getService(TypeWriterSettings::class.java).state.sentinel

        // Everything the user has typed in this comment up to the caret, minus the opening
        // delimiter. The closing delimiter is irrelevant: it is never before the caret in a
        // comment being typed.
        val caretInComment = (parameters.offset - comment.textRange.startOffset)
            .coerceIn(0, comment.text.length)
        val syntax = CommentSyntax.of(file.language)
        val opening = listOfNotNull(syntax.blockPrefix, syntax.linePrefix)
            .firstOrNull { comment.text.startsWith(it) }
            ?: return
        val beforeCaret = comment.text.take(caretInComment).removePrefix(opening)

        for (name in completionsFor(beforeCaret.trimStart(), sentinel)) {
            result.addElement(LookupElementBuilder.create(name))
        }
    }

    companion object {
        /**
         * [body] is the comment's body up to the caret, already stripped of delimiters. Returns
         * the names to offer, or empty when the position takes a number or is not a marker at
         * all.
         */
        fun completionsFor(body: String, sentinel: String): List<String> {
            val trimmed = body.trimStart()
            if (!trimmed.startsWith(sentinel)) return emptyList()
            val rest = trimmed.removePrefix(sentinel).trimStart()
            val words = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val head = words.firstOrNull()

            return when {
                rest.isEmpty() || words.size == 1 && !rest.endsWith(" ") ->
                    listOf("pause", "action") + Directives.NAMES
                head == "action" -> ActionManager.getInstance().getActionIdList("")
                head in setOf("pause", "speed", "jitter", "newline") -> emptyList()
                else -> emptyList()
            }
        }
    }
}
