package com.github.asm0dey.typewriter.ui

import com.github.asm0dey.typewriter.ide.SnippetFiles
import com.github.asm0dey.typewriter.library.DirectiveSidecar
import com.github.asm0dey.typewriter.library.SnippetDirs
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
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.ui.ComboboxSpeedSearch
import com.intellij.ui.EditorTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.io.IOException
import javax.swing.AbstractAction
import javax.swing.DefaultComboBoxModel
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
     * -- corruption arriving as added garbage rather than lost text. Timing for such a snippet
     * lives in [DirectiveSidecar] instead (spec section 9, resolved design question 22).
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
     * `SnippetDialog` shows the same directives in two editable places at once: the stored text
     * (the header, or -- for a comment-less snippet -- the sidecar; see [decideSave]) and the
     * dialog's own spinner/checkbox controls, which are only ever read to *seed* their initial
     * values when the dialog opens. Two failure modes fall out of that if OK writes the controls'
     * values unconditionally:
     *
     * 1. A speaker who edits the header directly in the editor (`// tw: speed 80` -> `90`),
     *    without touching the spinner, has that edit silently reverted to the spinner's stale `80`
     *    on OK -- the closest thing to data loss in this project, since unlike a bad run there is
     *    no abort/undo for it.
     * 2. A spinner cannot represent "this field is absent" -- it always shows *some* number,
     *    falling back to the app-wide default when there is none stored. Opening a snippet with
     *    nothing stored at all and simply clicking OK would therefore write a full header (or
     *    sidecar) that pins today's global defaults into that one file forever, invisibly, even
     *    though the speaker changed nothing.
     *
     * [resolveField] is a pure decision that fixes both, **per field** (a speaker who nudges only
     * the jitter spinner must not thereby also pin speed and newline): given
     * - [opened]: this field's value as it was stored when the dialog opened (`null` for a
     *   nullable field means nothing was stored),
     * - [current]: this field's value as it is stored *right now*, re-read at OK time -- differs
     *   from [opened] exactly when the speaker edited the stored text directly,
     * - [controlChanged]: whether the speaker moved this field's own control (spinner or
     *   checkbox) away from the value it was initialised to when the dialog opened,
     * - [controlValue]: that control's current value,
     *
     * it returns the value to actually write for this field, plus whether doing so required
     * silently picking a side:
     * - stored unchanged, control unchanged -> keep [current] (== [opened]; for an absent field
     *   this keeps it `null` -- the fix for failure mode 2 above).
     * - stored unchanged, control changed -> write [controlValue] -- the control is still the
     *   speaker's own tool for this field.
     * - stored changed, control unchanged -> keep [current] -- the speaker edited the stored text
     *   directly and meant it; this is the fix for failure mode 1 above.
     * - both changed -> keep [current] (the stored text is authoritative, spec section 5) but
     *   report `conflicted = true`, so the caller can tell the speaker their control change was
     *   not applied rather than silently discard either edit.
     */
    fun <T> resolveField(opened: T, current: T, controlChanged: Boolean, controlValue: T): FieldOutcome<T> {
        val storedChanged = current != opened
        return when {
            !storedChanged && controlChanged -> FieldOutcome(controlValue, conflicted = false)
            !storedChanged -> FieldOutcome(current, conflicted = false)
            !controlChanged -> FieldOutcome(current, conflicted = false)
            else -> FieldOutcome(current, conflicted = true)
        }
    }

    /** Which store [SnippetDialog.saveDirectives] should write [SaveDecision.toWrite] to. */
    enum class Store { HEADER, SIDECAR }

    /**
     * The complete outcome of one OK: what to write, whether writing is even needed, where it
     * goes, and whether doing so required silently picking a side. See [decideSave].
     */
    data class SaveDecision(val store: Store, val toWrite: Directives, val needsWrite: Boolean, val conflicted: Boolean)

    /**
     * The single pure function behind `SnippetDialog`'s OK button -- combining [resolveField] for
     * all four fields, the write gate (`toWrite != current`), and the header-vs-sidecar store
     * routing into one decision `SnippetDialog` only has to execute, not compute. Extracted
     * specifically because this combination -- assembling [Directives] from four outcomes, the
     * write gate, and the store routing -- previously sat entirely inside the `DialogWrapper`,
     * where nothing could test it directly; the two most serious defects a review round found
     * both lived in exactly that untested seam (the playback path never consulting the sidecar,
     * and a header-less/sidecar-less snippet's spinners pinning app defaults into it on a bare
     * OK). This function has no dependency on `SnippetDialog`, the UI toolkit, or any store's own
     * I/O -- only on [resolveField] and plain values -- so it is testable with no fixture.
     *
     * [hasCommentSyntax] decides [SaveDecision.store] ([Store.HEADER] when true, [Store.SIDECAR]
     * otherwise) but is not itself part of [resolveField]'s per-field comparisons -- [opened] and
     * [current] already reflect whichever store [hasCommentSyntax] selects, read by the caller
     * before this function is called.
     */
    fun decideSave(
        hasCommentSyntax: Boolean,
        opened: Directives,
        current: Directives,
        controlRaw: Boolean,
        controlSpeedMs: Int,
        controlJitterMs: Int,
        controlNewlineMs: Int,
        speedBaseline: Int,
        jitterBaseline: Int,
        newlineBaseline: Int,
    ): SaveDecision {
        val rawOutcome = resolveField(opened.raw, current.raw, controlRaw != opened.raw, controlRaw)
        val speedOutcome = resolveField(opened.speedMs, current.speedMs, controlSpeedMs != speedBaseline, controlSpeedMs)
        val jitterOutcome =
            resolveField(opened.jitterMs, current.jitterMs, controlJitterMs != jitterBaseline, controlJitterMs)
        val newlineOutcome =
            resolveField(opened.newlineMs, current.newlineMs, controlNewlineMs != newlineBaseline, controlNewlineMs)
        val resolved = Directives(
            raw = rawOutcome.value,
            speedMs = speedOutcome.value,
            jitterMs = jitterOutcome.value,
            newlineMs = newlineOutcome.value,
        )
        return SaveDecision(
            store = if (hasCommentSyntax) Store.HEADER else Store.SIDECAR,
            toWrite = resolved,
            needsWrite = resolved != current,
            conflicted = listOf(rawOutcome, speedOutcome, jitterOutcome, newlineOutcome).any { it.conflicted },
        )
    }
}

/**
 * Edit a snippet's text and its timing directives together (spec section 9, "Snippet dialog").
 * The [EditorTextField] is built over the snippet file's **existing** [Document] -- never a copy
 * -- so the dialog, any already-open editor tab on the same file, and the player all read the one
 * buffer. Cancel therefore does not revert typed text: only OK/Cancel governs the timing fields.
 *
 * Where timing is written depends on [syntax] (resolved design question 22): a comment-capable
 * snippet's timing lives in its own directive header, written on OK only per-field where the
 * speaker's own header edit doesn't already win -- see [DirectiveHeader.decideSave]. A
 * comment-less snippet (`!syntax.hasAny` -- no [com.intellij.lang.Commenter] for its language) has
 * no header to hold it at all: `DirectiveHeader.write` is a no-op for exactly that case, so its
 * timing instead lives in [DirectiveSidecar], a `.twmeta` file co-located with the snippet. The
 * four timing controls work identically either way -- the same [DirectiveHeader.decideSave]
 * decision, just reading and writing a different store -- and stay enabled in both cases; only
 * `pause`/`action` markers are unavailable for a comment-less snippet, and this dialog never
 * offered those regardless.
 *
 * Precondition: the caller ([EditSnippetAction]) has already verified [snippet.file][Snippet.file]
 * has a live [Document] before constructing this dialog. A binary file has none; this class does
 * not itself handle that case.
 */
class SnippetDialog private constructor(
    private val project: Project,
    /**
     * Null until the snippet exists on disk. A NEW snippet is authored entirely in memory and is
     * only written on OK/Play ([materialise]), so everything keyed to a real file -- the sidecar
     * store, the hotkey, Open in Editor, playback -- is absent until then and must be guarded.
     */
    private var snippet: Snippet?,
    initialFileType: FileType,
) : DialogWrapper(project) {

    companion object {
        /** Edit an existing snippet. Its file type is fixed; the language combo is not shown. */
        fun forExisting(project: Project, snippet: Snippet) =
            SnippetDialog(project, snippet, snippet.fileType)

        /**
         * Author a new snippet. Opens on an empty in-memory document with a language combo, and
         * writes a file only when the speaker commits (OK or Play) -- see [materialise].
         */
        fun forNewSnippet(project: Project): SnippetDialog {
            val current = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.fileType
            val preselected = SnippetFileNames.preselected(SnippetFileNames.choices(), current)
            return SnippetDialog(project, null, preselected)
        }
    }

    private var fileType: FileType = initialFileType

    /** The combo, in new mode only: an existing snippet's type is its file name's business. */
    private val typeCombo: ComboBox<FileType>? = if (snippet != null) {
        null
    } else {
        val choices = SnippetFileNames.choices()
        ComboBox(DefaultComboBoxModel(choices.toTypedArray())).apply {
            renderer = SimpleListCellRenderer.create("") { SnippetFileNames.label(it) }
            selectedItem = initialFileType
            ComboboxSpeedSearch.installSpeedSearch(this) { SnippetFileNames.label(it) }
            addActionListener {
                fileType = selectedItem as FileType
                // A NEW document, carrying the text over: the old one is backed by a PsiFile of
                // the previous language (see [newSnippetDocument]), and reusing it would leave the
                // editor's PSI -- and so reformat, completion and inspections -- speaking the
                // language the speaker just switched away from.
                document = newSnippetDocument(project, fileType, document.text)
                editorField.setNewDocumentAndFileType(fileType, document)
            }
        }
    }

    private var document: Document = snippet
        ?.let { FileDocumentManager.getInstance().getDocument(it.file)!! }
        ?: newSnippetDocument(project, initialFileType, "")

    private val editorField = EditorTextField(document, project, fileType, false, false).apply {
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

    /**
     * Comment syntax for the snippet's own language -- see the class kdoc for what depends on it.
     * Computed, not stored: in new mode the language combo can change it after construction, and
     * it decides which store the timing goes to (header vs sidecar).
     */
    private val syntax get() = CommentSyntax.of(LanguageUtil.getFileTypeLanguage(fileType) ?: Language.ANY)

    /** The directives as the dialog read them when it opened, from whichever store [syntax]
     * selects -- see [DirectiveHeader.decideSave]. */
    private val openedDirectives = currentDirectives()

    // The value each spinner is initialised to: the stored value, or the app default when there is
    // none. Doubles as the baseline saveDirectives compares the live spinner against to tell
    // whether the speaker actually touched it, since a JSpinner (unlike the header or the sidecar)
    // cannot itself represent "unset".
    private val speedBaseline = openedDirectives.speedMs ?: settings.state.speedMs
    private val jitterBaseline = openedDirectives.jitterMs ?: settings.state.jitterMs
    private val newlineBaseline = openedDirectives.newlineMs ?: settings.state.newlineMs

    private val speed = JSpinner(SpinnerNumberModel(speedBaseline, 0, 5000, 10))
    private val jitter = JSpinner(SpinnerNumberModel(jitterBaseline, 0, 5000, 5))
    private val newline = JSpinner(SpinnerNumberModel(newlineBaseline, 0, 5000, 50))
    private val raw = JBCheckBox("Type as authored (raw)", openedDirectives.raw)

    private var playOnClose = false

    init {
        title = snippet?.let { "Snippet: ${it.relativePath}" } ?: "New TypeWriter Snippet"
        init()
    }

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .let { if (typeCombo != null) it.addLabeledComponent("Language:", typeCombo) else it }
            .addComponent(editorField)
            .addLabeledComponent("Base delay (ms):", speed)
            .addLabeledComponent("Jitter (ms):", jitter)
            .addLabeledComponent("Newline pause (ms):", newline)
            .addComponent(raw)
            .addComponent(JBLabel("Hotkey: ${hotkeyText()} — change it in Settings > Keymap"))
            .panel

    /** The directives currently stored for this snippet, from whichever store [syntax] selects. */
    private fun currentDirectives(): Directives = if (syntax.hasAny) {
        DirectiveHeader.read(document.text, settings.state.sentinel)
    } else {
        // No file yet in new mode, so nothing is stored anywhere: the spinners start at the app
        // defaults, exactly as they do for a snippet whose header carries no timing.
        snippet?.let { DirectiveSidecar.read(it.file) } ?: Directives()
    }

    private fun hotkeyText(): String {
        // A new snippet has no action id until it is written, so it has no hotkey to report yet.
        val id = snippet?.id ?: return "unbound"
        val shortcuts = KeymapManager.getInstance().activeKeymap.getShortcuts(id)
        return if (shortcuts.isEmpty()) "unbound" else KeymapUtil.getShortcutText(shortcuts.first())
    }

    // Open in Editor only exists once there is a file to open: in new mode nothing is written
    // until OK/Play, so the button would have nothing to point at.
    override fun createActions() = listOfNotNull(
        snippet?.let { existing ->
            object : AbstractAction("Open in Editor") {
                override fun actionPerformed(e: ActionEvent?) {
                    FileEditorManager.getInstance(project).openFile(existing.file, true)
                    close(CANCEL_EXIT_CODE)
                }
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
    ).toTypedArray()

    // A new snippet with nothing in it would be written as an empty file that types nothing.
    override fun doValidate(): ValidationInfo? = when {
        snippet == null && document.text.isBlank() ->
            ValidationInfo("Write something to type, or cancel", editorField)
        else -> null
    }

    override fun doOKAction() {
        // New mode: this is the point the file comes into existence. If it cannot be written the
        // dialog stays open with the speaker's text intact rather than closing and losing it.
        if (snippet == null && !materialise()) return
        saveDirectives()
        super.doOKAction()
        if (playOnClose) {
            // A tick after disposal: dialog teardown routes actions through the action system,
            // and the abort watcher cancels on any action (spec section 9, "Snippet dialog").
            ApplicationManager.getApplication().invokeLater {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                snippet?.let { SnippetRunner.run(project, editor, it) }
            }
        }
    }

    /**
     * Thin shell around [DirectiveHeader.decideSave]: gather the current controls and stored
     * state, hand them to the pure decision, then execute exactly what it returns.
     */
    private fun saveDirectives() {
        val decision = DirectiveHeader.decideSave(
            hasCommentSyntax = syntax.hasAny,
            opened = openedDirectives,
            current = currentDirectives(),
            controlRaw = raw.isSelected,
            controlSpeedMs = speed.value as Int,
            controlJitterMs = jitter.value as Int,
            controlNewlineMs = newline.value as Int,
            speedBaseline = speedBaseline,
            jitterBaseline = jitterBaseline,
            newlineBaseline = newlineBaseline,
        )

        if (decision.needsWrite) {
            when (decision.store) {
                DirectiveHeader.Store.HEADER -> {
                    val updated = DirectiveHeader.write(document.text, decision.toWrite, settings.state.sentinel, syntax)
                    if (updated != document.text) {
                        WriteCommandAction.runWriteCommandAction(project) { document.setText(updated) }
                    }
                }
                DirectiveHeader.Store.SIDECAR ->
                    snippet?.let { DirectiveSidecar.write(it.file, decision.toWrite) }
            }
        }

        if (decision.conflicted) {
            SnippetRunner.notify(
                project,
                "${snippet?.relativePath}: a timing field's stored value changed while this dialog " +
                    "was open, and also changed here -- kept the stored value",
                NotificationType.WARNING,
            )
        }
        FileDocumentManager.getInstance().saveDocument(document)
    }


    /**
     * Writes the in-memory snippet to disk under a generated, collision-free name and adopts it,
     * so everything after this point (timing store, playback) works exactly as it does for a
     * snippet that was opened rather than created.
     *
     * The name is generated rather than asked for ([SnippetFileNames.workingRelativePath]): the
     * speaker has just written the thing, so naming it up front only produced a name that
     * collided with whatever already sat in the directory. Rename afterwards if it matters.
     *
     * Returns false when the file could not be created -- [NewSnippetAction.create] has already
     * reported why -- leaving the dialog open with the text still in it.
     */
    private fun materialise(): Boolean {
        val text = document.text
        val directory = try {
            SnippetDirs.forNewSnippet(project)
        } catch (ex: IOException) {
            SnippetRunner.notify(
                project,
                "could not create the snippet directory: ${ex.message}",
                NotificationType.ERROR,
            )
            return false
        } ?: run {
            SnippetRunner.notify(
                project,
                "no snippet directory configured; set one in Settings > Tools > TypeWriter",
                NotificationType.ERROR,
            )
            return false
        }

        val relative = SnippetFileNames.workingRelativePath(directory, fileType)
        val file = NewSnippetAction().create(project, relative) ?: return false
        val fileDocument = FileDocumentManager.getInstance().getDocument(file) ?: return false
        WriteCommandAction.runWriteCommandAction(project) { fileDocument.setText(text) }

        // Rebind to the FILE's document: saveDirectives writes a header through it and then saves
        // it, neither of which would reach disk through the in-memory scratch document.
        document = fileDocument
        snippet = SnippetDirs.all(project).firstOrNull { it.file == file }
        return snippet != null
    }
}

/**
 * A document for a snippet that has no file yet, backed by a real [com.intellij.psi.PsiFile]
 * of [type].
 *
 * Not `EditorFactory.createDocument`: that yields a document with no PSI behind it, and every
 * language feature in the dialog's editor is a function of PSI. Reformat Code in particular
 * silently does nothing, because there is no file for it to format -- which is exactly what
 * was reported. Completion, inspections and brace matching degrade the same way.
 *
 * `eventSystemEnabled = true` is the part that matters: it gives the file a
 * [com.intellij.testFramework.LightVirtualFile] and keeps PSI and Document in sync, so edits
 * in the editor reach the PSI that the platform's actions operate on. With it false the file
 * is a detached parse tree and reformat would still do nothing.
 *
 * The name only has to be one the type actually accepts -- [SnippetFileNames.suggestName]
 * already knows how to build one for an exact-name type (`Dockerfile`, `.editorconfig`) as
 * well as an extension-based one -- since it is what the platform maps back to [type].
 */
internal fun newSnippetDocument(project: Project, type: FileType, text: String): Document {
    val psi = PsiFileFactory.getInstance(project)
        .createFileFromText(SnippetFileNames.suggestName(type, "snippet"), type, text, 0L, true)
    // Mark it so the snippet predicates accept a file that is under no directory at all --
    // otherwise the dialog loses marker completion and fragment-highlighting suppression.
    psi.virtualFile?.putUserData(SnippetFiles.SCRATCH, true)
    return PsiDocumentManager.getInstance(project).getDocument(psi)
        ?: EditorFactory.getInstance().createDocument(text)
}
