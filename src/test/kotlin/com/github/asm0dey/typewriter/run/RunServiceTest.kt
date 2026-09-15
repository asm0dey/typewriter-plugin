package com.github.asm0dey.typewriter.run

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.model.Timing
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.TestActionEvent
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
 *
 * Running off the EDT is a property of this class, not a licence: anything here that simulates a
 * platform event the platform itself only ever publishes on the EDT must go through [publishOnEdt]
 * -- see its kdoc for the intermittent failure that came of not doing so.
 */
class RunServiceTest : TypeWriterFixtureTestCase() {

    private fun service() = fixture.project.getService(RunService::class.java)

    private fun onEdt(action: () -> Unit) = ApplicationManager.getApplication().invokeAndWait(action)

    /**
     * Publishes one of the platform's own [AnActionListener] events the way the platform itself
     * does: from the EDT. Every test below that fakes a keystroke or an action mid-run must go
     * through this, never `syncPublisher(...)` on this class's background test thread.
     *
     * Why: the message bus delivers this topic SYNCHRONOUSLY, on the publishing thread, to every
     * subscriber -- not just to this run's [AbortWatcher]. The platform's own subscribers are on
     * it too, and `LocalHintManager$MyAnActionListener.beforeActionPerformed` calls `hideHints()`,
     * which asserts the EDT. Publishing from the test thread therefore made the PLATFORM log
     * "Assert: must be called on EDT", which the JUnit5 test-framework's TestLoggerInterceptor
     * turns into a failure of whichever test is running.
     *
     * Why it looked like flakiness: `LocalHintManager` is instantiated lazily, so that subscriber
     * exists only once some earlier test in the same JVM has caused a hint/lookup to appear
     * (`MarkerCompletionTest` does). The suite's class order varies between runs, so the same code
     * failed or passed depending on whether that class had run yet -- `--tests '*RunServiceTest*'`
     * alone always passed; `--tests '*MarkerCompletionTest*' --tests '*RunServiceTest*'` always
     * failed.
     *
     * `invokeAndWait` (not `invokeLater`) additionally removes the delivery race: the event is
     * guaranteed to have been processed before the test thread moves on. It is still queued on the
     * EDT's own event queue, so it can only land between two of the run's dispatcher turns --
     * exactly like a real keypress, and exactly the property
     * [testANewlineOnlyTimingRunIsStillAbortableMidRun] relies on.
     */
    private fun publishOnEdt(event: (AnActionListener) -> Unit) = onEdt {
        event(ApplicationManager.getApplication().messageBus.syncPublisher(AnActionListener.TOPIC))
    }

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
            publishOnEdt { it.beforeEditorTyping('z', DataContext.EMPTY_CONTEXT) }
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

    // Fix round: Player.delayFor's clamp used to floor at Duration.ZERO, not 1ms. That floor is
    // not just an all-zero-Timing problem: delayFor only adds newlineMs for a "\n" chunk, so
    // Timing(0, 0, 300) still computes delay(0) for every *non-newline* character -- a snippet
    // author reaches this by setting `tw: speed 0` / `tw: jitter 0` and inheriting the default
    // newlineMs. Before the fix, this whole run typed as one synchronous EDT burst with no point
    // at which the published abort event could land mid-run; this test fails if that floor is
    // reverted to Duration.ZERO (verified directly -- see task-11-report.md's fix-round section
    // for the quoted failure).
    @Test
    fun testANewlineOnlyTimingRunIsStillAbortableMidRun() {
        configure("<caret>")
        val svc = service()
        val editor = fixture.editor
        val document = editor.document
        val text = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        runBlocking {
            val job = launch { svc.run(editor, listOf(Step.Type(text)), Timing(0, 0, 300)) }
            while (document.text.isEmpty()) yield()
            // Delivered through the EDT's own event queue via invokeLater, not by calling the
            // listener directly from this background thread. This is what makes the test
            // discriminating rather than a coincidence of OS thread scheduling: a direct
            // cross-thread syncPublisher call reaches AbortWatcher regardless of whether Player's
            // loop ever yields, because Job.cancel() and ensureActive() are thread-safe on their
            // own -- so a direct call landed even with delayFor's floor reverted to Duration.ZERO
            // in manual testing (see task-11-report.md's fix-round section). A REAL Escape/arrow
            // keypress reaches AbortWatcher only via the actual action-dispatch machinery, which
            // runs on the EDT and therefore cannot be *processed* -- not merely "not yet
            // observed" -- while that same thread is busy running Player's loop synchronously.
            // invokeLater reproduces that: it queues onto the same EDT `withContext(Dispatchers.
            // EDT)` uses, so it can only run between two dispatcher turns, i.e. only if Player's
            // loop actually suspends somewhere. [publishOnEdt] has the same property (it queues
            // too, and additionally waits for delivery); this call keeps `invokeLater` only
            // because this test is specifically about what a *deferred* EDT event can observe.
            ApplicationManager.getApplication().invokeLater(
                {
                    ApplicationManager.getApplication().messageBus.syncPublisher(AnActionListener.TOPIC)
                        .beforeEditorTyping('z', DataContext.EMPTY_CONTEXT)
                },
                ModalityState.any(),
            )
            job.join()
        }
        assertFalse(svc.isRunning())
        assertTrue(document.text.isNotEmpty(), "at least one character should have been typed before the abort")
        assertTrue(
            document.text.length < text.length,
            "the run should have stopped before typing everything: ${document.text}",
        )
    }

    // AbortWatcher's invokingAction exemption, exercised through the real wiring RunService
    // installs -- not by inspecting AbortWatcher in isolation. Without the exemption, Step.Action
    // running "EditorEnter" publishes beforeActionPerformed on the same bus this run's own
    // watcher is subscribed to, and the run would cancel itself before typing "b" (verified
    // directly by deleting the exemption -- see task-11-report.md's fix-round section).
    @Test
    fun testTheRunsOwnActionStepDoesNotAbortItself() {
        configure("<caret>")
        val svc = service()
        runBlocking {
            svc.run(fixture.editor, listOf(Step.Type("a"), Step.Action("EditorEnter"), Step.Type("b")), Timing(0, 0, 0))
        }
        assertEquals("a\nb", fixture.editor.document.text)
    }

    // The beforeActionPerformed abort path -- the hook Escape and the arrow keys actually arrive
    // on -- had no test at all before this fix round. Publishes a real AnActionListener.TOPIC
    // beforeActionPerformed event, for an action with no registered id (so AbortWatcher's
    // "typewriter." prefix exemption cannot apply and player.invokingAction is false), mid-run.
    @Test
    fun testAnIdeActionAbortsARunInProgress() {
        configure("<caret>")
        val svc = service()
        val editor = fixture.editor
        val document = editor.document
        val text = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val action = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) = Unit
        }
        runBlocking {
            val job = launch { svc.run(editor, listOf(Step.Type(text)), Timing(1, 0, 0)) }
            while (document.text.isEmpty()) yield()
            publishOnEdt { it.beforeActionPerformed(action, TestActionEvent.createTestEvent(action)) }
            job.join()
        }
        assertFalse(svc.isRunning())
        assertTrue(document.text.isNotEmpty(), "at least one character should have been typed before the abort")
        assertTrue(
            document.text.length < text.length,
            "the run should have stopped before typing everything: ${document.text}",
        )
    }

    // The "typewriter." id-prefix exemption: an action registered under that prefix (matching
    // the plugin's own commands, e.g. per-snippet play actions and TypeWriter: Undo Run) must
    // NOT abort a run in progress when it fires mid-run.
    @Test
    fun testATypewriterPrefixedActionDoesNotAbortARunInProgress() {
        configure("<caret>")
        val svc = service()
        val editor = fixture.editor
        val document = editor.document
        val actionId = "typewriter.test.harmless"
        val action = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) = Unit
        }
        ActionManager.getInstance().registerAction(actionId, action)
        // Read on the EDT at the instant the event is delivered. Without it this test would pass
        // vacuously if the event ever landed AFTER the run finished: the exemption would never
        // have been exercised, yet the document would still hold the full text.
        var lengthAtDelivery = -1
        try {
            runBlocking {
                val job = launch { svc.run(editor, listOf(Step.Type("abcdefghij")), Timing(1, 0, 0)) }
                while (document.text.isEmpty()) yield()
                publishOnEdt {
                    lengthAtDelivery = document.textLength
                    it.beforeActionPerformed(action, TestActionEvent.createTestEvent(action))
                }
                job.join()
            }
        } finally {
            ActionManager.getInstance().unregisterAction(actionId)
        }
        assertEquals("abcdefghij", document.text)
        assertFalse(svc.isRunning())
        assertTrue(
            lengthAtDelivery in 1..9,
            "the event must land mid-run for the exemption to be exercised, but the document held " +
                "$lengthAtDelivery of 10 characters when it was delivered",
        )
    }
}
