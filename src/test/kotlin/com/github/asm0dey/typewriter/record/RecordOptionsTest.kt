package com.github.asm0dey.typewriter.record

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

class RecordOptionsTest {
    @Test
    fun testOutputInConfiguredDir() {
        assertEquals(Path.of("/v/01-entity.mp4"), defaultOutput("/v", "01-entity"))
    }

    @Test
    fun testBlankDirMeansVideoDir() {
        assertEquals(defaultVideoDir(), defaultOutput("", "x").parent)
    }

    @Test
    fun testVideoDirExists() {
        assertTrue(Files.isDirectory(defaultVideoDir()))
    }

    @Test
    fun testNotificationHtmlKeepsLinesAndEscapes() {
        assertEquals("recording failed:<br>a &lt;b&gt;<br>c &amp; d", html("recording failed:\na <b>\nc & d"))
    }

    private class AbortCounter : FrameSink {
        var aborts = 0
        override suspend fun frame(bytes: ByteArray) = Unit
        override suspend fun finish() = Unit
        override fun abort() {
            aborts++
        }
    }

    @Test
    fun testRecordingOutcomes() = runBlocking {
        val ok = AbortCounter()
        assertNull(runRecording(ok) {})
        assertEquals(0, ok.aborts)

        val ffmpeg = AbortCounter()
        assertEquals("recording failed:\ntail", runRecording(ffmpeg) { throw FfmpegFailed("tail") })
        assertEquals(1, ffmpeg.aborts)

        val other = AbortCounter()
        assertEquals("recording failed: boom", runRecording(other) { throw IllegalStateException("boom") })
        assertEquals(1, other.aborts)

        val cancelled = AbortCounter()
        assertThrows(CancellationException::class.java) { runBlocking { runRecording(cancelled) { throw CancellationException() } } }
        assertEquals(1, cancelled.aborts)

        val error = AbortCounter()
        assertThrows(StackOverflowError::class.java) { runBlocking { runRecording(error) { throw StackOverflowError() } } }
        assertEquals(1, error.aborts)
    }
}
