package com.github.asm0dey.typewriter.run

import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.model.Timing
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Bookkeeping for the most recently finished run, kept only so `Undo Run` can recover it. */
private class LastRun(val editor: Editor, val marker: RangeMarker, val stampAtEnd: Long)

/**
 * Owns a run's whole lifecycle: starting it, tracking it, aborting it, and undoing it (spec
 * section 7). A run cannot be paused, only aborted -- design question 9 -- so [undoLastRun] is
 * the recovery path.
 *
 * `@Service(PROJECT)` is itself the plugin.xml registration; it must not also appear in
 * plugin.xml as a `<projectService>`, or the platform can construct two instances -- one silently
 * holding stale run state.
 */
@Service(Service.Level.PROJECT)
class RunService(private val project: Project, private val scope: CoroutineScope) {

    private val running = AtomicBoolean(false)

    // Written only from the EDT (inside `run`'s withContext(Dispatchers.EDT) block) and read by
    // `cancel()`, which is always reached via AbortWatcher's AnActionListener callbacks -- those
    // are themselves dispatched on the EDT by the platform. @Volatile guards the case an
    // embedding action calls `cancel()` off the EDT anyway.
    @Volatile
    private var currentJob: Job? = null

    @Volatile
    private var lastRun: LastRun? = null

    /** Which snippet plays next in a `Type Next` sequence (design question 12). Belongs to this
     * session, not the talk: nothing persists it, so it resets when the IDE restarts. */
    var cursor: Int = 0

    fun isRunning(): Boolean = running.get()

    /**
     * Cancels the in-flight run's own coroutine [Job]. This is what actually makes a run
     * abortable while it is playing, not just before it starts: [Player.play] observes
     * cancellation only through `delay()`/`ensureActive()` on its own [Job], so cancelling
     * anything other than that exact job (e.g. merely flipping a flag) would leave a running
     * player deaf to the abort. A no-op when nothing is running.
     */
    fun cancel() {
        currentJob?.cancel()
    }

    /**
     * Fire-and-forget entry point for actions (Tasks 12/17), which must not suspend: starts a
     * run on this service's own project-scoped [scope] and returns immediately.
     */
    fun launch(editor: Editor, steps: List<Step>, timing: Timing) {
        scope.launch(Dispatchers.EDT) { run(editor, steps, timing) }
    }

    /**
     * Plays [steps] into [editor] and suspends until the run finishes or is aborted. Returns
     * `false` immediately, without touching the editor, when a run is already active -- a second
     * invocation during a run is refused, not queued (spec section 7). Returns `true` once a run
     * has genuinely started, whether it went on to finish in full or was stopped early by caret
     * drift (which [Player] itself already handles by returning cleanly, spec section 7's third
     * abort trigger).
     *
     * An abort delivered through [cancel] (Escape, another action, a keystroke -- see
     * [AbortWatcher]) instead throws [kotlinx.coroutines.CancellationException], exactly like any
     * other cancelled suspend function; [launch] is the entry point for callers that do not want
     * to handle that.
     *
     * Threading: document mutation must happen on the EDT inside a write action -- [Player]'s own
     * `insert()` calls `runWriteAction` directly, which requires the calling thread to already
     * *be* the EDT. `withContext(Dispatchers.EDT)` below is what guarantees that regardless of
     * which thread called `run`.
     */
    suspend fun run(editor: Editor, steps: List<Step>, timing: Timing): Boolean {
        if (!running.compareAndSet(false, true)) return false
        try {
            withContext(Dispatchers.EDT) {
                currentJob = currentCoroutineContext()[Job]
                val runId = Any()
                replaceSelection(editor, runId)

                // The typed range is tracked by a RangeMarker, not a pair of integers -- an
                // `action` step can change text length after this point, and a raw offset would
                // go stale (spec section 7, "Typed range").
                val offset = editor.caretModel.offset
                val marker = editor.document.createRangeMarker(offset, offset).apply { isGreedyToRight = true }

                val player = Player(project, editor, runId)
                val watcherScope = Disposer.newDisposable("TypeWriter run $runId")
                AbortWatcher(player) { cancel() }.install(watcherScope)
                try {
                    player.play(steps, timing)
                } finally {
                    Disposer.dispose(watcherScope)
                    // The previous run's marker (if any) is about to be unreachable from
                    // `lastRun` -- dispose it explicitly rather than leaving it registered in the
                    // document's marker tree to be updated on every future edit until GC gets to
                    // it. A talk-length session starts many runs; each one's marker otherwise
                    // leaks until the whole session ends.
                    lastRun?.marker?.dispose()
                    lastRun = LastRun(editor, marker, editor.document.modificationStamp)
                }
            }
            return true
        } finally {
            currentJob = null
            running.set(false)
        }
    }

    /**
     * A selection present when the run starts must be replaced, matching real typing (spec
     * section 11, "Edges"). [Player] inserts at the caret and never touches `SelectionModel`, so
     * without this a pre-existing selection would survive the run untouched -- this is run
     * *setup*, owned by the caller, not by the player.
     */
    private fun replaceSelection(editor: Editor, runId: Any) {
        val selectionModel = editor.selectionModel
        val start = selectionModel.selectionStart
        val end = selectionModel.selectionEnd
        if (end <= start) return
        CommandProcessor.getInstance().executeCommand(
            project,
            {
                ApplicationManager.getApplication().runWriteAction {
                    editor.document.deleteString(start, end)
                    editor.caretModel.moveToOffset(start)
                    selectionModel.removeSelection()
                }
            },
            "TypeWriter",
            runId,
        )
    }

    /**
     * `Undo Run` is enabled only while the document's modification stamp is unchanged since the
     * run ended (spec section 7, "Recovery") -- otherwise it would delete whatever now occupies
     * those offsets -- and only while the marker itself is still valid: an `action` step (e.g. a
     * reformat that replaces the whole file in one edit) can invalidate a plain `RangeMarker`,
     * after which `endOffset`/`startOffset` return -1 and must not be used to slice the document.
     */
    fun canUndoLastRun(): Boolean {
        val run = lastRun ?: return false
        return run.marker.isValid &&
            run.editor.document.modificationStamp == run.stampAtEnd &&
            run.marker.endOffset > run.marker.startOffset
    }

    /**
     * The guaranteed recovery path (spec section 7, "Recovery"): deletes exactly the last run's
     * typed range, read straight off its `RangeMarker`, in one write command. `groupId` on every
     * per-character command already gives native Ctrl+Z a chance to merge a run into one step,
     * but that merging is platform behaviour this plugin does not control -- this is the path
     * that depends only on code this plugin owns.
     */
    fun undoLastRun(): Boolean {
        val run = lastRun ?: return false
        if (!canUndoLastRun()) return false
        WriteCommandAction.runWriteCommandAction(project, "TypeWriter: Undo Run", null, {
            run.editor.document.deleteString(run.marker.startOffset, run.marker.endOffset)
        })
        run.marker.dispose()
        lastRun = null
        return true
    }
}
