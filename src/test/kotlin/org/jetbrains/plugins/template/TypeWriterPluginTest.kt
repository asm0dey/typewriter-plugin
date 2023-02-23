package org.jetbrains.plugins.template

import com.github.asm0dey.typewriterplugin.TypeWriterAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TypeWriterPluginTest : BasePlatformTestCase() {

    fun testDummy() {
        assertTrue(true)
    }

    fun test2(){
        TypeWriterAction().actionPerformed(AnActionEvent(null, DataContext.EMPTY_CONTEXT, "", Presentation(), ActionManager.getInstance(), 0))
    }
}
