package com.github.asm0dey.typewriterplugin

import com.github.asm0dey.typewriterplugin.TypeWriterBundle.message
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor.RED
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent
import javax.swing.KeyStroke

class TypeWriterDialog(private val project: Project) : DialogWrapper(project) {
    var text = ""
    var delay = TypeWriterConstants.defaultDelay
    var openingSequence = "<"
    var closingSequence = ">"
    var jitter = 0
    var shortcut = ""

    // UI components
    private var shortcutValidationText: Cell<javax.swing.JLabel>? = null
    private var isShortcutValid = true

    init {
        title = message("dialog.title")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            // Create a custom shortcut text field that intercepts keyboard shortcuts
            val shortcutField = cell(ShortcutTextField())
                .label(message("dialog.shortcut"), LabelPosition.LEFT)
                .comment(message("dialog.shortcut.description"))
                .bindText(::shortcut)

            // Add action listener for validation when shortcut changes
            shortcutField.component.addActionListener { validateShortcut(shortcutField) }

            // Add a button to clear the shortcut
            button("Clear") {
                shortcutField.component.clearShortcut()
                validateShortcut(shortcutField)
            }
        }

        row {
            // Add validation message label
            shortcutValidationText = label("")
            shortcutValidationText?.applyToComponent {
                isVisible = false
                foreground = RED
            }
        }
        row {
            textArea()
                .label(message("dialog.text"), LabelPosition.TOP)
                .focused()
                .bindText(::text)
                .let {
                    it.component.rows = 4
                    it.component.columns = 50
                }
        }
        twoColumnsRow({
            intTextField(IntRange(1, 2000), 4)
                .label(message("dialog.delay"))
                .bindIntText(::delay)
                .gap(RightGap.SMALL)
                .let {
                    it.component.text = TypeWriterConstants.defaultDelay.toString()
                }
            @Suppress("DialogTitleCapitalization")
            label(message("dialog.ms"))
        }) {
            intTextField(IntRange(1, 2000), 4)
                .label(message("dialog.jitter"))
                .bindIntText(::jitter)
                .gap(RightGap.SMALL)
                .let {
                    it.component.text = "0"
                }
            @Suppress("DialogTitleCapitalization")
            label(message("dialog.ms"))

        }
        twoColumnsRow({
            textField()
                .label(message("dialog.template.opens"), LabelPosition.TOP)
                .bindText(::openingSequence)
                .let {
                    it.component.text = "<"
                }
        }, {
            textField()
                .label(message("dialog.template.closes"), LabelPosition.TOP)
                .bindText(::closingSequence)
                .let {
                    it.component.text = ">"
                }
        })

        row {
            button(message("dialog.manage.snippets")) {
                openSnippetManager()
            }
        }
    }

    private fun openSnippetManager() {
        val snippetManager = SnippetManagerDialog(project)
        snippetManager.show()
    }

    /**
     * Validates the current shortcut and updates the UI accordingly.
     * Checks if:
     * 1. The shortcut is a valid keystroke (not null)
     * 2. The shortcut contains only valid modifier keys and key names
     * 3. The shortcut doesn't conflict with existing shortcuts
     */
    private fun validateShortcut(shortcutField: Cell<*>) {
        // Cast the component to JBTextField since ShortcutTextField extends it
        val textField = shortcutField.component as JBTextField

        if (textField.text.isBlank()) {
            // Empty shortcut is valid (optional)
            isShortcutValid = true
            shortcutValidationText?.component?.isVisible = false
            return
        }

        // Since the ShortcutTextField already ensures valid keystrokes,
        // we only need to check for conflicts with existing shortcuts
        val keyStroke = KeyStroke.getKeyStroke(textField.text)
        if (keyStroke == null) {
            // This should not happen with ShortcutTextField, but just in case
            isShortcutValid = false
            shortcutValidationText?.component?.text = message("dialog.shortcut.invalid")
            shortcutValidationText?.component?.isVisible = true
            return
        }

        try {

            // Check for conflicts with existing shortcuts
            val keymap = KeymapManager.getInstance().activeKeymap
            val conflicts = keymap.getActionIds(KeyboardShortcut(keyStroke, null))

            if (conflicts.isNotEmpty()) {
                // Shortcut conflicts with existing shortcuts
                isShortcutValid = true // We still allow it, but show a warning
                val conflictingAction = conflicts.firstOrNull() ?: ""
                shortcutValidationText?.component?.text = message("dialog.shortcut.conflict", conflictingAction)
                shortcutValidationText?.component?.isVisible = true
            } else {
                // Valid shortcut with no conflicts
                isShortcutValid = true
                shortcutValidationText?.component?.isVisible = false
            }
        } catch (e: Exception) {
            // Invalid shortcut format
            isShortcutValid = false
            shortcutValidationText?.component?.text = message("dialog.shortcut.invalid")
            shortcutValidationText?.component?.isVisible = true
        }

    }

    override fun isOKActionEnabled(): Boolean {
        return isShortcutValid && super.isOKActionEnabled()
    }
}
