package com.github.asm0dey.typewriter.record

import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.model.Timing
import com.github.asm0dey.typewriter.run.Player
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.ThreadingAssertions
import kotlinx.coroutines.yield
import java.awt.Dimension
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

/**
 * [fit]: ignore [width] x [height] and size the video to its code ([fitted]), no taller than
 * [maxRatio] (width / height) allows; 0 = no limit.
 */
data class RecordOptions(
    val output: Path, val width: Int, val height: Int, val fontSize: Int,
    val fps: Int, val holdBeforeMs: Int, val holdAfterMs: Int, val fit: Boolean = false,
    val maxRatio: Double = 16.0 / 9,
)

/** Whether a caret blinking every [periodMs] shows [sinceMove] ns after it last moved: on first, as in the IDE. 0 = no blinking. */
internal fun caretShown(sinceMove: Long, periodMs: Int): Boolean =
    periodMs <= 0 || (sinceMove / (periodMs * 1_000_000L)) % 2 == 0L

/**
 * [options], or for [RecordOptions.fit] [options] sized to show every line [steps] ever produce in
 * full: plays them unpainted on a throwaway stage from [newStage] and keeps the largest
 * [RecordingStage.fullSize], rounded up to even and capped at [MAX_WIDTH] x [MAX_HEIGHT]. A
 * [RecordOptions.maxRatio] caps the height at that ratio of the width; the code then scrolls. EDT only.
 */
suspend fun fitted(
    project: Project, options: RecordOptions, steps: List<Step>, timing: Timing,
    newStage: (width: Int, height: Int, parent: Disposable) -> RecordingStage,
): RecordOptions {
    if (!options.fit) return options
    val disposable = Disposer.newDisposable("TypeWriter measuring")
    try {
        val stage = newStage(MAX_WIDTH, MAX_HEIGHT, disposable)
        var size = stage.fullSize()
        val measure: suspend (Duration) -> Unit = {
            // The gutter's width update after the line count grows is queued: let it run first.
            yield()
            val now = stage.fullSize()
            size = Dimension(maxOf(size.width, now.width), maxOf(size.height, now.height))
        }
        Player(project, stage.editor, Any(), measure).play(steps, timing)
        fun even(n: Int, max: Int) = minOf((n + 1) / 2 * 2, max)
        val width = even(size.width, MAX_WIDTH)
        val height = if (options.maxRatio > 0) minOf(size.height, (width / options.maxRatio).toInt()) else size.height
        return options.copy(width = width, height = even(height, MAX_HEIGHT))
    } finally {
        Disposer.dispose(disposable)
    }
}

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
    val caret = stage.editor.caretModel
    val blinkMs = stage.editor.settings.let { if (it.isBlinkCaret) it.caretBlinkPeriod else 0 }
    // The caret stays on while the text or caret keeps changing, and blinks from the last change.
    var lastState = document.modificationStamp to caret.offset
    var movedAt = 0L
    var frame = 0L
    val totalTypedChars = steps.filterIsInstance<Step.Type>().sumOf { it.text.length }.coerceAtLeast(1)
    fun snapshot(withCaret: Boolean): ByteArray {
        stage.paint(image, withCaret)
        return (image.raster.dataBuffer as DataBufferByte).data.copyOf()
    }
    val sleep: suspend (Duration) -> Unit = { d ->
        val state = document.modificationStamp to caret.offset
        if (state != lastState) {
            lastState = state
            movedAt = clock.nanos
        }
        val n = clock.advance(d)
        // Let queued EDT work run first: the gutter's width update after the line count grows is queued.
        yield()
        if (n > 0) {
            val shown = (frame until frame + n).map { caretShown(it * 1_000_000_000L / options.fps - movedAt, blinkMs) }
            frame += n
            // The editor can't change within one sleep, so each frame of it is one of two images.
            val on = if (true in shown) snapshot(true) else null
            val off = if (false in shown) snapshot(false) else null
            shown.forEach { sink.frame(if (it) on!! else off!!) }
        }
        // ponytail: progress from document growth; an action that deletes text makes it dip, track step indices if that ever matters
        progress(((document.textLength - initialLength).toDouble() / totalTypedChars).coerceIn(0.0, 1.0))
    }
    sleep(options.holdBeforeMs.milliseconds)
    Player(project, stage.editor, Any(), sleep).play(steps, timing)
    sleep(options.holdAfterMs.milliseconds)
    sink.finish()
}
