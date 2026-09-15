package com.github.asm0dey.typewriter.ui

import com.github.asm0dey.typewriter.library.SnippetRunner
import com.github.asm0dey.typewriter.model.Directives
import com.github.asm0dey.typewriter.model.Snippet
import com.github.asm0dey.typewriter.parse.CommentSyntax
import com.intellij.lang.Language
import com.intellij.lang.LanguageUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Reads and writes the directive header at the head of a snippet's text (spec section 5,
 * "Directives"; section 9, "Snippet dialog": "the timing fields read and write the file's
 * directive header"). A directive line is recognised **by name** -- its first token is one of
 * [Directives.NAMES] -- never by position, so a leading command marker (`tw: pause 500`, `tw:
 * action ...`) that merely happens to sit first in the file is left untouched by [write] rather
 * than being mistaken for -- and destroyed as -- a directive.
 *
 * [write] is a round-trip: `read(write(text, d, sentinel, syntax), sentinel) == d` for any
 * [Directives] value (see `DirectiveHeaderTest.testRoundTrip`). That property is what lets the
 * dialog trust the header it reads back after saving -- getting [write] wrong corrupts the
 * speaker's authored file, a failure a demo abort/undo cannot recover from.
 */
object DirectiveHeader {

    /**
     * Parses every leading directive line into a single [Directives]. Stops at the first line
     * that is not a directive line -- by name, not position -- so a leading command marker or the
     * snippet's actual content never contributes to the result.
     */
    fun read(text: String, sentinel: String): Directives {
        var result = Directives()
        for (line in text.lines()) {
            val body = line.trim().substringAfter(sentinel, missingDelimiterValue = "").trim()
            if (body.isEmpty() || !line.contains(sentinel)) break
            val tokens = body.split(Regex("\\s+"))
            var i = 0
            while (i < tokens.size) {
                when (tokens[i]) {
                    "raw" -> { result = result.copy(raw = true); i++ }
                    "speed" -> { result = result.copy(speedMs = tokens.getOrNull(i + 1)?.toIntOrNull()); i += 2 }
                    "jitter" -> { result = result.copy(jitterMs = tokens.getOrNull(i + 1)?.toIntOrNull()); i += 2 }
                    "newline" -> { result = result.copy(newlineMs = tokens.getOrNull(i + 1)?.toIntOrNull()); i += 2 }
                    else -> return result
                }
            }
        }
        return result
    }

    /**
     * Replaces (or removes, or adds) [text]'s leading directive header so it reflects exactly
     * [directives]. Only lines recognised as directive lines by [isDirectiveLine] -- by name, not
     * position -- are dropped from the head of the file; a leading command marker line is kept and
     * the new header (if any) is written above it, never over it.
     *
     * An "empty" [directives] (nothing set) removes the header line entirely rather than writing
     * one with an empty body, so a snippet that never had directives round-trips back to exactly
     * its original text.
     */
    fun write(text: String, directives: Directives, sentinel: String, syntax: CommentSyntax): String {
        val body = buildList {
            if (directives.raw) add("raw")
            directives.speedMs?.let { add("speed $it") }
            directives.jitterMs?.let { add("jitter $it") }
            directives.newlineMs?.let { add("newline $it") }
        }.joinToString(" ")

        val lines = text.lines().toMutableList()
        // Drop only the leading directive lines; a leading command marker stays.
        while (lines.isNotEmpty() && isDirectiveLine(lines.first(), sentinel)) lines.removeAt(0)
        if (body.isEmpty()) return lines.joinToString("\n")

        val header = comment("$sentinel $body", syntax)
        return (listOf(header) + lines).joinToString("\n")
    }

    private fun isDirectiveLine(line: String, sentinel: String): Boolean {
        if (!line.contains(sentinel)) return false
        val body = line.substringAfter(sentinel).trim()
        val head = body.split(Regex("\\s+")).firstOrNull() ?: return false
        return head in Directives.NAMES
    }

    private fun comment(body: String, syntax: CommentSyntax): String = when {
        syntax.linePrefix != null -> "${syntax.linePrefix} $body"
        syntax.blockPrefix != null && syntax.blockSuffix != null ->
            "${syntax.blockPrefix} $body ${syntax.blockSuffix}"
        else -> body
    }
}

/**
 * Edit a snippet's text and its timing directives together (spec section 9, "Snippet dialog").
 * The [EditorTextField] is built over the snippet file's **existing** [Document] -- never a copy
 * -- so the dialog, any already-open editor tab on the same file, and the player all read the one
 * buffer. Cancel therefore does not revert typed text: only OK/Cancel governs the timing fields
 * (written to the directive header only on OK), matching the spec's explicit note that the dialog
 * edits the real buffer by design.
 */
class SnippetDialog(private val project: Project, private val snippet: Snippet) : DialogWrapper(project) {

    private val document: Document = FileDocumentManager.getInstance().getDocument(snippet.file)!!
    private val editorField = EditorTextField(document, project, snippet.fileType, false, false).apply {
        preferredSize = Dimension(680, 360)
    }
    private val settings = ApplicationManager.getApplication().getService(TypeWriterSettings::class.java)
    private val current = DirectiveHeader.read(document.text, settings.state.sentinel)

    private val speed = JSpinner(SpinnerNumberModel(current.speedMs ?: settings.state.speedMs, 0, 5000, 10))
    private val jitter = JSpinner(SpinnerNumberModel(current.jitterMs ?: settings.state.jitterMs, 0, 5000, 5))
    private val newline = JSpinner(SpinnerNumberModel(current.newlineMs ?: settings.state.newlineMs, 0, 5000, 50))
    private val raw = JBCheckBox("Type as authored (raw)", current.raw)

    private var playOnClose = false

    init {
        title = "Snippet: ${snippet.relativePath}"
        init()
    }

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addComponent(editorField)
            .addLabeledComponent("Base delay (ms):", speed)
            .addLabeledComponent("Jitter (ms):", jitter)
            .addLabeledComponent("Newline pause (ms):", newline)
            .addComponent(raw)
            .addComponent(JBLabel("Hotkey: ${hotkeyText()} — change it in Settings > Keymap"))
            .panel

    private fun hotkeyText(): String {
        val shortcuts = KeymapManager.getInstance().activeKeymap.getShortcuts(snippet.id)
        return if (shortcuts.isEmpty()) "unbound" else KeymapUtil.getShortcutText(shortcuts.first())
    }

    override fun createActions() = arrayOf(
        object : AbstractAction("Open in Editor") {
            override fun actionPerformed(e: ActionEvent?) {
                FileEditorManager.getInstance(project).openFile(snippet.file, true)
                close(CANCEL_EXIT_CODE)
            }
        },
        object : AbstractAction("Play") {
            override fun actionPerformed(e: ActionEvent?) {
                playOnClose = true
                doOKAction()
            }
        },
        okAction,
        cancelAction,
    )

    override fun doOKAction() {
        saveDirectives()
        super.doOKAction()
        if (playOnClose) {
            // A tick after disposal: dialog teardown routes actions through the action system,
            // and the abort watcher cancels on any action (spec section 9, "Snippet dialog").
            ApplicationManager.getApplication().invokeLater {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                SnippetRunner.run(project, editor, snippet)
            }
        }
    }

    private fun saveDirectives() {
        val directives = Directives(
            raw = raw.isSelected,
            speedMs = speed.value as Int,
            jitterMs = jitter.value as Int,
            newlineMs = newline.value as Int,
        )
        val language: Language = LanguageUtil.getFileTypeLanguage(snippet.fileType) ?: Language.ANY
        val syntax = CommentSyntax.of(language)
        val updated = DirectiveHeader.write(document.text, directives, settings.state.sentinel, syntax)
        if (updated != document.text) {
            WriteCommandAction.runWriteCommandAction(project) { document.setText(updated) }
        }
        FileDocumentManager.getInstance().saveDocument(document)
    }
}
