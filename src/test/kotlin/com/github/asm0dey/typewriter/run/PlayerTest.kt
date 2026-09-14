package com.github.asm0dey.typewriter.run

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.model.Timing
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlayerTest : TypeWriterFixtureTestCase() {

    private val instant = Timing(0, 0, 0)

    private fun play(initial: String, steps: List<Step>): String {
        fixture.configureByText("P.java", initial)
        val editor = fixture.editor
        val marker: RangeMarker = editor.document.createRangeMarker(
            editor.caretModel.offset, editor.caretModel.offset,
        )
        runBlocking { Player(fixture.project, editor, marker, Any()).play(steps, instant) }
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
        runBlocking { Player(fixture.project, editor, marker, Any()).play(listOf(Step.Type("ABC")), instant) }
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
    // document; (2) the expected caret position after the action is re-derived from the
    // marker's end (spec section 7, "Typed range"), so the following Type step is accepted
    // rather than flagged as drift. isGreedyToRight has to be set for this one: EditorEnter
    // inserts its newline exactly at the marker's end offset, and a non-greedy marker (the
    // RangeMarker default, and fine for the other tests, which don't touch this) would leave
    // that insertion outside the tracked range, understating where the run actually left off.
    @Test
    fun testActionStepRunsARealIdeActionAndTypingContinuesAfterIt() {
        fixture.configureByText("P.java", "<caret>")
        val editor = fixture.editor
        val offset = editor.caretModel.offset
        val marker = editor.document.createRangeMarker(offset, offset)
        marker.isGreedyToRight = true
        runBlocking {
            Player(fixture.project, editor, marker, Any())
                .play(listOf(Step.Action("EditorEnter"), Step.Type("x")), instant)
        }
        assertEquals("\nx", editor.document.text)
    }

    // Pins the marker.endOffset choice (spec section 7, "Typed range": "The expected caret
    // position after an `action` step is re-derived from the marker's end, not from the last
    // insertion offset") against the brief's editor.caretModel.offset -- do not "fix" this back
    // to the brief's version. The two only diverge when an action moves the caret away from the
    // end of the typed range without changing the range itself: EditorLineStart does exactly
    // that. After typing "abc" the marker ends at offset 3; EditorLineStart moves the caret to
    // column 0 but leaves the marker where it was. With marker.endOffset as the source of truth,
    // the following Type("x") sees the caret (0) diverge from the typed range's end (3) and
    // aborts via onCaretDrift, leaving "abc" untouched. With caretModel.offset it would instead
    // treat 0 as the new baseline, see no drift, and type "x" at the start -- "xabc". Verified by
    // temporarily changing the production line to editor.caretModel.offset: this test then fails
    // (see task-6-report.md, fix round 1).
    @Test
    fun testActionStepExpectedOffsetComesFromTheMarkerNotTheCaret() {
        fixture.configureByText("P.java", "<caret>")
        val editor = fixture.editor
        val offset = editor.caretModel.offset
        val marker = editor.document.createRangeMarker(offset, offset)
        marker.isGreedyToRight = true
        runBlocking {
            Player(fixture.project, editor, marker, Any())
                .play(listOf(Step.Type("abc"), Step.Action("EditorLineStart"), Step.Type("x")), instant)
        }
        assertEquals("abc", editor.document.text)
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
    // (most likely EdtInterceptorExtension's own coroutine-context propagation into the write
    // action, visible in the failure's stack trace) incidentally stops the second one. That
    // incidental stop is test-harness behaviour this plugin does not control and cannot rely on
    // in production; ensureActive() is what makes "zero characters typed" a guarantee instead of
    // a coincidence.
    @Test
    fun testCancellationStopsTypingBeforeTheFirstCharacterWhenTheJobIsAlreadyCancelled() {
        fixture.configureByText("P.java", "<caret>")
        val editor = fixture.editor
        val offset = editor.caretModel.offset
        val marker = editor.document.createRangeMarker(offset, offset)
        val player = Player(fixture.project, editor, marker, Any())
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
    @Test
    fun testCancellationStopsTypingAfterTheSecondCharacterMidRun() {
        fixture.configureByText("P.java", "<caret>")
        val editor = fixture.editor
        val document = editor.document
        val offset = editor.caretModel.offset
        val marker = document.createRangeMarker(offset, offset)
        val player = Player(fixture.project, editor, marker, Any())
        runBlocking {
            lateinit var scope: CoroutineScope
            scope = CoroutineScope(Job(currentCoroutineContext()[Job]))
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
                    player.play(listOf(Step.Type("abcdef")), instant)
                }
                run.join()
            } finally {
                editor.caretModel.removeCaretListener(listener)
            }
        }
        assertEquals("ab", document.text)
    }
}
