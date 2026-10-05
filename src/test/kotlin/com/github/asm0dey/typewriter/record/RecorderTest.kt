package com.github.asm0dey.typewriter.record

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.model.Timing
import com.intellij.ide.DataManager
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.nio.file.Path

/**
 * Deliberately NOT `@RunInEdt`, like RunServiceTest: production runs [record] under
 * `Dispatchers.EDT`, where its `yield()` lets queued EDT events (the gutter's width update) run
 * before a frame is painted. `runBlocking` on the EDT would not pump that queue on `yield()`, so
 * these tests drive [record] on `Dispatchers.EDT` from a background thread and touch the fixture
 * through [edt].
 */
class RecorderTest : TypeWriterFixtureTestCase() {

    /** Collects frames; [onFrame] runs right after each paint, while the stage still shows that frame. */
    private class ListSink(val onFrame: () -> Unit = {}) : FrameSink {
        val frames = mutableListOf<ByteArray>()
        var finished = false
        override suspend fun frame(bytes: ByteArray) {
            frames += bytes
            onFrame()
        }
        override suspend fun finish() {
            finished = true
        }
        override fun abort() = Unit
    }

    private fun <T> edt(block: () -> T): T {
        var result: Result<T>? = null
        ApplicationManager.getApplication().invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun options(width: Int = 1280, height: Int = 720, fps: Int = 60, before: Int = 0, after: Int = 0) =
        RecordOptions(Path.of("out.mp4"), width, height, 14, fps, before, after)

    /** Builds a stage over the fixture editor, records into it, then hands it to [check] before disposing it. */
    private fun recordStage(
        steps: List<Step>, timing: Timing = Timing(0, 0, 0), options: RecordOptions = options(),
        sink: ListSink = ListSink(), prepare: (RecordingStage) -> Unit = {}, check: (RecordingStage) -> Unit = {},
    ) {
        val parent = Disposer.newDisposable()
        try {
            val stage = edt { RecordingStage(fixture.project, fixture.editor, options.width, options.height, options.fontSize, parent) }
            edt { prepare(stage) }
            runBlocking { withContext(Dispatchers.EDT) { record(fixture.project, stage, steps, timing, options, sink) } }
            edt { check(stage) }
        } finally {
            edt { Disposer.dispose(parent) }
        }
    }

    @Test
    fun testFrameCountMatchesVirtualTime() {
        edt { fixture.configureByText("A.java", "<caret>") }
        val sink = ListSink()
        recordStage(listOf(Step.Type("abc")), Timing(100, 0, 0), options(before = 1000, after = 2000), sink) { stage ->
            assertEquals(198, sink.frames.size)
            assertTrue(sink.frames.all { it.size == 1280 * 720 * 3 })
            assertTrue(sink.finished)
            val editor = stage.editor
            assertEquals("abc", editor.document.text.substring(0, editor.caretModel.offset).takeLast(3))
            assertEquals("", fixture.editor.document.text)
        }
    }

    @Test
    fun testStartsFromUnsavedDocumentText() {
        edt {
            fixture.configureByText("A.java", "class A {<caret>}")
            WriteCommandAction.runWriteCommandAction(fixture.project) { fixture.editor.document.insertString(0, "// edited\n") }
        }
        recordStage(listOf(Step.Type("x"))) { stage ->
            assertEquals("// edited\nclass A {x}", stage.editor.document.text)
        }
    }

    @Test
    fun testLongSnippetScrolls() {
        edt { fixture.configureByText("Long.java", (0 until 10).joinToString("\n") { "// line $it" } + "\n<caret>") }
        var before = 0
        lateinit var stage: RecordingStage
        var gutterAtFrame = Dimension()
        var contentHeightAtFrame = 0
        val sink = ListSink {
            gutterAtFrame = stage.editor.gutterComponentEx.size
            contentHeightAtFrame = stage.editor.contentComponent.height
        }
        // 10 + 100 lines: the line numbers grow to three digits, so the gutter must widen mid-recording.
        recordStage(
            listOf(Step.Type("line\n".repeat(100))), Timing(1, 0, 0), sink = sink,
            prepare = { stage = it; before = it.editor.scrollingModel.visibleArea.y },
        ) {
            val editor = stage.editor
            val area = editor.scrollingModel.visibleArea
            assertTrue(area.y > before, "visible area $area did not scroll from y=$before")
            val caretY = editor.visualPositionToXY(editor.caretModel.visualPosition).y
            assertTrue(caretY >= area.y && caretY + editor.lineHeight <= area.y + area.height, "caretY=$caretY area=$area")
            assertTrue(editor.document.lineCount > 100)
            assertEquals(contentHeightAtFrame, gutterAtFrame.height)
            // The gutter's width update is queued on the EDT. By now it has run, so a fresh layout gives
            // the right width; the last frame must already have had it, which only record's yield() ensures.
            stage.paint(BufferedImage(1280, 720, BufferedImage.TYPE_3BYTE_BGR))
            assertEquals(editor.contentComponent.height, editor.gutterComponentEx.height)
            assertEquals(editor.gutterComponentEx.width, gutterAtFrame.width, "last frame painted a stale gutter width")
        }
    }

    @Test
    fun testReformatRunsOnTheCopy() {
        edt { fixture.configureByText("A.java", "class A{void f(){}}<caret>") }
        // The fixture's test DataProvider answers EDITOR/PSI_FILE with the fixture editor for every
        // component, so ReformatCode would hit the source. Drop it and use the production,
        // component-based DataManager for this test, as the IDE does; both revert on dispose.
        val testDisposable = Disposer.newDisposable()
        try {
            HeadlessDataManager.fallbackToProductionDataManager(testDisposable)
            (DataManager.getInstance() as HeadlessDataManager).setTestDataProvider(null, testDisposable)
            recordStage(listOf(Step.Action("ReformatCode"))) { stage ->
                assertTrue(stage.editor.document.text.startsWith("class A {"), stage.editor.document.text)
                assertEquals("class A{void f(){}}", fixture.editor.document.text)
            }
        } finally {
            edt { Disposer.dispose(testDisposable) }
        }
    }

    @Test
    fun testFirstAndLastFramesDiffer() {
        edt { fixture.configureByText("A.java", "<caret>") }
        val sink = ListSink()
        recordStage(listOf(Step.Type("hello")), Timing(100, 0, 0), sink = sink)
        assertFalse(sink.frames.first().contentEquals(sink.frames.last()))
    }
}
