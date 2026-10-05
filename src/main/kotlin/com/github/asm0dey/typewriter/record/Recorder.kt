package com.github.asm0dey.typewriter.record

import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.model.Timing
import com.github.asm0dey.typewriter.run.Player
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.ThreadingAssertions
import kotlinx.coroutines.yield
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Where recorded frames go. [frame] and [finish] run on the EDT; [abort] may run anywhere. */
interface FrameSink {
    /** One frame: `width * height * 3` bytes, BGR. */
    suspend fun frame(bytes: ByteArray)

    /** All frames written; publish the output. */
    suspend fun finish()

    /** Discard everything. Idempotent. */
    fun abort()
}

data class RecordOptions(
    val output: Path, val width: Int, val height: Int, val fontSize: Int,
    val fps: Int, val holdBeforeMs: Int, val holdAfterMs: Int,
)

/**
 * Plays [steps] into [stage] on a virtual clock and sends every frame to [sink]: Player's sleeps
 * advance the clock instead of waiting, so a recording runs as fast as frames can be painted.
 * EDT only. Never aborts [sink] or disposes [stage]: the caller owns both.
 */
suspend fun record(
    project: Project, stage: RecordingStage, steps: List<Step>, timing: Timing,
    options: RecordOptions, sink: FrameSink, progress: (Double) -> Unit = {},
) {
    ThreadingAssertions.assertEventDispatchThread()
    val image = BufferedImage(options.width, options.height, BufferedImage.TYPE_3BYTE_BGR)
    val clock = FrameClock(options.fps)
    val document = stage.editor.document
    val initialLength = document.textLength
    val totalTypedChars = steps.filterIsInstance<Step.Type>().sumOf { it.text.length }.coerceAtLeast(1)
    val sleep: suspend (Duration) -> Unit = { d ->
        val n = clock.advance(d)
        // Let queued EDT work run first: the gutter's width update after the line count grows is queued.
        yield()
        if (n > 0) {
            stage.paint(image)
            // The editor can't change within one sleep, so every frame of it is the same bytes.
            val bytes = (image.raster.dataBuffer as DataBufferByte).data.copyOf()
            repeat(n) { sink.frame(bytes) }
        }
        // ponytail: progress from document growth; an action that deletes text makes it dip, track step indices if that ever matters
        progress(((document.textLength - initialLength).toDouble() / totalTypedChars).coerceIn(0.0, 1.0))
    }
    sleep(options.holdBeforeMs.milliseconds)
    Player(project, stage.editor, Any(), sleep).play(steps, timing)
    sleep(options.holdAfterMs.milliseconds)
    sink.finish()
}
