package com.github.asm0dey.typewriterplugin

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Service for storing and retrieving snippets with their shortcuts.
 */
@Service(Service.Level.APP)
@State(
    name = "com.github.asm0dey.typewriterplugin.SnippetStorage",
    storages = [Storage("typewriter-snippets.xml")]
)
class SnippetStorage : PersistentStateComponent<SnippetStorage> {
    var snippets: MutableList<Snippet> = mutableListOf()

    override fun getState(): SnippetStorage = this

    override fun loadState(state: SnippetStorage) {
        XmlSerializerUtil.copyBean(state, this)
    }

    fun addSnippet(snippet: Snippet) {
        // Remove any existing snippet with the same shortcut
        snippets.removeIf { it.shortcut == snippet.shortcut }
        snippets.add(snippet)
    }

    fun removeSnippet(shortcut: String) {
        snippets.removeIf { it.shortcut == shortcut }
    }

    fun getSnippet(shortcut: String): Snippet? {
        return snippets.find { it.shortcut == shortcut }
    }

    fun getAllSnippets(): List<Snippet> {
        return snippets.toList()
    }
}

/**
 * Represents a snippet with its shortcut and other properties.
 */
data class Snippet(
    var shortcut: String = "",
    var text: String = "",
    var delay: Int = TypeWriterConstants.defaultDelay,
    var jitter: Int = 0,
    var openingSequence: String = "<",
    var closingSequence: String = ">"
)