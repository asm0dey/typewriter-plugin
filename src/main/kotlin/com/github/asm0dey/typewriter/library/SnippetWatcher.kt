package com.github.asm0dey.typewriter.library

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.io.FileUtil
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
 *   keystroke-driven save anywhere in the IDE. Only events that touch the current global directory
 *   or an open project's own directory can matter here, so every other event is discarded before
 *   it can trigger a resync.
 * - **Event kind**: creation, deletion, move and rename all change *which* relative paths exist,
 *   so the registered action set can go stale. A content edit does not -- the action for an
 *   existing snippet already exists, and [SnippetLibrary.textOf] reads the live
 *   [com.intellij.openapi.editor.Document] at run time rather than a snapshot taken at
 *   registration, so an edited snippet needs no re-registration. Resyncing on every content change
 *   would be pure wasted work with no corresponding correctness benefit.
 *
 * Scope is checked against the **configured path** ([SnippetDirs.globalPath] /
 * [SnippetDirs.projectPath]), not against a [com.intellij.openapi.vfs.VirtualFile] resolved via
 * [SnippetDirs.global] / [SnippetDirs.project]. A move or rename event reports the file's *new*
 * (post-event) location, so if the snippet directory itself is the thing being renamed or moved --
 * `.typewriter` becoming `.typewriter-old`, say -- resolving the *configured* directory after the
 * event has already happened finds nothing there any more, and a scope check built on that
 * resolution would silently drop the very event that needs to trigger a resync. Comparing against
 * the configured path string sidesteps that: it doesn't need the directory to still exist to
 * recognise that the event happened at (or above, or below) that location.
 */
class SnippetWatcher : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val basePaths = configuredBasePaths()
        if (events.none { isRelevant(it, basePaths) }) return
        // Deferred rather than run inline: `after` fires from inside VFS event dispatch, and
        // SnippetSync.syncAll re-walks the VFS (SnippetLibrary.collect) to compute the new set --
        // safer to let that dispatch finish first.
        ApplicationManager.getApplication().invokeLater { SnippetSync.syncAll() }
    }

    private fun configuredBasePaths(): List<String> =
        listOf(SnippetDirs.globalPath()) +
            ProjectManager.getInstance().openProjects.mapNotNull { SnippetDirs.projectPath(it) }

    /**
     * True for structural events (create/delete/copy: only ever one path; move/rename: both the
     * before and after path, since either end can matter -- a snippet moved *out* of a directory
     * changes that directory's set exactly as much as one moved in) whose relevant path(s)
     * [overlaps] a configured base path. False for every other event, including content edits.
     */
    private fun isRelevant(event: VFileEvent, basePaths: List<String>): Boolean = when (event) {
        is VFileCreateEvent -> overlapsAny(event.path, basePaths)
        is VFileDeleteEvent -> overlapsAny(event.path, basePaths)
        is VFileCopyEvent -> overlapsAny(event.path, basePaths)
        is VFileMoveEvent -> overlapsAny(event.oldPath, basePaths) || overlapsAny(event.newPath, basePaths)
        is VFilePropertyChangeEvent ->
            event.isRename && (overlapsAny(event.oldPath, basePaths) || overlapsAny(event.newPath, basePaths))
        else -> false
    }

    private fun overlapsAny(path: String, basePaths: List<String>): Boolean = basePaths.any { overlaps(path, it) }

    /**
     * True if [path] and [base] lie on the same ancestor chain in either direction: [path] under
     * [base] (the ordinary case -- a snippet file inside the configured directory) or [base] under
     * or equal to [path] (the configured directory itself, or an ancestor of it, is what the event
     * touched).
     */
    private fun overlaps(path: String, base: String): Boolean =
        FileUtil.isAncestor(path, base, false) || FileUtil.isAncestor(base, path, false)
}
