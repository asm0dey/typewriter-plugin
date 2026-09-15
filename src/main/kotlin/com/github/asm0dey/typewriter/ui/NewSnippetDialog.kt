package com.github.asm0dey.typewriter.ui

import com.github.asm0dey.typewriter.library.SnippetDirs
import com.github.asm0dey.typewriter.library.SnippetRunner
import com.github.asm0dey.typewriter.library.SnippetSync
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.ExactFileNameMatcher
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ComboboxSpeedSearch
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.io.IOException
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Turns a name and a [FileType] into a filename (spec section 9, "New snippet"). A snippet has no
 * separate language setting -- its extension IS the language, because [SnippetLibrary][com.github.asm0dey.typewriter.library.SnippetLibrary]
 * feeds the resulting file's name straight into [FileTypeManager.getFileTypeByFileName], the same
 * mechanism that highlights it in its editor tab. That is why a *filename* is derived here, not
 * just an extension: several useful snippet types are identified by exact name and have no
 * extension at all -- `Dockerfile`, `Makefile`, `.gitignore`.
 */
object SnippetFileNames {

    /**
     * Every registered [FileType] usable as a snippet's type, sorted for a stable, scannable
     * dropdown.
     *
     * Filtered on two things:
     * - **not binary** -- a snippet is text a speaker types character by character; a binary
     *   type (images, archives, class files, ...) can never be one.
     * - **has either a real default extension or an exact-name matcher** -- [suggestName] has to
     *   turn the chosen type into an actual filename, and a type with neither (no meaningful
     *   extension, no fixed name) gives it nothing to build one from. In practice this trims a
     *   handful of internal/placeholder types [FileTypeManager] registers alongside the ones a
     *   speaker would ever pick, on top of the binary exclusion the spec calls out by name.
     *
     * This deliberately does not restrict to a hand-picked allowlist of "common" languages: the
     * whole point of this rewrite is that XML, YAML, Markdown and anything else the IDE has a
     * plugin for work as first-class snippet types, not just the handful a maintainer thought to
     * name. If a speaker's language genuinely is not registered as a [FileType] at all -- no
     * plugin installed for it -- it cannot appear here; the workaround is to install the relevant
     * language plugin (or pick "Plain Text" and rename the file afterward, accepting that it
     * types with no comment-based markers until the extension is fixed).
     *
     * On a real IDE this list runs to 100+ entries, several of them near-duplicates (multiple
     * Angular/Svg language-service versions, a run of `*ignore` file variants) -- that noise is
     * inherent to listing "every registered non-binary FileType" as spec section 9 requires, and
     * is not filtered further here. [preselected] and the chooser's speed search (installed in
     * [NewSnippetDialog]) are what make a 100+-entry list usable, not narrowing this list.
     */
    fun choices(): List<FileType> =
        FileTypeManager.getInstance().registeredFileTypes
            .filterNot { it.isBinary }
            .filter { it.defaultExtension.isNotEmpty() || exactNameOf(it) != null }
            .sortedBy { it.displayName }

    /**
     * The canonical exact filename [fileType] is associated with via one or more
     * [ExactFileNameMatcher]s (`Dockerfile`, `Makefile`, `.gitignore`), or `null` if it is matched
     * by extension instead.
     *
     * A [FileType] can register *several* exact-name matchers -- Docker's, for example, offers
     * nine: `Dockerfile`, `Containerfile`, and seven dotted build-target variants
     * (`Dockerfile.native`, `Dockerfile.jvm`, `Dockerfile.fast-jar`, `Dockerfile.legacy-jar`,
     * `Dockerfile.native-micro`, `Dockerfile.native-distroless`, `Dockerfile.jlink`) -- confirmed
     * by printing [FileTypeManager.getAssociations] for the Docker file type from a throwaway test
     * run rather than assumed. The platform does not document or guarantee an order for
     * [FileTypeManager.getAssociations]'s result, so picking `.firstOrNull()` off it (the original
     * implementation here) is non-deterministic in principle even when it happens to work today --
     * exactly what broke the moment Task 18 put the Docker plugin on the test classpath and
     * `firstOrNull()` started returning `Dockerfile.native` instead of `Dockerfile`.
     *
     * Canonicalisation rule, applied as a three-level sort over every exact name offered:
     * 1. **No dot beats a dot.** A dotted variant (`Dockerfile.native`) reads as a
     *    specialisation of a base form, not a name in its own right -- the base form is what a
     *    speaker means by "a Dockerfile snippet".
     * 2. **Shortest wins among remaining candidates.** Docker still offers two no-dot names --
     *    `Dockerfile` (10 chars) and `Containerfile` (13 chars) -- so dot-count alone does not
     *    settle it; the shorter, more generic name is preferred.
     * 3. **Alphabetical as the final tie-break**, purely so the result can never depend on
     *    iteration order when a future [FileType] offers two no-dot names of equal length.
     *
     * This is a judgement call, not a platform contract -- there is no API signal for "the
     * canonical one" among several exact-name matchers. It is documented here, deterministic, and
     * happens to select `Dockerfile` for Docker's file type, which is what every existing test and
     * the spec's own example (section 9: "prefills `Dockerfile`") expect.
     */
    fun exactNameOf(fileType: FileType): String? =
        FileTypeManager.getInstance().getAssociations(fileType)
            .filterIsInstance<ExactFileNameMatcher>()
            .map { it.presentableString }
            .sortedWith(compareBy<String> { it.contains('.') }.thenBy { it.length }.thenBy { it })
            .firstOrNull()

    /**
     * The chooser's display label for [fileType]: its [FileType.getDisplayName], with the default
     * extension appended in parentheses when there is one -- `"Java (.java)"`. The extension is
     * what picking a type actually means (see the class kdoc), and two entries can share a
     * display name while differing in extension, so the label needs to show it. Exact-name types
     * (no default extension) render as just their display name.
     */
    fun label(fileType: FileType): String {
        val extension = fileType.defaultExtension
        return if (extension.isEmpty()) fileType.displayName else "${fileType.displayName} (.$extension)"
    }

    /**
     * Which of [choices] the dialog should open on. [currentFileType] is the file type of
     * whatever the speaker was just looking at (typically the currently open editor's file) --
     * the best available guess for what they are about to type next, since a snippet is almost
     * always written in the language on screen. Falls back to Plain Text (present in [choices]
     * whenever it is non-empty, since [PlainTextFileType] is never binary and always has a
     * default extension) when there is no current file, or its type is not offered here at all
     * (e.g. it is binary and so excluded from [choices]). A [choices] list that somehow omits
     * Plain Text too falls back to its first entry, so this never fails for a non-empty list.
     */
    fun preselected(choices: List<FileType>, currentFileType: FileType?): FileType =
        choices.firstOrNull { it == currentFileType }
            ?: choices.firstOrNull { it == PlainTextFileType.INSTANCE }
            ?: choices.firstOrNull()
            ?: PlainTextFileType.INSTANCE

    /**
     * Combines [stem] with [fileType] into a filename that resolves back to [fileType] via
     * [FileTypeManager.getFileTypeByFileName] -- the extension drives language detection, so the
     * chosen type must always be the one that wins.
     *
     * - An exact-name type ignores [stem] entirely and returns its fixed name.
     * - Otherwise [stem] is combined with [fileType]'s own [FileType.getDefaultExtension]. If
     *   [stem] already ends with that same extension (case-insensitively), the suffix is not
     *   duplicated -- re-suggesting a name for the type already typed must not produce
     *   `"foo.java.java"`. A *different* trailing extension in [stem] (the speaker typed the
     *   wrong one, or none of this matters yet mid-typing) is left as-is and [fileType]'s own
     *   extension is still appended last, so the filename's actual, final extension -- the one
     *   [FileTypeManager] reads -- is always the type chosen in the dropdown, never shadowed by
     *   whatever the name field happens to contain.
     * - An empty [stem] is not special-cased here: it mechanically yields `".$extension"`. Blank
     *   input is a validation concern, not a naming concern -- [NewSnippetDialog.doValidate]
     *   blocks OK on a blank stem for any extension-based type before this is ever used to create
     *   a file.
     */
    fun suggestName(fileType: FileType, stem: String): String {
        exactNameOf(fileType)?.let { return it }
        val extension = fileType.defaultExtension
        if (extension.isEmpty()) return stem
        val base = stripIfAlreadyHasExtension(stem, extension)
        return "$base.$extension"
    }

    private fun stripIfAlreadyHasExtension(stem: String, extension: String): String {
        val dot = stem.lastIndexOf('.')
        if (dot <= 0) return stem
        val trailing = stem.substring(dot + 1)
        return if (trailing.equals(extension, ignoreCase = true)) stem.substring(0, dot) else stem
    }
}

/**
 * Name + file-type chooser for creating a new snippet (spec section 9, "New snippet"). The
 * combo's selection is authoritative for the resulting filename's extension -- see
 * [SnippetFileNames.suggestName] -- so there is no separate language field.
 *
 * The combo shows [SnippetFileNames.label] for every entry (not a bare [FileType], whose default
 * `toString()` is unreadable Java object noise), opens preselected on [SnippetFileNames.preselected]
 * for the currently open file, and carries a [ComboboxSpeedSearch] over the same label so any of
 * the 100+ registered types is reachable by typing a few letters, without needing to reorder --
 * and thereby make less predictable -- the otherwise fully alphabetical, unfiltered list spec
 * section 9 requires.
 */
class NewSnippetDialog(project: Project) : DialogWrapper(project) {

    private val stemField = JBTextField("01-snippet")
    private val choices = SnippetFileNames.choices()
    private val typeCombo = JComboBox(DefaultComboBoxModel(choices.toTypedArray())).apply {
        renderer = SimpleListCellRenderer.create("") { SnippetFileNames.label(it) }
        val currentFileType = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.fileType
        selectedItem = SnippetFileNames.preselected(choices, currentFileType)
        ComboboxSpeedSearch.installSpeedSearch(this) { SnippetFileNames.label(it) }
    }

    init {
        title = "New TypeWriter Snippet"
        init()
    }

    val fileType: FileType get() = typeCombo.selectedItem as FileType
    val fileName: String get() = SnippetFileNames.suggestName(fileType, stemField.text.trim())

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", stemField)
            .addLabeledComponent("File type:", typeCombo)
            .panel as JPanel

    override fun doValidate(): ValidationInfo? =
        if (stemField.text.isBlank() && SnippetFileNames.exactNameOf(fileType) == null) {
            ValidationInfo("Name must not be empty", stemField)
        } else {
            null
        }
}

/**
 * Entry point for spec section 9's "New snippet": shows [NewSnippetDialog], creates the resulting
 * file in whichever configured snippet directory exists (project directory wins when both do --
 * a new snippet during talk prep almost always belongs to the talk), resyncs the registered
 * actions so it is playable immediately, and opens it in a normal editor tab. There is no custom
 * text editor to maintain.
 */
class NewSnippetAction : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dialog = NewSnippetDialog(project)
        if (!dialog.showAndGet()) return

        val directory = SnippetDirs.project(project) ?: SnippetDirs.global() ?: run {
            SnippetRunner.notify(
                project,
                "no snippet directory; set one in Settings > Tools > TypeWriter",
                NotificationType.ERROR,
            )
            return
        }

        val name = dialog.fileName
        if (directory.findChild(name) != null) {
            SnippetRunner.notify(project, "$name already exists", NotificationType.ERROR)
            return
        }

        val created = try {
            WriteAction.compute<VirtualFile, IOException> { directory.createChildData(this, name) }
        } catch (ex: IOException) {
            SnippetRunner.notify(project, "could not create $name: ${ex.message}", NotificationType.ERROR)
            return
        }
        SnippetSync.syncAll()
        FileEditorManager.getInstance(project).openFile(created, true)
    }
}
