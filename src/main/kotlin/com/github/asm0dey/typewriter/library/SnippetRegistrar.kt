package com.github.asm0dey.typewriter.library

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup

/**
 * Dynamic per-snippet action registration (spec section 8, "Actions"). Each snippet's action id
 * is derived from its relative path via [SnippetLibrary.actionId], not from its shortcut -- v1's
 * defect was deriving the id from the shortcut, which orphaned the user's keymap binding every
 * time the shortcut changed. This registrar never touches the keymap: the IDE owns bindings, and
 * [register] only tells [ActionManager] which ids currently exist and keeps them clustered in the
 * [GROUP_ID] group declared in plugin.xml, "so they cluster in the keymap tree" (spec section 8).
 */
object SnippetRegistrar {

    /** Matches `<group id="typewriter.snippets" .../>` in plugin.xml. */
    private const val GROUP_ID = "typewriter.snippets"

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
        val group = manager.getAction(GROUP_ID) as? DefaultActionGroup
        val wanted = relativePaths.map { SnippetLibrary.actionId(it) }.toSet()

        for (id in registered - wanted) {
            val action = manager.getAction(id)
            if (action != null) {
                group?.remove(action)
                manager.unregisterAction(id)
            }
        }
        registered.retainAll(wanted)

        for (path in relativePaths) {
            val id = SnippetLibrary.actionId(path)
            if (registered.contains(id)) continue
            if (manager.getAction(id) == null) {
                val action = TypeSnippetAction(path)
                manager.registerAction(id, action)
                group?.add(action)
            }
            registered += id
        }
    }
}
