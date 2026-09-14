package com.github.asm0dey.typewriter

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class ScaffoldTest : LightJavaCodeInsightFixtureTestCase() {
    fun testFixtureStarts() {
        val file = myFixture.configureByText("A.java", "class A {}")
        assertEquals("class A {}", file.text)
    }
}
