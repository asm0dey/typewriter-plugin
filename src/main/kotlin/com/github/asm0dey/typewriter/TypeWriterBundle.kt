package com.github.asm0dey.typewriter

import com.intellij.DynamicBundle
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.TypeWriterBundle"

/**
 * Backs plugin.xml's `bundle="messages.TypeWriterBundle"` declarations (the notification group,
 * both settings pages, and every static `<action>`/`<group>` in `<actions>`) -- those are
 * resolved by the platform directly against the properties file on the classpath and never call
 * this class. It exists so any Kotlin code that needs one of the same user-visible strings pulls
 * from the identical bundle instead of duplicating the text inline.
 */
object TypeWriterBundle : DynamicBundle(BUNDLE) {

    @Suppress("SpreadOperator")
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        getMessage(key, *params)

    @Suppress("SpreadOperator", "unused")
    @JvmStatic
    fun messagePointer(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        getLazyMessage(key, *params)
}
