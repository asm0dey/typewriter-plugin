package com.github.asm0dey.typewriterplugin

import com.github.asm0dey.typewriterplugin.TypeWriterBundle.message
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

@Suppress("UnstableApiUsage")
class TypeWriterDialog(val project: Project) : DialogWrapper(project) {
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

    override fun show() {
        super.show()
        // Validate shortcut when dialog is shown (in case it's pre-populated)
//        validateShortcut(shortcutField)
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            val shortcutField = textField()
                .label(message("dialog.shortcut"), LabelPosition.LEFT)
                .comment(message("dialog.shortcut.description"))
                .bindText(::shortcut)

            // Add document listener for live validation
            shortcutField.component.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = validateShortcut(shortcutField)
                override fun removeUpdate(e: DocumentEvent) = validateShortcut(shortcutField)
                override fun changedUpdate(e: DocumentEvent) = validateShortcut(shortcutField)
            })
        }

        row {
            // Add validation message label
            shortcutValidationText = label("")
            shortcutValidationText?.applyToComponent {
                isVisible = false
                foreground = java.awt.Color.RED
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
    private fun validateShortcut(shortcutField: Cell<JBTextField>) {
        if (shortcutField.component.text.isBlank()) {
            // Empty shortcut is valid (optional)
            isShortcutValid = true
            shortcutValidationText?.component?.isVisible = false
            return
        }

        // Check for valid modifier keys and key names


        val keyStroke = KeyStroke.getKeyStroke(shortcutField.component.text)
        if (keyStroke == null) {
            // Invalid modifier keys
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
