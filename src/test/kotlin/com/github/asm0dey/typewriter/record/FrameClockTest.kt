package com.github.asm0dey.typewriter.record

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FrameClockTest {
    @Test fun testSpecTimelineAt60Fps() {
        val c = FrameClock(60)
        assertEquals(6, c.advance(87.milliseconds))   // frames 0..5
        assertEquals(7, c.advance(114.milliseconds))  // frames 6..12
        assertEquals(5, c.advance(96.milliseconds))   // frames 13..17
        assertEquals(1, c.advance(4.milliseconds))    // [297, 301): frame 18 at 300 ms
    }

    @Test fun testHoldAddsFpsTimesSeconds() = assertEquals(60, FrameClock(60).advance(1.seconds))

    @Test fun testZeroAdvancesNothing() = assertEquals(0, FrameClock(60).advance(Duration.ZERO))

    @Test fun testNoDriftOverManyFrames() {
        val c = FrameClock(60)
        var n = 0
        repeat(3000) { n += c.advance(1.milliseconds) }
        assertEquals(180, n)
    }
}
