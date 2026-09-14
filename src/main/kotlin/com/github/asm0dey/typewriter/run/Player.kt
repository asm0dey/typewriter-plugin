package com.github.asm0dey.typewriter.run

import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.model.Timing
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Types a parsed [Step] sequence into [editor]'s document, one run at a time. See design spec
 * section 7, "Playback engine".
 *
 * The player never consults a language: every language-aware step (scanning, parsing,
 * formatting, base indent) has already run, and hands it plain strings. Insertion goes straight
 * through [com.intellij.openapi.editor.Document.insertString] rather than the typing handler, so
 * the user's auto-close-pairs, auto-indent and smart-typing settings have no effect on the
 * output (spec section 7, "Independence from the user's typing settings").
 *
 * [marker] tracks the run's typed range as the document grows; the caller creates and configures
 * it (including `isGreedyToRight`) and keeps it around afterwards to read the range or undo the
 * run (Task 11's `Undo Run`). The player itself never reads it back -- the expected caret position
 * after an `action` step is the live caret, not the marker; see the comment on that assignment for
 * why.
 *
 * [runId] is passed as the `groupId` of every write command so native undo can merge a run's
 * per-character edits into one step (spec section 7, "Recovery").
 */
class Player(
    private val project: Project,
    private val editor: Editor,
    private val marker: RangeMarker,
    private val runId: Any,
) {
    /** Set while this player invokes an IDE action, so the abort watcher ignores it. */
    @Volatile
    var invokingAction: Boolean = false
        private set

    suspend fun play(steps: List<Step>, timing: Timing, onCaretDrift: () -> Unit = {}) {
        var expectedOffset = editor.caretModel.offset
        for (step in steps) {
            // delay(0) (an instant Timing, or a Step.Pause(0)) returns without ever suspending,
            // so it never checks the Job; this call is what actually makes a run abortable when
            // every delay in it is zero.
            currentCoroutineContext().ensureActive()
            when (step) {
                is Step.Pause -> delay(step.millis.milliseconds)
                is Step.Action -> {
                    runAction(step.actionId)
                    // Deliberately editor.caretModel.offset, not marker.endOffset -- do not
                    // "simplify" this back to the marker. Resolved design question (see the
                    // design spec's section 7 "Typed range" -- amended -- and the acceptance
                    // brief for this decision): drift detection exists to catch the *user*
                    // moving the caret, and spec section 7 "Abort" already exempts "actions
                    // invoked by the run's own `action` steps" from that -- the run's own action
                    // is not the user. Reading "re-derived ... not from the last insertion
                    // offset" as ruling out a *stale held value* (which a freshly-read live caret
                    // is not) reconciles with spec section 7's own completion example: `action
                    // CodeCompletion` followed by `action EditorChooseLookupItem` can park the
                    // caret inside a completion's inserted parens (e.g. `foo(|)`), short of the
                    // typed range's end -- exactly the case marker.endOffset would misread as
                    // drift and wrongly abort. See PlayerTest.testTypingContinuesAtTheCaretAfterAnActionMovesIt.
                    expectedOffset = editor.caretModel.offset
                }
                is Step.Type -> {
                    var i = 0
                    while (i < step.text.length) {
                        currentCoroutineContext().ensureActive()
                        if (editor.caretModel.offset != expectedOffset) {
                            onCaretDrift()
                            return
                        }
                        val codePoint = step.text.codePointAt(i)
                        val chunk = String(Character.toChars(codePoint))
                        insert(chunk)
                        expectedOffset = editor.caretModel.offset
                        editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
                        i += Character.charCount(codePoint)
                        delay(delayFor(chunk, timing))
                    }
                }
            }
        }
    }

    private fun insert(chunk: String) {
        CommandProcessor.getInstance().executeCommand(
            project,
            {
                ApplicationManager.getApplication().runWriteAction {
                    val offset = editor.caretModel.offset
                    editor.document.insertString(offset, chunk)
                    editor.caretModel.moveToOffset(offset + chunk.length)
                }
            },
            "TypeWriter",
            runId,
        )
    }

    private fun runAction(actionId: String) {
        val action = ActionManager.getInstance().getAction(actionId) ?: return
        invokingAction = true
        try {
            // No DataContext argument here: tryToExecute derives it from contextComponent, and
            // editor.contentComponent already resolves CommonDataKeys.EDITOR/PROJECT on its own
            // (verified by PlayerTest.testActionStepRunsARealIdeActionAndTypingContinuesAfterIt,
            // which runs a real action and checks its effect on the document).
            ActionManager.getInstance().tryToExecute(
                action,
                null,
                editor.contentComponent,
                ActionPlaces.UNKNOWN,
                true,
            )
        } finally {
            invokingAction = false
        }
    }

    internal fun delayFor(chunk: String, timing: Timing): Duration {
        val base = timing.speedMs.milliseconds + if (chunk == "\n") timing.newlineMs.milliseconds else Duration.ZERO
        val jitter = if (timing.jitterMs > 0) Random.nextInt(-timing.jitterMs, timing.jitterMs + 1).milliseconds else Duration.ZERO
        val total = base + jitter
        return if (total < Duration.ZERO) Duration.ZERO else total
    }
}
