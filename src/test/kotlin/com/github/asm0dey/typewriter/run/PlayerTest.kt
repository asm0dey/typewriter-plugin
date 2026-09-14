package com.github.asm0dey.typewriter.run

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.model.Timing
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.VisibleAreaListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class PlayerTest : TypeWriterFixtureTestCase() {

    private val instant = Timing(0, 0, 0)

    private fun play(initial: String, steps: List<Step>): String {
        fixture.configureByText("P.java", initial)
        val editor = fixture.editor
        runBlocking { Player(fixture.project, editor, Any()).play(steps, instant) }
        return editor.document.text
    }

    @Test
    fun testTypesTextVerbatim() {
        // language="JAVA"
        val text = "class A {}"
        assertEquals(text, play("<caret>", listOf(Step.Type(text))))
    }

    @Test
    fun testDoesNotAutoCloseBraces() {
        // language="JAVA"
        val text = "class A {"
        assertEquals(text, play("<caret>", listOf(Step.Type(text))))
    }

    @Test
    fun testTypesSurrogatePairs() {
        // language="JAVA"
        val text = "\"tea 🍵\""
        assertEquals(text, play("<caret>", listOf(Step.Type(text))))
    }

    @Test
    fun testTypesIntoAnExistingDocumentAtTheCaret() {
        // language="JAVA"
        val host = """
            |class Host {
            |    <caret>
            |}
        """.trimMargin()
        // language="JAVA"
        val typed = "int a = 1;"
        // language="JAVA"
        val expected = """
            |class Host {
            |    int a = 1;
            |}
        """.trimMargin()
        assertEquals(expected, play(host, listOf(Step.Type(typed))))
    }

    @Test
    fun testPauseDoesNotChangeTheText() {
        assertEquals("ab", play("<caret>", listOf(Step.Type("a"), Step.Pause(0), Step.Type("b"))))
    }

    @Test
    fun testTypedRangeIsTrackedByTheMarker() {
        fixture.configureByText("P.java", "x<caret>y")
        val editor = fixture.editor
        val offset = editor.caretModel.offset
        val marker = editor.document.createRangeMarker(offset, offset)
        marker.isGreedyToRight = true
        runBlocking { Player(fixture.project, editor, Any()).play(listOf(Step.Type("ABC")), instant) }
        assertEquals("ABC", editor.document.getText(marker.textRange))
    }

    // A step list containing an unrecognized action id must be a safe no-op (runAction returns
    // early because ActionManager has nothing registered under that id), and the expected caret
    // position for the following Type step must still be derived correctly so typing continues
    // rather than being mistaken for drift.
    @Test
    fun testUnknownActionStepIsANoOpAndTypingContinues() {
        assertEquals(
            "x",
            play("<caret>", listOf(Step.Action("typewriter.test.nonexistent"), Step.Type("x"))),
        )
    }

    // Proves two things about the Action branch at once: (1) runAction's tryToExecute call
    // resolves a real project/editor from editor.contentComponent alone -- Player builds no
    // DataContext of its own -- so a real action (EditorEnter) actually runs and mutates the
    // document; (2) after the action, the following Type step is accepted rather than flagged
    // as drift -- expectedOffset is the live caret (editor.caretModel.offset), and EditorEnter
    // happens to leave the caret exactly where its "\n" landed, so there is nothing for it to
    // diverge from. This test passes under either rule (caret-based or the earlier
    // marker-based one) since EditorEnter's caret and the typed range's end coincide here; see
    // testTypingContinuesAtTheCaretAfterAnActionMovesIt below for the action that tells the two
    // apart.
    @Test
    fun testActionStepRunsARealIdeActionAndTypingContinuesAfterIt() {
        fixture.configureByText("P.java", "<caret>")
        val editor = fixture.editor
        runBlocking {
            Player(fixture.project, editor, Any())
                .play(listOf(Step.Action("EditorEnter"), Step.Type("x")), instant)
        }
        assertEquals("\nx", editor.document.text)
    }

    // Pins the resolved rule (Player.kt: the expected caret position after an `action` step is
    // the live caret, not marker.endOffset -- see that comment for why) against the marker-based
    // rule this test used to assert before that design question was resolved -- do not "fix"
    // this back to the marker. The two only diverge when an action moves the caret away from the
    // end of the typed range without changing the range itself: EditorLineStart does exactly
    // that. After typing "abc" the marker ends at offset 3; EditorLineStart moves the caret to
    // column 0. With the caret as the source of truth, the following Type("x") sees no
    // divergence (the caret is exactly where the run's own action left it) and types "x" there
    // -- "xabc". With marker.endOffset it would instead read the caret's new position (0) as
    // drift against the marker's end (3) and wrongly abort mid-snippet, leaving "abc" untyped --
    // exactly the failure mode spec section 7's own completion example ("action CodeCompletion"
    // followed by "action EditorChooseLookupItem" parking the caret inside inserted parens) would
    // hit under the marker rule. Verified by temporarily reverting the production line to
    // marker.endOffset: this test then fails with "abc" instead of "xabc" (see task-6-report.md,
    // fix round 3).
    //
    // Asserts onCaretDrift() does NOT fire, alongside the document text, so a regression that
    // reintroduces a spurious drift call after this kind of action is caught even if it somehow
    // left the final text alone.
    @Test
    fun testTypingContinuesAtTheCaretAfterAnActionMovesIt() {
        fixture.configureByText("P.java", "<caret>")
        val editor = fixture.editor
        var drifted = false
        runBlocking {
            Player(fixture.project, editor, Any())
                .play(listOf(Step.Type("abc"), Step.Action("EditorLineStart"), Step.Type("x")), instant) {
                    drifted = true
                }
        }
        assertEquals("xabc", editor.document.text)
        assertFalse(drifted)
    }

    // Regression test for the exact risk this task calls out: a suspend function that only
    // relies on delay() to observe cancellation is never cancellable when every Timing delay is
    // zero, because kotlinx.coroutines' delay(timeMillis) returns immediately without ever
    // reaching suspendCancellableCoroutine when timeMillis <= 0 (kotlinx-coroutines-core Delay.kt).
    //
    // The Job is cancelled BEFORE play() ever starts. CoroutineStart.UNDISPATCHED is required to
    // make the coroutine body actually run despite that -- CoroutineStart.DEFAULT (what
    // runBlocking always uses) refuses to invoke the block at all when its parent Job is already
    // cancelled, which would make this pass trivially regardless of what Player does internally.
    // Under UNDISPATCHED the body runs synchronously and reaches Player's own
    // currentCoroutineContext().ensureActive() check as the very first statement of play(); with
    // an instant Timing there is no other suspension point that could have caught this. Verified
    // by temporarily deleting both ensureActive() calls from Player.play(): this test then fails
    // with document text "a" instead of "" -- one character leaks through before something else
    // incidentally stops the second one. That something is not traced: ChildContext.runInChildContext
    // and NestedLocksThreadingSupport appear in the failure's stack trace, but what they do and
    // why cancelling this Job affects them is not established here. That incidental stop is
    // test-harness behaviour this plugin does not control and cannot rely on in production;
    // ensureActive() is what makes "zero characters typed" a guarantee instead of a coincidence.
    @Test
    fun testCancellationStopsTypingBeforeTheFirstCharacterWhenTheJobIsAlreadyCancelled() {
        fixture.configureByText("P.java", "<caret>")
        val editor = fixture.editor
        val player = Player(fixture.project, editor, Any())
        runBlocking {
            // A CoroutineScope owning its own child Job (parented to this runBlocking's job, so
            // the coroutine below stays a structural descendant rather than a detached one) --
            // not a bare Job handed to launch's context, which would make the launched coroutine
            // a child of that Job instead of of this scope.
            val scope = CoroutineScope(Job(currentCoroutineContext()[Job]))
            scope.cancel()
            val run = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                player.play(listOf(Step.Type("abc")), instant)
            }
            run.join()
        }
        assertEquals("", editor.document.text)
    }

    // Cancels mid-run, not just at the start, and specifically exercises the in-loop
    // ensureActive() (Player.kt, top of the per-code-point while loop) rather than the
    // per-Step one at play()'s entry: the Job is still active when Step.Type("abcdef")
    // begins, so that outer check passes, and cancellation only happens after two
    // characters are already committed.
    //
    // The reentrant hook matters. A DocumentListener or a ScrollingModel
    // VisibleAreaListener also fire synchronously from inside Player's call stack, but
    // cancelling from either one lands inside CommandProcessor's own non-cancellable
    // command-execution window and triggers the platform's own undo-the-whole-group
    // recovery (observed directly: with such a listener, cancelling after "ab" is typed
    // rolls the document back to "" -- both characters, not just the pending one -- and
    // this happens identically whether or not ensureActive() is present, so neither of
    // those hooks can tell the two implementations apart). A CaretListener does not: its
    // callback runs outside that window, so cancelling from it leaves already-committed
    // characters alone and lets ensureActive() decide what happens next -- which is what
    // this test needs.
    //
    // The assertion captures what play() itself throws (via runCatching, inside the launch
    // block) rather than leaning only on the document's final text. With ensureActive()
    // present, play() throws CancellationException and "ab" is exactly what's committed --
    // that's the passing path. Without it (verified by deletion: fix round 2,
    // task-6-report.md), play() does not return normally either, but not with
    // CancellationException: it proceeds to attempt the 'c' insertion under an
    // already-cancelled Job, that insertion itself commits ('c' lands in the document, same
    // "commits, then something throws on the way out" pattern as the start-of-run test
    // above), and then a RuntimeException surfaces from within that same write command
    // ("The following files have changes that cannot be undone") -- a genuinely different
    // failure than clean cancellation, not a platform detail this test should tolerate.
    // Asserting CancellationException pins the property this test actually depends on,
    // rather than a document-length coincidence or a platform failure mode that could look
    // different on another release.
    @Test
    fun testCancellationStopsTypingAfterTheSecondCharacterMidRun() {
        fixture.configureByText("P.java", "<caret>")
        val editor = fixture.editor
        val document = editor.document
        val player = Player(fixture.project, editor, Any())
        var thrown: Throwable? = null
        runBlocking {
            val scope = CoroutineScope(Job(currentCoroutineContext()[Job]))
            val listener = object : CaretListener {
                override fun caretPositionChanged(e: CaretEvent) {
                    if (document.text == "ab") {
                        scope.cancel()
                    }
                }
            }
            editor.caretModel.addCaretListener(listener)
            try {
                val run = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    thrown = runCatching { player.play(listOf(Step.Type("abcdef")), instant) }.exceptionOrNull()
                }
                run.join()
            } finally {
                editor.caretModel.removeCaretListener(listener)
            }
        }
        assertEquals("ab", document.text)
        assertInstanceOf(CancellationException::class.java, thrown)
    }

    // delayFor (internal for this test) computes the per-character pacing: speedMs is the base,
    // newlineMs adds hesitation only for a newline chunk, jitterMs randomizes within a symmetric
    // band, and the whole thing is clamped at zero. Every other test in this class uses
    // Timing(0, 0, 0), which collapses all three knobs to zero and would not notice a newline
    // hesitation attached to the wrong branch, a jitter sign flip, or a lost clamp.
    @Test
    fun testDelayForAddsNewlineHesitationOnlyToNewlines() {
        val timing = Timing(speedMs = 10, jitterMs = 0, newlineMs = 5)
        val player = playerForDelayForTests()
        assertEquals(10.milliseconds, player.delayFor("a", timing))
        assertEquals(15.milliseconds, player.delayFor("\n", timing))
    }

    @Test
    fun testDelayForJitterStaysWithinTheSymmetricBandAndNeverGoesNegative() {
        val timing = Timing(speedMs = 0, jitterMs = 50, newlineMs = 0)
        val player = playerForDelayForTests()
        repeat(500) {
            val delay = player.delayFor("a", timing)
            assertTrue(delay >= Duration.ZERO, "expected >= 0, was $delay")
            assertTrue(delay <= 50.milliseconds, "expected <= 50ms, was $delay")
        }
    }

    private fun playerForDelayForTests(): Player {
        fixture.configureByText("P.java", "<caret>")
        val editor = fixture.editor
        return Player(fixture.project, editor, Any())
    }

    // Drift is the mouse-click case (spec section 7, "Abort", third bullet): the caret ends up
    // somewhere the run did not put it. Now that the action-step offset is the live caret (fix
    // round 3), nothing in Player ever moves the caret to a position other than exactly where it
    // expects -- so the only way left to exercise this branch is to move the caret from outside
    // the player entirely, simulating the user.
    //
    // The hook has to be one whose callback runs genuinely outside Player's own write command,
    // not merely "a different listener type" -- two more direct candidates were considered and
    // rejected:
    //   - A DocumentListener fires *inside* insertString's own listener-notification phase,
    //     which is itself inside insert()'s write action. Player's insert() calls
    //     caretModel.moveToOffset(...) right after insertString, as part of that SAME write
    //     action -- so any caret move injected from a DocumentListener would be immediately
    //     overwritten by Player's own subsequent move before insert() even returns.
    //   - A CaretListener fires synchronously as part of every moveToOffset call, including
    //     insert()'s own. An injected move from inside that callback would be the last write to
    //     the caret before insert() returns, and Player's very next line
    //     (expectedOffset = editor.caretModel.offset) would adopt that injected position as the
    //     new "correct" baseline -- masking the drift instead of exercising it (the same trap
    //     encountered writing the mid-run cancellation test's DocumentListener attempt, but for
    //     caret state instead of Job state).
    // A ScrollingModel.VisibleAreaListener does not have either problem: Player calls
    // scrollToCaret(...) as its own separate statement, strictly after insert() has already
    // returned and expectedOffset has already been captured from the just-typed position. A move
    // injected from that callback lands after the correct baseline was recorded, so the next
    // iteration's drift check (editor.caretModel.offset != expectedOffset) sees exactly the
    // mismatch a real mouse click would produce.
    @Test
    fun testUserMovingTheCaretMidRunAbortsTheRunWithoutThrowing() {
        fixture.configureByText("P.java", "<caret>")
        val editor = fixture.editor
        val document = editor.document
        val player = Player(fixture.project, editor, Any())
        var drifted = false
        val listener = VisibleAreaListener {
            if (document.text == "a") editor.caretModel.moveToOffset(0)
        }
        editor.scrollingModel.addVisibleAreaListener(listener)
        try {
            assertDoesNotThrow {
                runBlocking {
                    player.play(listOf(Step.Type("abcdef")), instant) { drifted = true }
                }
            }
        } finally {
            editor.scrollingModel.removeVisibleAreaListener(listener)
        }
        assertEquals("a", document.text)
        assertTrue(drifted)
    }
}
