package com.github.asm0dey.typewriter.run

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager

/**
 * Cancels a run on any IDE action or any typed character (spec section 7, "Abort"; design
 * question 2, resolved in section 16). Deliberately an [AnActionListener], not an AWT
 * `KeyListener`: a `KeyListener` races the hotkey that starts the run -- the `keyReleased`
 * events of a chord like `Ctrl+T, 1` arrive after the run begins and would cancel it
 * immediately, and auto-repeat defeats a grace period. Neither `AnActionListener` hook sees raw
 * key events, so no such race exists here. [RunService.run] never types through
 * [beforeEditorTyping] either -- it inserts via `Document.insertString` -- so a run cannot
 * self-trigger this.
 *
 * The third abort trigger in spec section 7 -- "the caret not being where the run left it,
 * checked before each insertion" -- is [Player]'s own job (its `onCaretDrift` callback), not
 * this class's: that check happens between individual characters, which only the player can see.
 *
 * Two exemptions, both from spec section 7 "Abort": actions invoked by the run's own `action`
 * steps ([Player.invokingAction]), and any action whose id starts with `typewriter.` (the
 * plugin's own commands, including per-snippet play actions and `TypeWriter: Undo Run`).
 *
 * One watcher is scoped to one [player] (hence one run): [RunService] installs a fresh instance
 * per run, disposed when that run ends.
 */
class AbortWatcher(
    private val player: Player,
    private val onAbort: () -> Unit,
) : AnActionListener {

    fun install(parent: Disposable) {
        ApplicationManager.getApplication().messageBus
            .connect(parent)
            .subscribe(AnActionListener.TOPIC, this)
    }

    override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
        if (isExempt(action)) return
        onAbort()
    }

    override fun beforeEditorTyping(c: Char, dataContext: DataContext) {
        if (player.invokingAction) return
        onAbort()
    }

    private fun isExempt(action: AnAction): Boolean {
        if (player.invokingAction) return true
        val id = ActionManager.getInstance().getId(action) ?: return false
        return id.startsWith("typewriter.")
    }
}
