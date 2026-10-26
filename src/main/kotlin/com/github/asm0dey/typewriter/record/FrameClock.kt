package com.github.asm0dey.typewriter.record

import kotlin.time.Duration

/**
 * Virtual time for a recording. [advance] moves time forward and returns how many frame
 * times `k / fps` fall in the half-open interval `[t, t + d)`, so a chunk inserted exactly
 * on a frame time is painted by the sleep that follows it. Integer nanoseconds: no drift.
 */
class FrameClock(private val fps: Int) {
    /** The current virtual time, in nanoseconds. */
    var nanos = 0L
        private set

    fun advance(d: Duration): Int {
        val first = ceilDiv(nanos * fps)
        nanos += d.inWholeNanoseconds
        return (ceilDiv(nanos * fps) - first).toInt()
    }

    private fun ceilDiv(n: Long) = Math.ceilDiv(n, 1_000_000_000L)
}
