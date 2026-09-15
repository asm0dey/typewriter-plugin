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
 *
 * The platform instantiates a `CompletionContributor` on its own schedule, so this class carries
 * no companion object: a platform inspection flags logic or state in an IDE extension's companion
 * (only a logger and constants are allowed there), since it would mean class-initialization work
 * and object retention at a moment the plugin does not control. [completionsFor] (the tested pure
 * entry point) and its private helpers therefore live at file scope below instead -- don't move
 * them back into a companion object.
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

        val classified = classify(beforeCaret.trimStart(), sentinel)
        for (name in classified.names) {
            val element = if (classified.isActionIds) actionLookupElement(name) else LookupElementBuilder.create(name)
            result.addElement(element)
        }
    }
}

/**
 * [body] is the comment's body up to the caret, already stripped of delimiters. Returns
 * the names to offer, or empty when the position takes a number or is not a marker at
 * all.
 */
fun completionsFor(body: String, sentinel: String): List<String> = classify(body, sentinel).names

/** [names] plus whether they are action ids (spec section 9: presented with the action's own text and icon). */
private data class Classified(val names: List<String>, val isActionIds: Boolean)

private fun classify(body: String, sentinel: String): Classified {
    val trimmed = body.trimStart()
    if (!trimmed.startsWith(sentinel)) return Classified(emptyList(), isActionIds = false)
    val rest = trimmed.removePrefix(sentinel).trimStart()
    val words = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val head = words.firstOrNull()

    return when {
        rest.isEmpty() || words.size == 1 && !rest.endsWith(" ") ->
            Classified(listOf("pause", "action") + Directives.NAMES, isActionIds = false)
        head == "action" -> Classified(ActionManager.getInstance().getActionIdList(""), isActionIds = true)
        head in setOf("pause", "speed", "jitter", "newline") -> Classified(emptyList(), isActionIds = false)
        else -> Classified(emptyList(), isActionIds = false)
    }
}

/**
 * Spec section 9: action ids are "presented with the action's own text and icon", so a
 * speaker can tell `ReformatCode` from `ReformatFile` without knowing the raw id. Falls
 * back to a bare lookup element when the id no longer resolves to a registered action, or
 * when that action has no icon.
 */
private fun actionLookupElement(actionId: String): LookupElementBuilder {
    var builder = LookupElementBuilder.create(actionId)
    val action = ActionManager.getInstance().getAction(actionId) ?: return builder
    val presentation = action.templatePresentation
    presentation.icon?.let { builder = builder.withIcon(it) }
    presentation.text?.let { builder = builder.withTypeText(it) }
    return builder
}
