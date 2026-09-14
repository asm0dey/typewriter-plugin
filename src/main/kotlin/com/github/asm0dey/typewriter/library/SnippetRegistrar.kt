package com.github.asm0dey.typewriter.library

import com.intellij.openapi.actionSystem.ActionManager

/**
 * Dynamic per-snippet action registration (spec section 8, "Actions"). Each snippet's action id
 * is derived from its relative path via [SnippetLibrary.actionId], not from its shortcut -- v1's
 * defect was deriving the id from the shortcut, which orphaned the user's keymap binding every
 * time the shortcut changed. This registrar never touches the keymap: the IDE owns bindings, and
 * [register] only tells [ActionManager] which ids currently exist.
 */
object SnippetRegistrar {

    private val registered = linkedSetOf<String>()

    fun registeredIds(): Set<String> = registered.toSet()

    /**
     * Registers exactly one action per path in [relativePaths]; any id previously registered by
     * this object whose path is no longer present is unregistered. Renaming a snippet is
     * indistinguishable from deleting the old path and adding a new one: the old action -- and
     * with it, per spec section 8, its keymap binding -- disappears, and a fresh, unbound action
     * appears under the new id. That loss is accepted by the spec, not a defect here.
     */
    @Synchronized
    fun register(relativePaths: List<String>) {
        val manager = ActionManager.getInstance()
        val wanted = relativePaths.map { SnippetLibrary.actionId(it) }.toSet()

        for (id in registered - wanted) {
            if (manager.getAction(id) != null) manager.unregisterAction(id)
        }
        registered.retainAll(wanted)

        for (path in relativePaths) {
            val id = SnippetLibrary.actionId(path)
            if (registered.contains(id)) continue
            if (manager.getAction(id) == null) {
                manager.registerAction(id, TypeSnippetAction(path))
            }
            registered += id
        }
    }
}
