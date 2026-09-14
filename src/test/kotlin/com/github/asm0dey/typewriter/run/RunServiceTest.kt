package com.github.asm0dey.typewriter.run

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.model.Timing
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Deliberately NOT `@RunInEdt`, unlike every other fixture test in this repo: [RunService.run]
 * dispatches onto `Dispatchers.EDT` internally (see its kdoc, and inherited item 4), and that
 * dispatch can only be serviced while the physical EDT thread is free to pump its own event
 * queue. Wrapping this whole class on the EDT -- as `@RunInEdt` would -- means every
 * `runBlocking { svc.run(...) }` call below blocks that same physical thread waiting on a
 * coroutine that itself needs that exact thread to make progress: a guaranteed deadlock, not a
 * slow test (confirmed by hitting it directly -- see task-11-report.md). So this class runs its
 * `@Test` methods on a plain background thread and reaches for the EDT explicitly, via [onEdt],
 * only for the individual calls the platform actually requires there: PSI-backed fixture setup
 * and explicit write actions. See [TypeWriterFixtureTestCase]'s kdoc for where the annotation
 * that used to cover this went instead.
 */
class RunServiceTest : TypeWriterFixtureTestCase() {

    private fun service() = fixture.project.getService(RunService::class.java)

    private fun onEdt(action: () -> Unit) = ApplicationManager.getApplication().invokeAndWait(action)

    private fun configure(text: String) {
        onEdt { fixture.configureByText("R.java", text) }
    }

    @Test
    fun testRunTypesAndUndoRemovesExactlyTheTypedRange() {
        configure("before<caret>after")
        val svc = service()
        runBlocking { svc.run(fixture.editor, listOf(Step.Type("TYPED")), Timing(0, 0, 0)) }
        assertEquals("beforeTYPEDafter", fixture.editor.document.text)
        assertTrue(svc.canUndoLastRun())
        onEdt { WriteAction.run<Exception> { svc.undoLastRun() } }
        assertEquals("beforeafter", fixture.editor.document.text)
    }

    @Test
    fun testUndoIsInvalidatedByLaterEdits() {
        configure("<caret>")
        val svc = service()
        runBlocking { svc.run(fixture.editor, listOf(Step.Type("abc")), Timing(0, 0, 0)) }
        onEdt { WriteCommandAction.runWriteCommandAction(fixture.project) { fixture.editor.document.insertString(0, "z") } }
        assertFalse(svc.canUndoLastRun())
    }

    @Test
    fun testASecondRunIsRefusedWhileOneIsActive() {
        configure("<caret>")
        val svc = service()
        runBlocking {
            svc.run(fixture.editor, listOf(Step.Type("a")), Timing(0, 0, 0))
            assertFalse(svc.isRunning())
        }
    }

    @Test
    fun testCursorRoundTrips() {
        val svc = service()
        svc.cursor = 3
        assertEquals(3, svc.cursor)
    }

    // The brief's own "second run refused" test above never actually overlaps two runs (its
    // single svc.run() call has finished by the time isRunning() is checked), so it would still
    // pass even if concurrent runs were allowed. This test genuinely overlaps them: the first
    // run is parked inside a real (non-zero) delay when the second is attempted, so the second
    // call's compareAndSet must observe `running == true` and refuse synchronously.
    @Test
    fun testASecondRunIsRefusedWhileTheFirstIsGenuinelyStillPlaying() {
        configure("<caret>")
        val svc = service()
        runBlocking {
            val first = launch { svc.run(fixture.editor, listOf(Step.Pause(50)), Timing(0, 0, 0)) }
            while (!svc.isRunning()) yield()
            val secondAccepted = svc.run(fixture.editor, listOf(Step.Type("x")), Timing(0, 0, 0))
            assertFalse(secondAccepted)
            first.join()
        }
        assertFalse(svc.isRunning())
    }

    // Proves abort works DURING a run, not only before one starts (spec section 7, "Abort"):
    // publishes a real beforeEditorTyping event on the application message bus -- exactly what
    // the platform does when the user types a character -- while the run is mid-flight, and
    // checks that it stops short of typing everything. This only exercises a genuine mid-run
    // interleaving because the test method itself is not pinned to the EDT (see the class kdoc):
    // the run's own delay() suspensions actually free the real EDT to dispatch the published
    // action-system event while Player is still mid-loop.
    @Test
    fun testAUserKeystrokeAbortsARunInProgress() {
        configure("<caret>")
        val svc = service()
        val editor = fixture.editor
        val document = editor.document
        runBlocking {
            val job = launch {
                svc.run(editor, listOf(Step.Type("abcdefghijklmnopqrst")), Timing(15, 0, 0))
            }
            while (document.text.isEmpty()) yield()
            ApplicationManager.getApplication().messageBus.syncPublisher(AnActionListener.TOPIC)
                .beforeEditorTyping('z', DataContext.EMPTY_CONTEXT)
            job.join()
        }
        assertFalse(svc.isRunning())
        assertTrue(document.text.isNotEmpty(), "at least one character should have been typed before the abort")
        assertTrue(document.text.length < 20, "the run should have stopped before typing everything: ${document.text}")
    }

    // A pre-existing selection must be replaced, matching real typing (spec section 11 "Edges").
    // Player itself never touches SelectionModel -- this is the run's setup, owned by RunService.
    @Test
    fun testAPreExistingSelectionIsReplacedByTheRun() {
        // language="JAVA"
        configure("before<selection>SELECTED</selection>after")
        val svc = service()
        runBlocking { svc.run(fixture.editor, listOf(Step.Type("TYPED")), Timing(0, 0, 0)) }
        assertEquals("beforeTYPEDafter", fixture.editor.document.text)
    }

    // Undo Run must remove exactly the typed range and nothing else: with a pre-existing
    // selection, the typed range starts where the (now-deleted) selection was, and Undo Run's
    // guarantee covers only that -- not restoring the deleted selection text.
    @Test
    fun testUndoAfterReplacingASelectionRemovesOnlyTheTypedText() {
        // language="JAVA"
        configure("before<selection>SELECTED</selection>after")
        val svc = service()
        runBlocking { svc.run(fixture.editor, listOf(Step.Type("TYPED")), Timing(0, 0, 0)) }
        onEdt { WriteAction.run<Exception> { svc.undoLastRun() } }
        assertEquals("beforeafter", fixture.editor.document.text)
    }

    @Test
    fun testCanUndoLastRunIsFalseBeforeAnyRunHasHappened() {
        configure("<caret>")
        assertFalse(service().canUndoLastRun())
    }

    @Test
    fun testUndoLastRunIsANoOpWhenThereIsNothingToUndo() {
        configure("<caret>")
        val svc = service()
        var result = true
        onEdt { WriteAction.run<Exception> { result = svc.undoLastRun() } }
        assertFalse(result)
    }
}
