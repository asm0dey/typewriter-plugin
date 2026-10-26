package com.github.asm0dey.typewriter.record

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.github.asm0dey.typewriter.library.SnippetLibrary
import com.github.asm0dey.typewriter.model.Snippet
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Not `@RunInEdt`, like [RecorderTest]: [recordBatch] hops onto `Dispatchers.EDT` itself. */
class BatchRecorderTest : TypeWriterFixtureTestCase() {

    /** Collects frames; on finish, remembers the text of the stage editor being recorded. */
    private class ListSink : FrameSink {
        var frames = 0
        var aborted = false
        var finalText: String? = null
        override suspend fun frame(bytes: ByteArray) {
            frames++
        }
        override suspend fun finish() {
            finalText = EditorFactory.getInstance().allEditors
                .single { it.getUserData(RecordingStage.STAGE) == true }.document.text
        }
        override fun abort() {
            aborted = true
        }
    }

    private class FailingSink : FrameSink {
        override suspend fun frame(bytes: ByteArray) = Unit
        override suspend fun finish() = throw FfmpegFailed("boom")
        override fun abort() = Unit
    }

    private fun <T> edt(block: () -> T): T {
        var result: Result<T>? = null
        ApplicationManager.getApplication().invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun snippet(name: String, text: String): Snippet = edt {
        val dir = WriteAction.compute<VirtualFile, Exception> { fixture.tempDirFixture.findOrCreateDir("batch") }
        val vf = WriteAction.compute<VirtualFile, Exception> {
            (dir.findChild(name) ?: dir.createChildData(this, name)).also { it.setBinaryContent(text.toByteArray()) }
        }
        Snippet(SnippetLibrary.actionId(name), name, vf, JavaFileType.INSTANCE, true)
    }

    private val out: Path = Files.createTempDirectory("batch")

    private fun options(before: Int = 0, after: Int = 0) = RecordOptions(out, 320, 180, 14, 10, before, after)

    private fun run(
        items: List<BatchItem>, options: RecordOptions = options(), sinkFor: (RecordOptions) -> FrameSink,
    ): List<BatchOutcome> {
        val outcomes = mutableListOf<BatchOutcome>()
        runBlocking { recordBatch(fixture.project, items, options, sinkFor, onOutcome = { outcomes += it }) }
        return outcomes
    }

    @Test
    fun `records every snippet into an empty file of the cleaned name`() {
        val items = listOf(
            BatchItem(snippet("01-a.java", "class A {}"), out.resolve("java/01-a.mp4")),
            BatchItem(snippet("02-b.java", "class B {}"), out.resolve("java/02-b.mp4")),
        )
        val sinks = mutableListOf<ListSink>()
        val outcomes = run(items) { ListSink().also { sinks += it } }
        assertEquals(items, outcomes.map { it.item })
        outcomes.forEach { assertInstanceOf(BatchOutcome.Recorded::class.java, it) }
        assertEquals(listOf("class A {}", "class B {}"), sinks.map { it.finalText?.trim() })
        assertTrue(Files.isDirectory(out.resolve("java")))
    }

    @Test
    fun `a blocked snippet fails and the rest still record`() {
        val unreadable = edt {
            val dir = WriteAction.compute<VirtualFile, Exception> { fixture.tempDirFixture.findOrCreateDir("not-a-file.java") }
            Snippet("x", "not-a-file.java", dir, JavaFileType.INSTANCE, true)
        }
        val items = listOf(
            BatchItem(unreadable, out.resolve("not-a-file.mp4")),
            BatchItem(snippet("02-b.java", "class B {}"), out.resolve("02-b.mp4")),
        )
        val outcomes = run(items) { ListSink() }
        val failed = assertInstanceOf(BatchOutcome.Failed::class.java, outcomes[0])
        assertTrue("no readable text" in failed.reason, failed.reason)
        assertInstanceOf(BatchOutcome.Recorded::class.java, outcomes[1])
    }

    @Test
    fun `a binary file among the snippets fails alone`() {
        val binary = snippet(".DS_Store", "\u0000\u0001").copy(fileType = UnknownFileType.INSTANCE)
        val items = listOf(
            BatchItem(binary, out.resolve(".DS_Store.mp4")),
            BatchItem(snippet("02-b.java", "class B {}"), out.resolve("02-b.mp4")),
        )
        val outcomes = run(items) { ListSink() }
        assertInstanceOf(BatchOutcome.Failed::class.java, outcomes[0])
        assertInstanceOf(BatchOutcome.Recorded::class.java, outcomes[1])
    }

    @Test
    fun `ffmpeg failure on one snippet does not stop the batch`() {
        val items = listOf(
            BatchItem(snippet("01-a.java", "class A {}"), out.resolve("01-a.mp4")),
            BatchItem(snippet("02-b.java", "class B {}"), out.resolve("02-b.mp4")),
        )
        var first = true
        val outcomes = run(items) { if (first) FailingSink().also { first = false } else ListSink() }
        val failed = assertInstanceOf(BatchOutcome.Failed::class.java, outcomes[0])
        assertTrue("boom" in failed.reason, failed.reason)
        assertInstanceOf(BatchOutcome.Recorded::class.java, outcomes[1])
    }

    @Test
    fun `a pause-only snippet still records the holds`() {
        val sink = ListSink()
        val outcomes = run(
            listOf(BatchItem(snippet("01-p.java", "// tw: pause 100\n"), out.resolve("01-p.mp4"))),
            options(before = 100, after = 100),
        ) { sink }
        assertInstanceOf(BatchOutcome.Recorded::class.java, outcomes.single())
        assertTrue(sink.frames > 0)
    }

    @Test
    fun `cancel keeps finished outcomes and aborts the current sink`() {
        val items = listOf(
            BatchItem(snippet("01-a.java", "class A {}"), out.resolve("01-a.mp4")),
            BatchItem(snippet("02-b.java", "class B {}"), out.resolve("02-b.mp4")),
        )
        val sinks = mutableListOf<ListSink>()
        val outcomes = mutableListOf<BatchOutcome>()
        lateinit var job: Job
        var thrown: Throwable? = null
        runBlocking {
            job = launch(Dispatchers.EDT) {
                try {
                    recordBatch(
                        fixture.project, items, options(after = 1000), { ListSink().also { sinks += it } },
                        onStart = { i, _ -> if (i == 1) job.cancel() },
                        onOutcome = { outcomes += it },
                    )
                } catch (e: CancellationException) {
                    thrown = e
                    throw e
                }
            }
            job.join()
        }
        assertEquals(1, outcomes.size)
        assertInstanceOf(BatchOutcome.Recorded::class.java, outcomes.single())
        assertTrue(thrown is CancellationException)
        assertTrue(sinks.size < 2 || sinks[1].aborted, "the interrupted sink must be aborted")
    }

    @Test
    fun `summary strings`() {
        val item = BatchItem(snippet("01-a.java", ""), out.resolve("01-a.mp4"))
        fun rec(warnings: List<String> = emptyList()) = BatchOutcome.Recorded(item, warnings)
        val failed = BatchOutcome.Failed(item, "x", emptyList())
        assertEquals("Recorded 2 videos", batchSummary(listOf(rec(), rec()), 2, false))
        assertEquals("Recorded 2 videos, 1 with warnings", batchSummary(listOf(rec(listOf("w")), rec()), 2, false))
        assertEquals("Recorded 1 of 2 videos", batchSummary(listOf(rec(), failed), 2, false))
        assertEquals("Cancelled after 1 of 3 videos", batchSummary(listOf(rec()), 3, true))
    }
}
