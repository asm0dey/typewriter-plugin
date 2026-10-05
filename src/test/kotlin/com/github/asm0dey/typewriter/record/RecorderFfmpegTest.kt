package com.github.asm0dey.typewriter.record

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.model.Timing
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries

/** Real ffmpeg, real files. Driven like RecorderTest: [record] on Dispatchers.EDT from the test thread, no @RunInEdt. */
class RecorderFfmpegTest : TypeWriterFixtureTestCase() {

    /** Missing tool: fail on CI (it installs ffmpeg), skip on a developer machine. */
    private fun missing(what: String): Nothing {
        if (System.getenv("CI") != null) fail<Unit>("$what is required on CI")
        Assumptions.assumeTrue(false, "$what not installed")
        error("unreachable")
    }

    private fun requireFfmpeg(): Path = (locateFfmpeg("ffmpeg") as? FfmpegStatus.Found)?.path ?: missing("ffmpeg")

    private fun requireFfprobe(): Path = PathEnvironmentVariableUtil.findInPath("ffprobe")?.toPath() ?: missing("ffprobe")

    private fun <T> edt(block: () -> T): T {
        var result: Result<T>? = null
        ApplicationManager.getApplication().invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun options(output: Path) = RecordOptions(output, 640, 360, 14, 30, 500, 500)

    private fun recordTo(output: Path, frames: IntArray) {
        val options = options(output)
        val sink = FfmpegSink(requireFfmpeg(), options)
        val counting = object : FrameSink by sink {
            override suspend fun frame(bytes: ByteArray) {
                frames[0]++
                sink.frame(bytes)
            }
        }
        val parent = Disposer.newDisposable()
        try {
            val stage = edt { RecordingStage(fixture.project, fixture.editor, options.width, options.height, options.fontSize, parent) }
            try {
                runBlocking {
                    withContext(Dispatchers.EDT) {
                        record(fixture.project, stage, listOf(Step.Type("a\nb\nc")), Timing(50, 0, 0), options, counting)
                    }
                }
            } catch (e: Throwable) {
                sink.abort()
                throw e
            }
        } finally {
            edt { Disposer.dispose(parent) }
        }
    }

    private fun probe(file: Path): Map<String, String> {
        val process = ProcessBuilder(
            requireFfprobe().toString(), "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=width,height:format=duration", "-of", "default=nw=1", file.toString(),
        ).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), text)
        return text.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    @Test
    fun testMp4AndGifHaveRightSizeAndDuration() {
        edt { fixture.configureByText("A.java", "<caret>") }
        val dir = Files.createTempDirectory("tw-rec").resolve("my videos").createDirectories()
        for (name in listOf("demo.mp4", "demo.gif")) {
            val frames = intArrayOf(0)
            val out = dir.resolve(name)
            recordTo(out, frames)
            val info = probe(out)
            assertEquals("640", info["width"], name)
            assertEquals("360", info["height"], name)
            // GIF is capped at 50 fps and 30 is below it, so both formats keep every frame.
            assertEquals(frames[0] / 30.0, info.getValue("duration").toDouble(), 0.1, name)
        }
        assertEquals(listOf("demo.gif", "demo.mp4"), dir.listDirectoryEntries().map { it.fileName.toString() }.sorted())
    }

    @Test
    fun testMissingOutputDirectoryFails() {
        edt { fixture.configureByText("A.java", "<caret>") }
        val root = Files.createTempDirectory("tw-rec")
        val out = root.resolve("nope").resolve("demo.mp4")
        val e = assertThrows(FfmpegFailed::class.java) { recordTo(out, intArrayOf(0)) }
        assertTrue(e.stderrTail.isNotBlank())
        assertFalse(root.resolve("nope").exists())
        assertTrue(root.listDirectoryEntries().isEmpty())
    }

    @Test
    fun testNothingToEncodeFails() {
        val dir = Files.createTempDirectory("tw-rec")
        val sink = FfmpegSink(requireFfmpeg(), options(dir.resolve("demo.mp4")))
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            assertThrows(FfmpegFailed::class.java) { runBlocking { sink.finish() } }
        }
        assertTrue(dir.listDirectoryEntries().isEmpty())
    }

    @Test
    fun testAbortKillsProcessAndDeletesPart() {
        val dir = Files.createTempDirectory("tw-rec")
        val out = dir.resolve("demo.mp4")
        val sink = FfmpegSink(requireFfmpeg(), options(out))
        val frame = ByteArray(640 * 360 * 3)
        runBlocking { repeat(5) { sink.frame(frame) } }
        sink.abort()
        sink.abort()
        assertTrue(dir.listDirectoryEntries().isEmpty())
        assertFalse(out.exists())
    }
}
