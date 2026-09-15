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
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
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
     */
    fun choices(): List<FileType> =
        FileTypeManager.getInstance().registeredFileTypes
            .filterNot { it.isBinary }
            .filter { it.defaultExtension.isNotEmpty() || exactNameOf(it) != null }
            .sortedBy { it.displayName }

    /**
     * The exact filename [fileType] is associated with via an [ExactFileNameMatcher]
     * (`Dockerfile`, `Makefile`, `.gitignore`), or `null` if it is matched by extension instead.
     */
    fun exactNameOf(fileType: FileType): String? =
        FileTypeManager.getInstance().getAssociations(fileType)
            .filterIsInstance<ExactFileNameMatcher>()
            .firstOrNull()
            ?.presentableString

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
 */
class NewSnippetDialog(project: Project) : DialogWrapper(project) {

    private val stemField = JBTextField("01-snippet")
    private val typeCombo = JComboBox(DefaultComboBoxModel(SnippetFileNames.choices().toTypedArray()))

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
