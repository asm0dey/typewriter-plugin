package com.github.asm0dey.typewriter.library

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent

/**
 * Recomputes the full set of registered snippet actions from every currently open project (spec
 * section 8, "Actions"/"Directories"): the union of the global toolkit and each open project's own
 * directory, project shadowing global on relative path -- exactly what [SnippetRegistrar.register]
 * already does as a full reconciliation, so this just recomputes the wanted set and hands it over.
 */
object SnippetSync {
    fun syncAll() {
        val paths = ProjectManager.getInstance().openProjects
            .flatMap { SnippetDirs.all(it) }
            .map { it.relativePath }
            .distinct()
        SnippetRegistrar.register(paths)
    }
}

/**
 * Registers every snippet's action once a project has finished opening (spec section 8, "Actions":
 * "early enough to precede keymap resolution"). No user keystroke is possible before a project
 * activity has had a chance to run, and `KeymapImpl` serialises every id in a saved keymap without
 * consulting [com.intellij.openapi.actionSystem.ActionManager] -- so a keymap binding for an id
 * that is not registered *yet* is not lost, only inert until registration catches up. That is the
 * actual constraint this activity has to satisfy: registered before the keystroke, not registered
 * before the keymap loads.
 */
class SnippetStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        SnippetSync.syncAll()
    }
}

/**
 * Keeps the registered action set live as snippet files come and go (spec section 8: "A VFS
 * listener keeps the set live: a new file registers an action immediately, without restart. A
 * deleted file unregisters its action..."). Two things this listener is deliberately narrow about:
 *
 * - **Scope**: [BulkFileListener.after] fires for every VFS event in the whole IDE, most of which
 *   have nothing to do with snippets. Re-running [SnippetSync.syncAll] on every one of them would
 *   do real work (walking both snippet directories, reconciling the registry) on every
 *   keystroke-driven save anywhere in the IDE. Only events whose path lies under the current
 *   global directory or an open project's own directory can matter here, so every other event is
 *   discarded before it can trigger a resync.
 * - **Event kind**: creation, deletion, move and rename all change *which* relative paths exist,
 *   so the registered action set can go stale. A content edit does not -- the action for an
 *   existing snippet already exists, and [SnippetLibrary.textOf] reads the live
 *   [com.intellij.openapi.editor.Document] at run time rather than a snapshot taken at
 *   registration, so an edited snippet needs no re-registration. Resyncing on every content change
 *   would be pure wasted work with no corresponding correctness benefit.
 */
class SnippetWatcher : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val dirs = snippetDirectories()
        if (dirs.isEmpty()) return
        if (events.none { isStructural(it) && isUnderAnyOf(it, dirs) }) return
        // Deferred rather than run inline: `after` fires from inside VFS event dispatch, and
        // SnippetSync.syncAll re-walks the VFS (SnippetLibrary.collect) to compute the new set --
        // safer to let that dispatch finish first.
        ApplicationManager.getApplication().invokeLater { SnippetSync.syncAll() }
    }

    private fun snippetDirectories(): List<VirtualFile> =
        listOfNotNull(SnippetDirs.global()) +
            ProjectManager.getInstance().openProjects.mapNotNull { SnippetDirs.project(it) }

    private fun isStructural(event: VFileEvent): Boolean = when (event) {
        is VFileCreateEvent, is VFileDeleteEvent, is VFileMoveEvent, is VFileCopyEvent -> true
        is VFilePropertyChangeEvent -> event.propertyName == VirtualFile.PROP_NAME
        else -> false
    }

    private fun isUnderAnyOf(event: VFileEvent, dirs: List<VirtualFile>): Boolean =
        dirs.any { dir -> FileUtil.isAncestor(dir.path, event.path, false) }
}
