package com.github.asm0dey.typewriter.ide

import com.github.asm0dey.typewriter.library.SnippetDirs
import com.intellij.codeInsight.daemon.impl.analysis.DefaultHighlightingSettingProvider
import com.intellij.codeInsight.daemon.impl.analysis.FileHighlightingSetting
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Whether a [VirtualFile] lives under one of the two configured snippet directories (spec section
 * 8, "Directories") -- global or the current project's own. Snippets nest in subdirectories (a
 * talk-slug folder, for instance), so ancestry -- not direct parentage -- is what [isUnder] checks.
 *
 * [SnippetHighlightingSettingProvider] uses [isSnippet] to suppress analysis noise on snippet
 * files (spec section 8, "Snippet files in the IDE"); Task 15's completion contributor uses the
 * same predicate to decide whether to offer marker completions.
 */
object SnippetFiles {

    /**
     * Marks the in-memory file [com.github.asm0dey.typewriter.ui.newSnippetDocument] builds for a
     * snippet being authored in the dialog, before any file exists on disk.
     *
     * Both snippet predicates are "is this file under a configured snippet directory", which that
     * file cannot be -- it has no directory at all. Without this marker the dialog that is now the
     * primary authoring surface would lose the two things spec sections 8 and 9 promise a snippet
     * file: `tw:` marker completion, and suppressed highlighting on a deliberately-incomplete
     * fragment. Both worked when a new snippet was edited as a real file in the snippet directory,
     * so both are regressions to prevent rather than features to add.
     */
    val SCRATCH = Key.create<Boolean>("typewriter.scratchSnippet")

    fun isSnippet(project: Project, file: VirtualFile): Boolean =
        isScratch(file) || isUnder(file, roots(project))

    /** True for the dialog's not-yet-saved snippet -- see [SCRATCH]. */
    fun isScratch(file: VirtualFile): Boolean = file.getUserData(SCRATCH) == true

    /**
     * Exposed separately from [isSnippet] so [SnippetHighlightingSettingProvider.settingFor] can
     * be tested against an explicit root list, without resolving the configured directories
     * through [Project] / VFS lookups.
     */
    fun isUnder(file: VirtualFile, roots: List<VirtualFile>): Boolean =
        roots.any { VfsUtilCore.isAncestor(it, file, false) }

    fun roots(project: Project): List<VirtualFile> =
        listOfNotNull(SnippetDirs.global(), SnippetDirs.project(project))
}

/**
 * Spec section 8, "Snippet files in the IDE": snippets are fragments, often deliberately broken,
 * so red squiggles on them are never signal. `SKIP_HIGHLIGHTING` (not `SKIP_INSPECTION`) is used
 * because `SKIP_INSPECTION` leaves the highlighting pass running, so parser errors -- an unclosed
 * class, most snippets -- would still show red. Lexer-based colouring (keywords, strings,
 * comments) comes from the editor rather than the daemon and survives either level.
 */
class SnippetHighlightingSettingProvider : DefaultHighlightingSettingProvider() {

    override fun getDefaultSetting(project: Project, file: VirtualFile): FileHighlightingSetting? =
        settingFor(file, SnippetFiles.roots(project))

    /** Separated so it can be tested without resolving the configured directories. */
    fun settingFor(file: VirtualFile, roots: List<VirtualFile>): FileHighlightingSetting? =
        if (SnippetFiles.isScratch(file) || SnippetFiles.isUnder(file, roots)) {
            FileHighlightingSetting.SKIP_HIGHLIGHTING
        } else {
            null
        }
}
