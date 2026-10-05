package com.github.asm0dey.typewriter.record

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private var process: Process? = null
    private var drainer: Thread? = null
    private var frames = 0

    private fun start(): Process = process ?: run {
        val format = requireNotNull(VideoFormat.of(output)) { "unsupported output ${output.fileName}" }
        val p = ProcessBuilder(ffmpegArgs(ffmpeg, options.width, options.height, options.fps, format, part))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        // Drain stderr so a full pipe can't stall ffmpeg; keep the last 20 lines for the error message.
        drainer = Thread {
            p.errorStream.bufferedReader().forEachLine { line ->
                tail.addLast(line)
                if (tail.size > 20) tail.pollFirst()
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
            // ffmpeg exited early (e.g. the output directory is missing): its stderr says why.
            throw failure(p)
        }
    }

    override suspend fun finish() {
        val p = start()
        withContext(Dispatchers.IO) {
            try {
                p.outputStream.close()
            } catch (_: IOException) {
                // exit code below tells the story
            }
            val exit = p.waitFor()
            drainer?.join()
            if (exit != 0 || frames == 0) throw failure(p, wait = false)
            Files.move(part, output, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun abort() {
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
