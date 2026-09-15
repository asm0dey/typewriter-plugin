package com.github.asm0dey.typewriter.ui

import com.github.asm0dey.typewriter.library.SnippetRunner
import com.github.asm0dey.typewriter.model.Directives
import com.github.asm0dey.typewriter.model.Snippet
import com.github.asm0dey.typewriter.parse.CommentSyntax
import com.intellij.lang.Language
import com.intellij.lang.LanguageUtil
import com.intellij.notification.NotificationType
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
     *
     * When [syntax] has no comment delimiters at all ([CommentSyntax.hasAny] is false -- a plain
     * text file, or any file type whose language registers no [com.intellij.lang.Commenter]),
     * [text] is returned completely unmodified rather than prepending a bare, uncommented
     * `"$sentinel ..."` line. Such a line would never be recognised as a marker
     * ([com.github.asm0dey.typewriter.parse.MarkerScanner] short-circuits to no markers for
     * exactly this case) and would instead be typed verbatim into the target editor during a demo
     * -- corruption arriving as added garbage rather than lost text. Spec section 11's policy for
     * an unknown/uncommentable file type is "warn, proceed without commands"; writing a header
     * that can only ever become stray body content contradicts that.
     */
    fun write(text: String, directives: Directives, sentinel: String, syntax: CommentSyntax): String {
        if (!syntax.hasAny) return text

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

    /** One directive field's resolved value on OK, plus whether resolving it required silently
     * picking a side between two edits that disagreed. See [resolveField]. */
    data class FieldOutcome<T>(val value: T, val conflicted: Boolean)

    /**
     * `SnippetDialog` shows the same directives in two editable places at once: the header text
     * inside the `EditorTextField`, live-bound to the snippet's own [com.intellij.openapi.editor.Document],
     * and the dialog's own spinner/checkbox controls, which are only ever read to *seed* their
     * initial values when the dialog opens. Two failure modes fall out of that if OK writes the
     * controls' values unconditionally:
     *
     * 1. A speaker who edits the header directly in the editor (`// tw: speed 80` -> `90`),
     *    without touching the spinner, has that edit silently reverted to the spinner's stale `80`
     *    on OK -- the closest thing to data loss in this project, since unlike a bad run there is
     *    no abort/undo for it.
     * 2. A spinner cannot represent "this field is absent from the header" -- it always shows
     *    *some* number, falling back to the app-wide default when the header has none. Opening a
     *    snippet with no header at all and simply clicking OK would therefore write a full header
     *    that pins today's global defaults into that one file forever, invisibly, even though the
     *    speaker changed nothing.
     *
     * [resolveField] is a pure decision that fixes both, **per field** (a speaker who nudges only
     * the jitter spinner must not thereby also pin speed and newline): given
     * - [opened]: this field's value as the header had it when the dialog opened (`null` for a
     *   nullable field means the header had no such directive),
     * - [current]: this field's value as the header has it *right now*, re-read from the document
     *   at OK time -- differs from [opened] exactly when the speaker edited the header directly,
     * - [controlChanged]: whether the speaker moved this field's own control (spinner or
     *   checkbox) away from the value it was initialised to when the dialog opened,
     * - [controlValue]: that control's current value,
     *
     * it returns the value to actually write for this field, plus whether doing so required
     * silently picking a side:
     * - header unchanged, control unchanged -> keep [current] (== [opened]; for an absent field
     *   this keeps it `null` -- the fix for failure mode 2 above).
     * - header unchanged, control changed -> write [controlValue] -- the control is still the
     *   speaker's own tool for this field.
     * - header changed, control unchanged -> keep [current] -- the speaker edited the header
     *   directly and meant it; this is the fix for failure mode 1 above.
     * - both changed -> keep [current] (the `Document` is the snippet's one authoritative text,
     *   spec section 5) but report `conflicted = true`, so the caller can tell the speaker their
     *   control change was not applied rather than silently discard either edit.
     */
    fun <T> resolveField(opened: T, current: T, controlChanged: Boolean, controlValue: T): FieldOutcome<T> {
        val headerChangedInEditor = current != opened
        return when {
            !headerChangedInEditor && controlChanged -> FieldOutcome(controlValue, conflicted = false)
            !headerChangedInEditor -> FieldOutcome(current, conflicted = false)
            !controlChanged -> FieldOutcome(current, conflicted = false)
            else -> FieldOutcome(current, conflicted = true)
        }
    }
}

/**
 * Edit a snippet's text and its timing directives together (spec section 9, "Snippet dialog").
 * The [EditorTextField] is built over the snippet file's **existing** [Document] -- never a copy
 * -- so the dialog, any already-open editor tab on the same file, and the player all read the one
 * buffer. Cancel therefore does not revert typed text: only OK/Cancel governs the timing fields
 * (written to the directive header only on OK, and only per-field where the speaker's own edit
 * doesn't already win -- see [DirectiveHeader.resolveField]), matching the spec's explicit note
 * that the dialog edits the real buffer by design.
 *
 * Precondition: the caller ([EditSnippetAction]) has already verified [snippet.file][Snippet.file]
 * has a live [Document] before constructing this dialog. A binary file has none; this class does
 * not itself handle that case.
 */
class SnippetDialog(private val project: Project, private val snippet: Snippet) : DialogWrapper(project) {

    private val document: Document = FileDocumentManager.getInstance().getDocument(snippet.file)!!
    private val editorField = EditorTextField(document, project, snippet.fileType, false, false).apply {
        preferredSize = Dimension(680, 360)
        // EditorTextField defaults to the Swing/LAF font (a proportional UI font, e.g. "Inter"),
        // overriding the editor colour scheme's own monospace font -- see setupEditorFont's
        // myInheritSwingFont branch. Snippets are whitespace-sensitive (base indent, the
        // whitespace-equivalence guard, column positions); the one thing a speaker is checking
        // when authoring one is unreadable in a proportional font. Opting out keeps the field on
        // the editor scheme's own font (spec section 9: "a real editor -- monospace, highlighting,
        // completion").
        setFontInheritedFromLAF(false)
    }
    private val settings = ApplicationManager.getApplication().getService(TypeWriterSettings::class.java)

    /** The directives as the dialog read them when it opened -- see [DirectiveHeader.resolveField]. */
    private val openedDirectives = DirectiveHeader.read(document.text, settings.state.sentinel)

    /** Comment syntax for the snippet's own language. See [createCenterPanel] and [saveDirectives]
     * for what happens when it has none. */
    private val syntax = CommentSyntax.of(LanguageUtil.getFileTypeLanguage(snippet.fileType) ?: Language.ANY)

    // The value each spinner is initialised to: the header's own value, or the app default when
    // the header has none. Doubles as the baseline saveDirectives compares the live spinner
    // against to tell whether the speaker actually touched it, since a JSpinner (unlike the
    // header) cannot itself represent "unset".
    private val speedBaseline = openedDirectives.speedMs ?: settings.state.speedMs
    private val jitterBaseline = openedDirectives.jitterMs ?: settings.state.jitterMs
    private val newlineBaseline = openedDirectives.newlineMs ?: settings.state.newlineMs

    private val speed = JSpinner(SpinnerNumberModel(speedBaseline, 0, 5000, 10)).apply { isEnabled = syntax.hasAny }
    private val jitter = JSpinner(SpinnerNumberModel(jitterBaseline, 0, 5000, 5)).apply { isEnabled = syntax.hasAny }
    private val newline = JSpinner(SpinnerNumberModel(newlineBaseline, 0, 5000, 50)).apply {
        isEnabled = syntax.hasAny
    }
    private val raw = JBCheckBox("Type as authored (raw)", openedDirectives.raw).apply { isEnabled = syntax.hasAny }

    private var playOnClose = false

    init {
        title = "Snippet: ${snippet.relativePath}"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val builder = FormBuilder.createFormBuilder()
            .addComponent(editorField)
            .addLabeledComponent("Base delay (ms):", speed)
            .addLabeledComponent("Jitter (ms):", jitter)
            .addLabeledComponent("Newline pause (ms):", newline)
            .addComponent(raw)
        // No comment syntax means write() can never install a header (see its kdoc) -- the
        // controls above are disabled for exactly that reason; this explains why, rather than
        // leaving the speaker to wonder why they don't respond.
        if (!syntax.hasAny) {
            builder.addComponent(JBLabel("This file type has no comment syntax -- timing directives are unavailable."))
        }
        return builder
            .addComponent(JBLabel("Hotkey: ${hotkeyText()} — change it in Settings > Keymap"))
            .panel
    }

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

    /**
     * Resolves each of the four directive fields independently via [DirectiveHeader.resolveField]
     * -- never writing the controls' values over a header the speaker edited directly, and never
     * writing an absent field's app-default fallback into a header that never had it -- then
     * writes the combined result only if it actually differs from what the header currently holds
     * (so an unmodified dialog never rewrites -- and thereby cosmetically reformats -- a header
     * nothing about this OK changed).
     */
    private fun saveDirectives() {
        if (!syntax.hasAny) {
            // Nothing to resolve: the controls are disabled and write() is a no-op for this
            // syntax regardless (see its kdoc).
            FileDocumentManager.getInstance().saveDocument(document)
            return
        }

        val sentinel = settings.state.sentinel
        val currentDirectives = DirectiveHeader.read(document.text, sentinel)

        val rawOutcome = DirectiveHeader.resolveField(
            opened = openedDirectives.raw,
            current = currentDirectives.raw,
            controlChanged = raw.isSelected != openedDirectives.raw,
            controlValue = raw.isSelected,
        )
        val speedOutcome = DirectiveHeader.resolveField(
            opened = openedDirectives.speedMs,
            current = currentDirectives.speedMs,
            controlChanged = (speed.value as Int) != speedBaseline,
            controlValue = speed.value as Int,
        )
        val jitterOutcome = DirectiveHeader.resolveField(
            opened = openedDirectives.jitterMs,
            current = currentDirectives.jitterMs,
            controlChanged = (jitter.value as Int) != jitterBaseline,
            controlValue = jitter.value as Int,
        )
        val newlineOutcome = DirectiveHeader.resolveField(
            opened = openedDirectives.newlineMs,
            current = currentDirectives.newlineMs,
            controlChanged = (newline.value as Int) != newlineBaseline,
            controlValue = newline.value as Int,
        )

        val resolved = Directives(
            raw = rawOutcome.value,
            speedMs = speedOutcome.value,
            jitterMs = jitterOutcome.value,
            newlineMs = newlineOutcome.value,
        )
        // Only write when something actually needs to change -- otherwise write() would still
        // regenerate a canonically-formatted header line even when every field resolved to
        // "unchanged", cosmetically rewriting a header the speaker authored with different
        // spacing or token order.
        if (resolved != currentDirectives) {
            val updated = DirectiveHeader.write(document.text, resolved, sentinel, syntax)
            if (updated != document.text) {
                WriteCommandAction.runWriteCommandAction(project) { document.setText(updated) }
            }
        }

        if (listOf(rawOutcome, speedOutcome, jitterOutcome, newlineOutcome).any { it.conflicted }) {
            SnippetRunner.notify(
                project,
                "${snippet.relativePath}: a timing field was edited directly in the header and " +
                    "also changed in this dialog -- kept the header's edited value",
                NotificationType.WARNING,
            )
        }
        FileDocumentManager.getInstance().saveDocument(document)
    }
}
