package com.github.asm0dey.typewriter.library

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.github.asm0dey.typewriter.model.Directives
import com.github.asm0dey.typewriter.model.Snippet
import com.github.asm0dey.typewriter.run.RunService
import com.github.asm0dey.typewriter.ui.TypeWriterProjectSettings
import com.github.asm0dey.typewriter.ui.TypeWriterSettings
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.TestActionEvent
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Deliberately not `@RunInEdt`, for the same reason as [com.github.asm0dey.typewriter.run.RunServiceTest]:
 * [SnippetRunner.run] does synchronous EDT-only work (PSI creation, formatting under a write
 * action) and then hands off to [RunService.launch], a fire-and-forget entry point whose actual
 * typing happens in a coroutine dispatched onto `Dispatchers.EDT`. Waiting for that coroutine to
 * finish requires the physical EDT thread to stay free to pump its own queue, so this class's
 * test methods -- and [waitForIdle]'s polling loop -- run on a plain background thread and reach
 * for the EDT only through [onEdt], exactly like `RunServiceTest`. Calls that themselves need the
 * EDT ([SnippetRunner.run], `AnAction.actionPerformed`) are wrapped in [onEdt]; the wait for their
 * *asynchronous* continuation happens afterward, off the EDT.
 */
class SnippetRunnerTest : TypeWriterFixtureTestCase() {

    private fun <T> onEdt(block: () -> T): T {
        val box = arrayOfNulls<Any?>(1)
        ApplicationManager.getApplication().invokeAndWait { box[0] = block() }
        @Suppress("UNCHECKED_CAST")
        return box[0] as T
    }

    /** Brackets a run started via [RunService.launch]: waits for it to start, then to finish. */
    private fun waitForIdle(svc: RunService, timeoutMs: Long = 5000) = runBlocking {
        withTimeout(timeoutMs) { while (!svc.isRunning()) yield() }
        withTimeout(timeoutMs) { while (svc.isRunning()) yield() }
    }

    private fun configureTarget(text: String) = onEdt { fixture.configureByText("T.java", text) }

    private fun tempDir(name: String): VirtualFile = onEdt {
        WriteAction.compute<VirtualFile, Exception> { fixture.tempDirFixture.findOrCreateDir(name) }
    }

    private fun writeSnippetFile(dir: VirtualFile, name: String, text: String): VirtualFile = onEdt {
        WriteAction.compute<VirtualFile, Exception> {
            (dir.findChild(name) ?: dir.createChildData(this, name)).also {
                it.setBinaryContent(text.toByteArray())
            }
        }
    }

    private fun snippet(dir: VirtualFile, name: String, text: String, fileType: FileType = JavaFileType.INSTANCE): Snippet {
        val vf = writeSnippetFile(dir, name, text)
        return Snippet(SnippetLibrary.actionId(name), name, vf, fileType, true)
    }

    private fun appSettings() = ApplicationManager.getApplication().getService(TypeWriterSettings::class.java)

    /**
     * Zero timing so the async typing a test triggers settles almost instantly -- [com.github.asm0dey.typewriter.run.Player]'s
     * own per-character delay still floors at 1ms (see `RunServiceTest`), so this makes tests
     * fast, not flaky. Saves and restores the real, live [TypeWriterSettings] service instance,
     * since [SnippetRunner.run] resolves it through the application's own service registry.
     */
    private fun withZeroTiming(block: () -> Unit) {
        val settings = appSettings()
        val saved = settings.state
        settings.loadState(saved.copy(speedMs = 0, jitterMs = 0, newlineMs = 0))
        try {
            block()
        } finally {
            settings.loadState(saved)
        }
    }

    // Spec section 11 "Pre-flight": a blocking Check.Error must prevent the run outright --
    // SnippetRunner.run must return at PreFlight.check's blocked() gate rather than reaching
    // RunService.launch. A read-only target document is the easiest trigger (PreFlight.kt's own
    // first check). If the blocked() guard were ever removed or ignored, this run would attempt
    // to type into a read-only document and either throw or silently mutate it -- either way this
    // assertion would fail.
    @Test
    fun testABlockingPreFlightErrorPreventsTheRun() {
        configureTarget("<caret>")
        onEdt { fixture.editor.document.setReadOnly(true) }
        val before = fixture.editor.document.text
        val dir = tempDir("blocked")
        val s = snippet(dir, "01.java", "// x")
        val svc = fixture.project.getService(RunService::class.java)

        onEdt { SnippetRunner.run(fixture.project, fixture.editor, s) }

        assertEquals(before, fixture.editor.document.text)
        assertFalse(svc.isRunning())
    }

    // Spec section 8 "Sequence": Type Next types the snippet at the cursor and advances; both
    // ends clamp (controller-ratified, see task-12-report.md's fix-round section). Two snippets,
    // three invocations: the third invocation is past the end and must not throw or advance
    // beyond the last index.
    @Test
    fun testTypeNextAdvancesTheCursorAndClampsAtTheEndWithoutThrowing() {
        withZeroTiming {
            configureTarget("<caret>")

            // SnippetDirs.project resolves through LocalFileSystem.findFileByNioFile, which
            // cannot see fixture.tempDirFixture's in-memory temp:// files -- a real directory on
            // disk is required here, refreshed into the VFS so the VirtualFile the sequence walk
            // sees is backed by the same files.
            val realDir = Files.createTempDirectory("tw-seq")
            Files.writeString(realDir.resolve("01.java"), "// one")
            Files.writeString(realDir.resolve("02.java"), "// two")
            onEdt { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(realDir) }

            val projectSettings = fixture.project.getService(TypeWriterProjectSettings::class.java)
            val savedProjectDir = projectSettings.state.projectDir
            // An absolute path here makes Path.resolve(relative) in SnippetDirs.project return
            // this directory regardless of the light fixture project's own basePath.
            projectSettings.loadState(TypeWriterProjectSettings.State(projectDir = realDir.toString()))

            val svc = fixture.project.getService(RunService::class.java)
            svc.cursor = 0

            val dataContext = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, fixture.project)
                .add(CommonDataKeys.EDITOR, fixture.editor)
                .build()
            val event = TestActionEvent.createTestEvent(dataContext)
            val action = TypeNextAction()

            try {
                onEdt { action.actionPerformed(event) }
                waitForIdle(svc)
                assertEquals(1, svc.cursor, "after the first snippet, cursor should point at the second")

                onEdt { action.actionPerformed(event) }
                waitForIdle(svc)
                assertEquals(1, svc.cursor, "at the last snippet, cursor should clamp rather than advance past it")

                onEdt { action.actionPerformed(event) }
                waitForIdle(svc)
                assertEquals(1, svc.cursor, "a third invocation past the end must not throw and must stay clamped")
            } finally {
                projectSettings.loadState(TypeWriterProjectSettings.State(projectDir = savedProjectDir))
                realDir.toFile().deleteRecursively()
            }
        }
    }

    // Spec section 11 "Pre-flight" / section 10 "Settings": format-on-play must run BEFORE the
    // program is parsed into typed steps, not after -- otherwise the sloppily-indented source,
    // not the formatter's output, would be what gets typed. The target editor starts on an empty
    // line at column 0, so BaseIndent contributes no indentation of its own (empty indent, caret
    // column 0 -- see BaseIndent.apply), making the formatted text the *only* source of the
    // indentation actually typed.
    @Test
    fun testFormatOnPlayFormatsBeforeBuildingTheTypedSteps() {
        withZeroTiming {
            configureTarget("<caret>")
            val dir = tempDir("fmt")
            // Same fixture pair as SnippetFormatterTest.testFixesIndentation, whose exact output
            // is already pinned there -- reused here so this test is only proving that
            // SnippetRunner actually calls the formatter and types its result, not re-proving
            // what the formatter itself produces.
            // language="JAVA"
            val original = """
                |class A {
                |int x;
                |        void m() {
                |int y = 1;
                |}
                |}
            """.trimMargin()
            // language="JAVA"
            val formatted = """
                |class A {
                |    int x;
                |    void m() {
                |        int y = 1;
                |    }
                |}
            """.trimMargin()
            val s = snippet(dir, "01.java", original)
            val svc = fixture.project.getService(RunService::class.java)

            onEdt { SnippetRunner.run(fixture.project, fixture.editor, s) }
            waitForIdle(svc)

            assertEquals(formatted, fixture.editor.document.text)
        }
    }

    // Spec section 9, resolved design question 22: a comment-less snippet's timing lives in its
    // own .twmeta sidecar, since MarkerScanner returns no markers for such a snippet (no comment
    // syntax to recognise a PsiComment in) and program.directives is therefore always Directives().
    // Without SnippetRunner.run actually consulting DirectiveSidecar, playback would silently fall
    // back to the global default regardless of what the sidecar -- and the dialog -- say. Proven
    // here by making the two disagree sharply: the global default is slow (50ms/char), the sidecar
    // says instant (0ms) -- if the sidecar were not consulted, this run would still be typing well
    // past the short timeout below.
    @Test
    fun testSidecarTimingReachesThePlayerForACommentLessSnippet() {
        configureTarget("<caret>")
        val settings = appSettings()
        val savedGlobal = settings.state
        settings.loadState(savedGlobal.copy(speedMs = 50, jitterMs = 0, newlineMs = 0))
        try {
            val dir = tempDir("sidecar-timing")
            val text = "hello world, this is a longer line of plain text"
            val s = snippet(dir, "01.txt", text, PlainTextFileType.INSTANCE)
            onEdt { DirectiveSidecar.write(s.file, Directives(speedMs = 0, jitterMs = 0, newlineMs = 0)) }
            val svc = fixture.project.getService(RunService::class.java)

            onEdt { SnippetRunner.run(fixture.project, fixture.editor, s) }
            // At 50ms/char the ~50-character snippet above would take well over 2000ms if the
            // global default -- not the sidecar -- were what actually reached the player.
            waitForIdle(svc, timeoutMs = 500)

            assertEquals(text, fixture.editor.document.text)
        } finally {
            settings.loadState(savedGlobal)
        }
    }
}
