package com.github.asm0dey.typewriter.library

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.github.asm0dey.typewriter.ui.TypeWriterProjectSettings
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.RunInEdt
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Exercises the real wiring: [SnippetWatcher] is only useful if the platform actually delivers VFS
 * events to it (the `applicationListeners` entry in plugin.xml) and [SnippetStartupActivity] is
 * only useful if it is actually run for an opening project. Every test here triggers behaviour
 * through that real path -- a genuine VFS write, or the activity's own `execute` -- and never calls
 * [SnippetRegistrar.register] or [SnippetSync.syncAll] directly to produce the assertion outcome,
 * because that would only re-prove what [SnippetRegistrarTest] (Task 12) already proved.
 */
@RunInEdt(writeIntent = true)
class SnippetWatcherTest : TypeWriterFixtureTestCase() {

    private val savedProjectDirs = mutableListOf<String>()
    private val tempDirs = mutableListOf<Path>()

    @AfterEach
    fun cleanup() {
        val settings = fixture.project.getService(TypeWriterProjectSettings::class.java)
        savedProjectDirs.lastOrNull()?.let { settings.loadState(TypeWriterProjectSettings.State(projectDir = it)) }
        // Full reset: SnippetRegistrar.register(emptyList()) unregisters everything currently
        // tracked, matching the convention SnippetRegistrarTest already established.
        SnippetRegistrar.register(emptyList())
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    /** Points the fixture project's snippet dir at a real, VFS-refreshed directory on disk. */
    private fun pointProjectAt(dir: Path): VirtualFile {
        val settings = fixture.project.getService(TypeWriterProjectSettings::class.java)
        savedProjectDirs.add(settings.state.projectDir)
        settings.loadState(TypeWriterProjectSettings.State(projectDir = dir.toString()))
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!
    }

    private fun pump() = PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

    // Proves the listener itself fires: nothing in this test calls SnippetRegistrar.register or
    // SnippetSync.syncAll directly. A real VFS create (WriteAction.createChildData) publishes a
    // VFileCreateEvent through the application message bus; plugin.xml's <applicationListeners>
    // entry must deliver it to SnippetWatcher, which must schedule SnippetSync.syncAll, for the
    // action to ever appear. If the listener were never wired up, this would time out at "not
    // registered" instead.
    @Test
    fun testCreatingASnippetFileThroughRealVfsRegistersItsActionViaTheListener() {
        val realDir = Files.createTempDirectory("tw-watch-create")
        tempDirs.add(realDir)
        val vDir = pointProjectAt(realDir)

        WriteAction.run<Exception> {
            vDir.createChildData(this, "01.java").setBinaryContent("// a".toByteArray())
        }
        pump()

        assertNotNull(
            ActionManager.getInstance().getAction(SnippetLibrary.actionId("01.java")),
            "creating a snippet file through the real VFS should register its action via SnippetWatcher",
        )
    }

    // Same real-path requirement as above, for the unregister direction.
    @Test
    fun testDeletingASnippetFileUnregistersItsActionViaTheListener() {
        val realDir = Files.createTempDirectory("tw-watch-delete")
        tempDirs.add(realDir)
        val vDir = pointProjectAt(realDir)

        val file = WriteAction.compute<VirtualFile, Exception> {
            vDir.createChildData(this, "gone.java").also { it.setBinaryContent("// x".toByteArray()) }
        }
        pump()
        assertNotNull(ActionManager.getInstance().getAction(SnippetLibrary.actionId("gone.java")))

        WriteAction.run<Exception> { file.delete(this) }
        pump()

        assertNull(
            ActionManager.getInstance().getAction(SnippetLibrary.actionId("gone.java")),
            "deleting a snippet file through the real VFS should unregister its action via SnippetWatcher",
        )
    }

    // Proves SnippetStartupActivity itself does the registering (not merely that SnippetRegistrar
    // works, which Task 12 already covers): files exist on disk before execute() runs, and the
    // only call in this test is to the activity's own execute -- never to register/syncAll.
    @Test
    fun testStartupActivityRegistersSnippetsAlreadyOnDiskForTheProject() = runBlocking {
        val realDir = Files.createTempDirectory("tw-watch-startup")
        tempDirs.add(realDir)
        Files.writeString(realDir.resolve("boot.java"), "// boot")
        pointProjectAt(realDir)

        SnippetStartupActivity().execute(fixture.project)

        assertNotNull(ActionManager.getInstance().getAction(SnippetLibrary.actionId("boot.java")))
    }

    // Scope filter, strengthened after review: the project IS pointed at a real, configured
    // snippet directory (so SnippetWatcher's base-path list is genuinely non-empty when the event
    // fires -- an earlier version of this test left the project on its unconfigured default,
    // which made SnippetWatcher's now-removed `dirs.isEmpty()` short-circuit return before the
    // scope check ever ran, passing for the wrong reason), and the irrelevant file is created in a
    // *second, separate* real directory that overlaps no configured base path. A phantom id is
    // seeded directly (setup, not the behaviour under test) so that IF a resync ran it would be
    // removed -- "ghost.java" is not a real file anywhere, so any real collect() call drops it. It
    // surviving the unrelated VFS event proves no resync happened.
    @Test
    fun testEventsOutsideAnySnippetDirectoryDoNotTriggerAResync() {
        val realDir = Files.createTempDirectory("tw-watch-scoped")
        tempDirs.add(realDir)
        pointProjectAt(realDir)

        val unrelated = Files.createTempDirectory("tw-watch-unrelated")
        tempDirs.add(unrelated)
        val vUnrelated = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(unrelated)!!

        SnippetRegistrar.register(listOf("ghost.java"))
        WriteAction.run<Exception> {
            vUnrelated.createChildData(this, "irrelevant.txt").setBinaryContent("// noise".toByteArray())
        }
        pump()

        assertNotNull(
            ActionManager.getInstance().getAction(SnippetLibrary.actionId("ghost.java")),
            "an event outside every configured snippet directory must not trigger SnippetSync.syncAll",
        )
    }

    // Event-kind filter: a content-only edit to an existing, in-scope snippet file must not
    // trigger a resync -- the action already exists and SnippetLibrary.textOf reads the live
    // Document, so re-registering buys nothing. Same phantom-survival technique as above, this
    // time with the edited file itself inside the configured project directory.
    @Test
    fun testContentOnlyEditsDoNotTriggerAResync() {
        val realDir = Files.createTempDirectory("tw-watch-content")
        tempDirs.add(realDir)
        val vDir = pointProjectAt(realDir)
        val file = WriteAction.compute<VirtualFile, Exception> {
            vDir.createChildData(this, "a.java").also { it.setBinaryContent("// original".toByteArray()) }
        }
        pump()

        SnippetRegistrar.register(listOf("a.java", "ghost.java"))

        WriteAction.run<Exception> { file.setBinaryContent("// edited".toByteArray()) }
        pump()

        assertNotNull(
            ActionManager.getInstance().getAction(SnippetLibrary.actionId("ghost.java")),
            "a content-only edit inside a snippet directory must not trigger SnippetSync.syncAll",
        )
    }

    // Covers the VFileMoveEvent branch specifically: a file moved INTO the configured directory
    // from an unrelated one must register its action, proving isRelevant's move branch checks the
    // event's new path (not just create/delete paths).
    @Test
    fun testMovingAFileIntoASnippetDirectoryRegistersItsActionViaTheListener() {
        val configured = Files.createTempDirectory("tw-watch-move-dest")
        tempDirs.add(configured)
        val vConfigured = pointProjectAt(configured)

        val elsewhere = Files.createTempDirectory("tw-watch-move-src")
        tempDirs.add(elsewhere)
        val vElsewhere = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(elsewhere)!!
        val file = WriteAction.compute<VirtualFile, Exception> {
            vElsewhere.createChildData(this, "moved.java").also { it.setBinaryContent("// m".toByteArray()) }
        }

        WriteAction.run<Exception> { file.move(this, vConfigured) }
        pump()

        assertNotNull(
            ActionManager.getInstance().getAction(SnippetLibrary.actionId("moved.java")),
            "moving a file into a configured snippet directory should register its action via SnippetWatcher",
        )
    }

    // Covers the VFileCopyEvent branch specifically: a file copied INTO the configured directory
    // must register its action for the copy's destination path.
    @Test
    fun testCopyingAFileIntoASnippetDirectoryRegistersItsActionViaTheListener() {
        val configured = Files.createTempDirectory("tw-watch-copy-dest")
        tempDirs.add(configured)
        val vConfigured = pointProjectAt(configured)

        val elsewhere = Files.createTempDirectory("tw-watch-copy-src")
        tempDirs.add(elsewhere)
        val vElsewhere = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(elsewhere)!!
        val file = WriteAction.compute<VirtualFile, Exception> {
            vElsewhere.createChildData(this, "source.java").also { it.setBinaryContent("// s".toByteArray()) }
        }

        WriteAction.run<Exception> { file.copy(this, vConfigured, "copied.java") }
        pump()

        assertNotNull(
            ActionManager.getInstance().getAction(SnippetLibrary.actionId("copied.java")),
            "copying a file into a configured snippet directory should register its action via SnippetWatcher",
        )
    }

    // Covers the VFilePropertyChangeEvent/isRename branch specifically: renaming a snippet file in
    // place must drop the old id and register the new one.
    @Test
    fun testRenamingASnippetFileSwapsItsRegisteredAction() {
        val realDir = Files.createTempDirectory("tw-watch-rename")
        tempDirs.add(realDir)
        val vDir = pointProjectAt(realDir)
        val file = WriteAction.compute<VirtualFile, Exception> {
            vDir.createChildData(this, "old.java").also { it.setBinaryContent("// r".toByteArray()) }
        }
        pump()
        assertNotNull(ActionManager.getInstance().getAction(SnippetLibrary.actionId("old.java")))

        WriteAction.run<Exception> { file.rename(this, "new.java") }
        pump()

        assertNull(
            ActionManager.getInstance().getAction(SnippetLibrary.actionId("old.java")),
            "renaming a snippet file should unregister its old id via SnippetWatcher",
        )
        assertNotNull(
            ActionManager.getInstance().getAction(SnippetLibrary.actionId("new.java")),
            "renaming a snippet file should register its new id via SnippetWatcher",
        )
    }

    // Regression test for the review finding: renaming/moving the CONFIGURED DIRECTORY ITSELF
    // (settings left pointing at the now-stale relative path, exactly as if a user renamed
    // ".typewriter" on disk without updating TypeWriter's settings) must still unregister the
    // snippets that used to live there. Before the fix, SnippetWatcher computed its scope from
    // SnippetDirs.project(project) -- a VirtualFile resolved fresh AFTER the event, by the
    // configured relative path -- which finds nothing once the directory has moved away, so the
    // rename event was silently dropped and "01.java"'s action stayed registered forever. The fix
    // compares the event's OLD path (which a rename/move event reports precisely) against the
    // *configured path string* instead, which needs no live resolution to still equal the
    // directory's former location.
    @Test
    fun testRenamingTheConfiguredDirectoryItselfUnregistersItsStaleActions() = runBlocking {
        val realDir = Files.createTempDirectory("tw-watch-dir-rename")
        tempDirs.add(realDir)
        val vDir = pointProjectAt(realDir)
        WriteAction.run<Exception> {
            vDir.createChildData(this, "01.java").also { it.setBinaryContent("// one".toByteArray()) }
        }
        pump()
        assertNotNull(
            ActionManager.getInstance().getAction(SnippetLibrary.actionId("01.java")),
            "setup: the snippet must be registered before the directory is renamed away",
        )

        // Settings are deliberately left pointing at realDir's old (now stale) path -- this is the
        // exact scenario the finding describes, not an artificially different one. The new name is
        // derived from realDir's own (already-unique, random-suffixed) name rather than a fixed
        // literal: a fixed literal here collides across runs, because deleteRecursively() in
        // cleanup() deletes via raw java.io (not through the VFS), which can leave a stale VFS
        // record behind at that exact path for a later run to trip over.
        val movedAwayName = "${realDir.fileName}-moved-away"
        tempDirs.add(realDir.resolveSibling(movedAwayName)) // cleanup after rename
        WriteAction.run<Exception> { vDir.rename(this, movedAwayName) }
        pump()

        assertNull(
            ActionManager.getInstance().getAction(SnippetLibrary.actionId("01.java")),
            "renaming the configured snippet directory away from its settings path should " +
                "unregister the stale actions that used to live there",
        )
    }
}
