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

    // Scope filter: an event outside any configured snippet directory must not trigger a resync.
    // A phantom id is seeded directly (setup, not the behaviour under test) so that IF a resync ran
    // it would be removed -- "ghost.java" is not a real file anywhere, so any real collect() call
    // drops it. It surviving the unrelated VFS event proves no resync happened.
    @Test
    fun testEventsOutsideAnySnippetDirectoryDoNotTriggerAResync() {
        val unrelated = Files.createTempDirectory("tw-watch-unrelated")
        tempDirs.add(unrelated)
        val vDir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(unrelated)!!

        SnippetRegistrar.register(listOf("ghost.java"))
        WriteAction.run<Exception> {
            vDir.createChildData(this, "irrelevant.txt").setBinaryContent("// noise".toByteArray())
        }
        pump()

        assertNotNull(
            ActionManager.getInstance().getAction(SnippetLibrary.actionId("ghost.java")),
            "an event outside every snippet directory must not trigger SnippetSync.syncAll",
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
}
