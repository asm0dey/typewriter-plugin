package com.github.asm0dey.typewriterplugin

import com.intellij.ui.components.JBTextField
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import java.awt.event.InputEvent

/**
 * A custom text field that intercepts keyboard shortcuts and displays them.
 * Instead of allowing the user to type the shortcut as text, this component
 * captures the actual key presses and converts them to a shortcut representation.
 */
class ShortcutTextField : JBTextField() {
    
    init {
        // Make the field non-editable through normal typing
        isEditable = false
        
        // Add a key listener to capture keyboard shortcuts
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                // Prevent default behavior
                e.consume()
                
                // Skip modifier-only key presses
                if (e.keyCode == KeyEvent.VK_CONTROL || 
                    e.keyCode == KeyEvent.VK_ALT || 
                    e.keyCode == KeyEvent.VK_SHIFT || 
                    e.keyCode == KeyEvent.VK_META) {
                    return
                }
                
                // Create a KeyStroke from the key event
                val keyStroke = KeyStroke.getKeyStrokeForEvent(e)
                
                // Convert the KeyStroke to a string representation
                val shortcutText = keyStrokeToString(keyStroke)
                
                // Update the text field
                text = shortcutText
                
                // Notify document listeners
                fireActionPerformed()
            }
        })
    }
    
    /**
     * Converts a KeyStroke to a string representation that can be parsed by KeyStroke.getKeyStroke()
     */
    private fun keyStrokeToString(keyStroke: KeyStroke): String {
        val modifiers = keyStroke.modifiers
        val keyCode = keyStroke.keyCode
        
        val modifierText = StringBuilder()
        
        // Add modifiers in the order expected by KeyStroke.getKeyStroke()
        if (modifiers and InputEvent.CTRL_DOWN_MASK != 0) {
            modifierText.append("ctrl ")
        }
        if (modifiers and InputEvent.META_DOWN_MASK != 0) {
            modifierText.append("meta ")
        }
        if (modifiers and InputEvent.ALT_DOWN_MASK != 0) {
            modifierText.append("alt ")
        }
        if (modifiers and InputEvent.SHIFT_DOWN_MASK != 0) {
            modifierText.append("shift ")
        }
        
        // Add the key code
        val keyText = KeyEvent.getKeyText(keyCode)
        
        return modifierText.toString() + keyText
    }
    
    /**
     * Clears the current shortcut
     */
    fun clearShortcut() {
        text = ""
    }
}