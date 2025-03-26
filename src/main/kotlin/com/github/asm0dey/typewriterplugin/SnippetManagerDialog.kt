package com.github.asm0dey.typewriterplugin

import com.github.asm0dey.typewriterplugin.TypeWriterBundle.message
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.components.service
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import javax.swing.KeyStroke
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.table.JBTable
import java.awt.Dimension
import java.util.*
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

class SnippetManagerDialog(private val project: Project) : DialogWrapper(project) {
    private val snippetStorage = service<SnippetStorage>()
    private val tableModel = SnippetTableModel(snippetStorage.getAllSnippets().toMutableList())
    private val table = JBTable(tableModel)

    init {
        title = message("snippet.manager.title")
        init()
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            cell(JBScrollPane(table).apply {
                preferredSize = Dimension(600, 300)
                table.setShowGrid(true)
            })
        }
        row {
            val editButton = button(message("snippet.manager.edit")) {
                editSelectedSnippet()
            }

            val deleteButton = button(message("snippet.manager.delete")) {
                deleteSelectedSnippet()
            }

            // Initially disable buttons if no row is selected
            editButton.component.isEnabled = false
            deleteButton.component.isEnabled = false

            // Add a listener to enable/disable buttons based on selection
            table.selectionModel.addListSelectionListener {
                val selected = table.selectedRow >= 0
                editButton.component.isEnabled = selected
                deleteButton.component.isEnabled = selected
            }
        }
    }

    private fun editSelectedSnippet() {
        val selectedRow = table.selectedRow
        if (selectedRow >= 0) {
            val snippet = tableModel.getSnippetAt(selectedRow)
            val dialog = TypeWriterDialog(project)
            dialog.shortcut = snippet.shortcut
            dialog.text = snippet.text
            dialog.delay = snippet.delay
            dialog.jitter = snippet.jitter
            dialog.openingSequence = snippet.openingSequence
            dialog.closingSequence = snippet.closingSequence

            if (dialog.showAndGet()) {
                val updatedSnippet = Snippet(
                    dialog.shortcut,
                    dialog.text,
                    dialog.delay,
                    dialog.jitter,
                    dialog.openingSequence,
                    dialog.closingSequence
                )

                // If the shortcut has changed, unregister the old one
                if (snippet.shortcut != updatedSnippet.shortcut && snippet.shortcut.isNotBlank()) {
                    // Unregister the action for the old shortcut
                    val oldActionId = "com.github.asm0dey.typewriterplugin.DirectSnippetExecution.${snippet.shortcut}"
                    ActionManager.getInstance().unregisterAction(oldActionId)

                    // Remove the old keyboard shortcut
                    try {
                        val keymap = KeymapManager.getInstance().activeKeymap
                        val shortcuts = keymap.getShortcuts(oldActionId)
                        for (shortcut in shortcuts) {
                            keymap.removeShortcut(oldActionId, shortcut)
                        }
                    } catch (e: Exception) {
                        // Log error or handle any issues
                        println("Failed to remove old shortcut for snippet: ${snippet.shortcut}")
                    }
                }

                snippetStorage.addSnippet(updatedSnippet)
                tableModel.updateSnippet(selectedRow, updatedSnippet)

                // Register the action for this shortcut
                val actionId = "com.github.asm0dey.typewriterplugin.DirectSnippetExecution.${updatedSnippet.shortcut}"
                val action = DirectSnippetExecutionAction(updatedSnippet)
                ActionManager.getInstance().registerAction(actionId, action)

                // Register the keyboard shortcut
                try {
                    // Check for valid modifier keys
                    val validModifiers = setOf("ctrl", "control", "meta", "alt", "shift", "altGraph", "button1", "button2", "button3")
                    val parts = updatedSnippet.shortcut.lowercase(Locale.US).split(" ").map { it.trim() }

                    // Check if all modifiers are valid
                    val modifiers = parts.dropLast(1)
                    val invalidModifiers = modifiers.filter { it !in validModifiers }

                    if (invalidModifiers.isEmpty()) {
                        val keyStroke = KeyStroke.getKeyStroke(updatedSnippet.shortcut)
                        if (keyStroke != null) {
                            val shortcut = KeyboardShortcut(keyStroke, null)
                            val keymap = KeymapManager.getInstance().activeKeymap
                            keymap.addShortcut(actionId, shortcut)
                        }
                    }
                } catch (e: Exception) {
                    // Log error or handle invalid shortcut format
                    println("Failed to register shortcut for snippet: ${updatedSnippet.shortcut}")
                }
            }
        }
    }

    private fun deleteSelectedSnippet() {
        val selectedRow = table.selectedRow
        if (selectedRow >= 0) {
            val snippet = tableModel.getSnippetAt(selectedRow)
            snippetStorage.removeSnippet(snippet.shortcut)
            tableModel.removeSnippet(selectedRow)

            // Unregister the action for this shortcut
            val actionId = "com.github.asm0dey.typewriterplugin.DirectSnippetExecution.${snippet.shortcut}"
            ActionManager.getInstance().unregisterAction(actionId)

            // Remove the keyboard shortcut
            try {
                val keymap = KeymapManager.getInstance().activeKeymap
                val shortcuts = keymap.getShortcuts(actionId)
                for (shortcut in shortcuts) {
                    keymap.removeShortcut(actionId, shortcut)
                }
            } catch (e: Exception) {
                // Log error or handle any issues
                println("Failed to remove shortcut for snippet: ${snippet.shortcut}")
            }
        }
    }

    private class SnippetTableModel(private val snippets: MutableList<Snippet>) : AbstractTableModel() {
        private val columnNames = arrayOf(
            message("snippet.manager.shortcut"),
            message("snippet.manager.text")
        )

        override fun getRowCount(): Int = snippets.size
        override fun getColumnCount(): Int = columnNames.size
        override fun getColumnName(column: Int): String = columnNames[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val snippet = snippets[rowIndex]
            return when (columnIndex) {
                0 -> snippet.shortcut
                1 -> snippet.text.take(50) + if (snippet.text.length > 50) "..." else ""
                else -> ""
            }
        }

        fun getSnippetAt(rowIndex: Int): Snippet = snippets[rowIndex]

        fun updateSnippet(rowIndex: Int, snippet: Snippet) {
            snippets[rowIndex] = snippet
            fireTableRowsUpdated(rowIndex, rowIndex)
        }

        fun removeSnippet(rowIndex: Int) {
            snippets.removeAt(rowIndex)
            fireTableRowsDeleted(rowIndex, rowIndex)
        }
    }
}
