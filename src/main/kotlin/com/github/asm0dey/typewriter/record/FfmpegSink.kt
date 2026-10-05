package com.github.asm0dey.typewriter.record

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

class FfmpegFailed(val stderrTail: String) : Exception(stderrTail)

/**
 * Pipes frames into ffmpeg writing `<name>.part.<ext>`, and renames it to the output on [finish].
 * The process starts on the first [frame] or [finish]. A blocked pipe write suspends [frame] on IO,
 * which is the back-pressure and keeps the EDT free.
 */
class FfmpegSink(private val ffmpeg: Path, private val options: RecordOptions) : FrameSink {
    private val output = options.output
    private val part = output.resolveSibling("${output.nameWithoutExtension}.part.${output.extension}")
    private val tail = ConcurrentLinkedDeque<String>()
    @Volatile private var process: Process? = null
    @Volatile private var aborted = false
    private var drainer: Thread? = null
    private var frames = 0

    @Synchronized
    private fun start(): Process = process ?: run {
        if (aborted) throw CancellationException("recording aborted")
        val format = requireNotNull(VideoFormat.of(output)) { "unsupported output ${output.fileName}" }
        val p = ProcessBuilder(ffmpegArgs(ffmpeg, options.width, options.height, options.fps, format, part))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        // Drain stderr so a full pipe can't stall ffmpeg; keep the last 20 lines for the error message.
        drainer = Thread {
            try {
                p.errorStream.bufferedReader().forEachLine { line ->
                    tail.addLast(line)
                    if (tail.size > 20) tail.pollFirst()
                }
            } catch (_: IOException) {
                // abort() destroyed the process and closed the stream under us
            }
        }.apply { isDaemon = true; start() }
        process = p
        p
    }

    override suspend fun frame(bytes: ByteArray) {
        val p = start()
        try {
            withContext(Dispatchers.IO) { p.outputStream.write(bytes) }
            frames++
        } catch (_: IOException) {
            // A cancel or abort kills ffmpeg under the write; that is not an encoding failure.
            currentCoroutineContext().ensureActive()
            if (aborted) throw CancellationException("recording aborted")
            // Otherwise ffmpeg exited early (e.g. the output directory is missing): its stderr says why.
            throw failure(p)
        }
    }

    override suspend fun finish() {
        val p = start()
        withContext(Dispatchers.IO) {
            try {
                try {
                    p.outputStream.close()
                } catch (_: IOException) {
                    // exit code below tells the story
                }
                // Interruptible, so cancelling the coroutine stops the wait instead of letting the move run.
                val exit = runInterruptible { p.waitFor() }
                drainer?.join()
                if (aborted) throw CancellationException("recording aborted")
                ensureActive()
                if (exit != 0 || frames == 0) throw failure(p, wait = false)
                Files.move(part, output, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: CancellationException) {
                abort()
                throw e
            }
        }
    }

    @Synchronized
    override fun abort() {
        aborted = true
        process?.destroyForcibly()
        Files.deleteIfExists(part)
    }

    private fun failure(p: Process, wait: Boolean = true): FfmpegFailed {
        if (wait) {
            p.waitFor()
            drainer?.join()
        }
        Files.deleteIfExists(part)
        val text = tail.joinToString("\n").ifBlank { "ffmpeg produced no output (no frames?)" }
        return FfmpegFailed(text)
    }
}
