package com.github.asm0dey.typewriter.run

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.model.Timing
import com.intellij.openapi.editor.RangeMarker
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
}
