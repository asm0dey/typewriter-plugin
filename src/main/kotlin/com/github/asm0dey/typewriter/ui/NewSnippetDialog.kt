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
import com.intellij.openapi.fileTypes.ExtensionFileNameMatcher
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VfsUtil
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
 * Turns a name and a [FileType] into a snippet-directory-relative path (spec section 9, "New
 * snippet"; spec question 23). A snippet has no separate language setting -- its extension IS the
 * language, because [SnippetLibrary][com.github.asm0dey.typewriter.library.SnippetLibrary] feeds
 * the resulting file's name straight into [FileTypeManager.getFileTypeByFileName], the same
 * mechanism that highlights it in its editor tab. Most types resolve by extension and need only a
 * filename. A handful resolve by exact name only -- no extension at all -- and for those
 * [relativePath] gives the stem a job it otherwise has no use for: naming a directory.
 */
object SnippetFileNames {

    /**
     * Every registered [FileType] usable as a snippet's type, sorted for a stable, scannable
     * dropdown.
     *
     * Filtered on two things:
     * - **not binary** -- a snippet is text a speaker types character by character; a binary
     *   type (images, archives, class files, ...) can never be one.
     * - **has either a real extension matcher or an exact-name matcher** -- [suggestName] has to
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
            .filter { extensionOf(it) != null || exactNameOf(it) != null }
            .sortedBy { it.displayName }

    /**
     * The extension [fileType] actually resolves files by, read from its
     * [ExtensionFileNameMatcher]s -- **never** from [FileType.getDefaultExtension], which is
     * misleading in both directions and cannot be trusted for this decision:
     *
     * - Docker's file type has `defaultExtension == ""` despite genuinely registering
     *   `ExtensionFileNameMatcher("dockerfile")` (`*.dockerfile`) -- trusting `defaultExtension`
     *   alone would wrongly treat it as needing the exact-only, stem-as-directory treatment.
     * - EditorConfig's file type has `defaultExtension == "editorconfig"` despite registering
     *   **no** [ExtensionFileNameMatcher] at all -- only a single `ExactFileNameMatcher(".editorconfig")`.
     *   Trusting `defaultExtension` there would wrongly treat it as extension-based and suggest
     *   `01-setup.editorconfig`, a name that does not actually resolve back to `EditorConfig` via
     *   [FileTypeManager.getFileTypeByFileName] -- exactly the "editor tab disagrees with the
     *   parser" divergence spec section 5 forbids.
     *
     * Both confirmed by printing [FileTypeManager.getAssociations] for the real file types on this
     * platform's test classpath, not assumed from either type's `defaultExtension`.
     *
     * When more than one [ExtensionFileNameMatcher] is registered -- Plain Text offers two,
     * `*.txt` and `*.log` -- the one matching [FileType.getDefaultExtension] (case-insensitively)
     * is preferred, since that field, while not trustworthy as a yes/no signal for "does this type
     * have an extension" (see above), *is* the platform's own declared preference once an
     * extension is known to exist. If [FileType.getDefaultExtension] matches none of the
     * candidates (not observed for any type on this platform's test classpath), the shortest
     * extension wins, alphabetical as the final tie-break -- same determinism reasoning as
     * [exactNameOf], for a case this project's plugin set doesn't currently produce.
     */
    fun extensionOf(fileType: FileType): String? {
        val candidates = FileTypeManager.getInstance().getAssociations(fileType)
            .filterIsInstance<ExtensionFileNameMatcher>()
            .map { it.extension }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.single()
        val default = fileType.defaultExtension
        return candidates.firstOrNull { it.equals(default, ignoreCase = true) }
            ?: candidates.sortedWith(compareBy<String> { it.length }.thenBy { it }).first()
    }

    /**
     * The canonical exact filename [fileType] is associated with via one or more
     * [ExactFileNameMatcher]s (`.editorconfig`, `Makefile`), or `null` if it has none. Consulted
     * only for a type with no [extensionOf] -- see [relativePath].
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
     * `firstOrNull()` started returning `Dockerfile.native` instead of `Dockerfile`. (Docker's file
     * type also carries an extension matcher -- see [extensionOf] -- so in practice `exactNameOf`
     * is never actually consulted for it any more; the canonicalisation below stays correct and
     * exercised for the exact-only types that do reach it, e.g. EditorConfig.)
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
     * happens to select `Dockerfile` for Docker's file type (were it ever consulted for Docker),
     * which is what spec section 9's own example ("prefills `Dockerfile`") expects.
     */
    fun exactNameOf(fileType: FileType): String? =
        FileTypeManager.getInstance().getAssociations(fileType)
            .filterIsInstance<ExactFileNameMatcher>()
            .map { it.presentableString }
            .sortedWith(compareBy<String> { it.contains('.') }.thenBy { it.length }.thenBy { it })
            .firstOrNull()

    /**
     * The chooser's display label for [fileType]: its [FileType.getDisplayName], with [extensionOf]
     * appended in parentheses when there is one -- `"Java (.java)"`, `"Dockerfile (.dockerfile)"`.
     * The extension is what picking a type actually means (see the class kdoc), and two entries
     * can share a display name while differing in extension, so the label needs to show it.
     * Exact-only types (no [extensionOf]) render as just their display name -- `"EditorConfig"`.
     */
    fun label(fileType: FileType): String {
        val extension = extensionOf(fileType)
        return if (extension == null) fileType.displayName else "${fileType.displayName} (.$extension)"
    }

    /**
     * Which of [choices] the dialog should open on. [currentFileType] is the file type of
     * whatever the speaker was just looking at (typically the currently open editor's file) --
     * the best available guess for what they are about to type next, since a snippet is almost
     * always written in the language on screen. Falls back to Plain Text (present in [choices]
     * whenever it is non-empty, since [PlainTextFileType] is never binary and always resolves an
     * [extensionOf]) when there is no current file, or its type is not offered here at all (e.g.
     * it is binary and so excluded from [choices]). A [choices] list that somehow omits Plain Text
     * too falls back to its first entry, so this never fails for a non-empty list.
     */
    fun preselected(choices: List<FileType>, currentFileType: FileType?): FileType =
        choices.firstOrNull { it == currentFileType }
            ?: choices.firstOrNull { it == PlainTextFileType.INSTANCE }
            ?: choices.firstOrNull()
            ?: PlainTextFileType.INSTANCE

    /**
     * Combines [stem] with [fileType] into a *filename* -- the leaf component only; see
     * [relativePath] for the full snippet-directory-relative path, which is what actually decides
     * where to create the file.
     *
     * - When [fileType] has an [extensionOf], [stem] is combined with it. If [stem] already ends
     *   with that same extension (case-insensitively), the suffix is not duplicated --
     *   re-suggesting a name for the type already typed must not produce `"foo.java.java"`. A
     *   *different* trailing extension in [stem] (the speaker typed the wrong one, or none of this
     *   matters yet mid-typing) is left as-is and [fileType]'s own extension is still appended
     *   last, so the filename's actual, final extension -- the one [FileTypeManager] reads -- is
     *   always the type chosen in the dropdown, never shadowed by whatever the name field happens
     *   to contain. An empty [stem] is not special-cased: it mechanically yields `".$extension"`.
     * - Otherwise (an exact-only type) this returns [exactNameOf]'s fixed name, ignoring [stem]
     *   entirely -- the name is not negotiable (see [relativePath]'s kdoc for why), so this
     *   function alone cannot give the stem anything useful to do for such a type.
     *
     * Blank-[stem] rejection is a validation concern, not a naming concern here --
     * [NewSnippetDialog.doValidate] blocks OK on any blank stem, extension-based or exact-only,
     * since [relativePath] now needs a non-blank stem either way (as the extension prefix, or as
     * the directory name).
     */
    fun suggestName(fileType: FileType, stem: String): String {
        val extension = extensionOf(fileType)
        if (extension != null) {
            val base = stripIfAlreadyHasExtension(stem, extension)
            return "$base.$extension"
        }
        return exactNameOf(fileType) ?: stem
    }

    /**
     * The snippet-directory-relative path to create for [fileType] named from [stem] (spec
     * question 23).
     *
     * - **Extension-based types** (has an [extensionOf]) create a single file at the snippet
     *   directory's root: exactly [suggestName]'s result, e.g. `01-entity.java`. This covers
     *   `Dockerfile` too -- despite its nine [ExactFileNameMatcher]s, it also carries
     *   `ExtensionFileNameMatcher("dockerfile")`, so `01-build.dockerfile` already resolves to it
     *   correctly and needs no directory. Confirmed against the real platform, not assumed: an
     *   earlier version of this rule sent every exact-name type to a directory on the assumption
     *   Docker was exact-only, which printing its associations disproved.
     * - **Exact-only types** (an [exactNameOf] but no [extensionOf] -- confirmed to be 16
     *   registered non-binary types on this platform's test classpath, `.editorconfig` among them)
     *   put [stem] to work as a **directory** name instead: `"$stem/${exactNameOf(fileType)}"`,
     *   e.g. `01-setup/.editorconfig`. The exact name itself is not negotiable -- renaming
     *   `.editorconfig` to `01-setup.editorconfig` would stop it resolving via its
     *   `ExactFileNameMatcher` entirely, so the file would parse as plain text: no comment syntax,
     *   no markers, no pauses or actions. A directory is what gives such a type both sequencing
     *   (the `01-`/`02-` stem sort spec section 8 defines applies to the directory name) and
     *   multiplicity (two `.editorconfig` snippets can coexist as sibling directories) without
     *   ever touching the file's own name.
     * - A type with neither (excluded from [choices] already) falls back to [suggestName]'s own
     *   fallback -- [stem] unchanged.
     */
    fun relativePath(fileType: FileType, stem: String): String {
        if (extensionOf(fileType) != null) return suggestName(fileType, stem)
        val exactName = exactNameOf(fileType) ?: return suggestName(fileType, stem)
        return "$stem/$exactName"
    }

    private fun stripIfAlreadyHasExtension(stem: String, extension: String): String {
        val dot = stem.lastIndexOf('.')
        if (dot <= 0) return stem
        val trailing = stem.substring(dot + 1)
        return if (trailing.equals(extension, ignoreCase = true)) stem.substring(0, dot) else stem
    }
}

/**
 * Name + file-type chooser for creating a new snippet (spec section 9, "New snippet"; spec
 * question 23). The combo's selection is authoritative for the resulting file's extension (or, for
 * an exact-only type, its exact name) -- see [SnippetFileNames.relativePath] -- so there is no
 * separate language field, and the name field is always required: it is either the extension
 * prefix or the directory name, never optional.
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
    val relativePath: String get() = SnippetFileNames.relativePath(fileType, stemField.text.trim())

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", stemField)
            .addLabeledComponent("File type:", typeCombo)
            .panel as JPanel

    // A blank stem is now always invalid: it is either the extension prefix (extension-based
    // types) or the directory name (exact-only types) -- relativePath() has no meaningful use for
    // it either way. This used to exempt exact-name types, back when the stem was simply discarded
    // for them; spec question 23 gave it a job, so the exemption no longer applies to anything.
    override fun doValidate(): ValidationInfo? =
        if (stemField.text.isBlank()) {
            ValidationInfo("Name must not be empty", stemField)
        } else {
            null
        }
}

/**
 * Entry point for spec section 9's "New snippet": shows [NewSnippetDialog], then hands its
 * [NewSnippetDialog.relativePath] to [create].
 */
class NewSnippetAction : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dialog = NewSnippetDialog(project)
        if (!dialog.showAndGet()) return
        create(project, dialog.relativePath)
    }

    /**
     * Creates [relativePath] (e.g. `"01-entity.java"` or `"01-setup/.editorconfig"` -- see
     * [SnippetFileNames.relativePath]) under whichever configured snippet directory exists
     * (project directory wins when both do -- a new snippet during talk prep almost always belongs
     * to the talk), creating any intermediate directory via [VfsUtil.createDirectoryIfMissing]
     * rather than hand-rolling it, resyncs registered snippet actions so the result is playable
     * immediately, and opens it in a normal editor tab -- there is no custom text editor to
     * maintain. Returns the created file, or `null` if nothing was created (a duplicate, an I/O
     * failure, or -- only when both configured paths are blank -- no configured directory at all;
     * each already reported via [SnippetRunner.notify]). A configured directory that does not yet
     * exist is CREATED, not reported: see [SnippetDirs.forNewSnippet].
     *
     * Exposed as a standalone function, not folded into [actionPerformed], specifically so a test
     * can exercise the real creation path -- duplicate detection across a nested path, directory
     * creation, the I/O failure balloon -- without needing to show a [DialogWrapper] headlessly.
     * Only [actionPerformed] itself (showing the dialog, reading its result) stays UI-only and
     * untested.
     *
     * The duplicate check ([VfsUtil.findRelativeFile]) walks [relativePath]'s segments one at a
     * time and only reports a collision if the *final* segment (the file) already exists: an
     * exact-only type's stem directory already existing from an earlier, differently-named sibling
     * snippet is not a duplicate and must still succeed; only a second snippet resolving to the
     * exact same path is refused.
     */
    fun create(project: Project, relativePath: String): VirtualFile? {
        val directory = try {
            SnippetDirs.forNewSnippet(project)
        } catch (ex: IOException) {
            SnippetRunner.notify(
                project,
                "could not create the snippet directory: ${ex.message}",
                NotificationType.ERROR,
            )
            return null
        } ?: run {
            // Only reachable when BOTH configured paths are blank -- see SnippetDirs.forNewSnippet,
            // which creates a configured-but-absent directory rather than reporting it as unset.
            SnippetRunner.notify(
                project,
                "no snippet directory configured; set one in Settings > Tools > TypeWriter",
                NotificationType.ERROR,
            )
            return null
        }

        val segments = relativePath.split('/')
        if (VfsUtil.findRelativeFile(directory, *segments.toTypedArray()) != null) {
            SnippetRunner.notify(project, "$relativePath already exists", NotificationType.ERROR)
            return null
        }

        val parentPath = relativePath.substringBeforeLast('/', "")
        val leafName = relativePath.substringAfterLast('/')

        val created = try {
            WriteAction.compute<VirtualFile, IOException> {
                val targetDir =
                    if (parentPath.isEmpty()) directory else VfsUtil.createDirectoryIfMissing(directory, parentPath)
                targetDir.createChildData(this, leafName)
            }
        } catch (ex: IOException) {
            SnippetRunner.notify(project, "could not create $relativePath: ${ex.message}", NotificationType.ERROR)
            return null
        }
        SnippetSync.syncAll()
        FileEditorManager.getInstance(project).openFile(created, true)
        return created
    }
}
