package com.github.asm0dey.typewriter.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The menu text that answers "which snippet will Type Next type?".
 *
 * Reported from a real session: the sequence cursor was in-memory state that nothing rendered, so
 * the only way to find out was to play it -- during a talk. A pure function needing no PSI fixture
 * extends nothing (plan "Test conventions").
 */
class SequenceLabelTest {

    private val three = listOf("01-intro.java", "02-entity.java", "03-outro.java")

    @Test
    fun testNamesTheTargetAndItsPositionInTheSequence() {
        assertEquals("Type Next: 02-entity.java (2 of 3)", sequenceLabel("Type Next", three, 1))
    }

    // The clamp must agree with SequenceRunner.playAt's own coerceIn, or at either end the menu
    // would promise one snippet and playing would type a different one. Both ends clamp rather
    // than wrap -- wrapping would silently restart the demo from step 1 in front of an audience.
    @Test
    fun testClampsAtBothEndsExactlyAsPlaybackDoes() {
        assertEquals(
            "Type Next: 01-intro.java (1 of 3)",
            sequenceLabel("Type Next", three, -2),
            "Type Previous at the start clamps to the first snippet, matching playAt",
        )
        assertEquals(
            "Type Next: 03-outro.java (3 of 3)",
            sequenceLabel("Type Next", three, 9),
            "past the end clamps to the last snippet rather than wrapping to the first",
        )
    }

    @Test
    fun testSaysSoWhenThereIsNoSequenceToWalk() {
        assertEquals(
            "Type Next (no snippets in the project directory)",
            sequenceLabel("Type Next", emptyList(), 0),
            "an empty project directory must explain itself, not name a snippet that does not exist",
        )
    }
}
