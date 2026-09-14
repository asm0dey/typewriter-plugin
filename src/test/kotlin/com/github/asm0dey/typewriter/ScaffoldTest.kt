package com.github.asm0dey.typewriter

import com.intellij.testFramework.junit5.RunInEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@RunInEdt(writeIntent = true)
class ScaffoldTest : TypeWriterFixtureTestCase() {
    @Test
    fun testFixtureStarts() {
        val file = fixture.configureByText(
            "A.java",
            // language="JAVA"
            "class A {}",
        )
        // language="JAVA"
        assertEquals("class A {}", file.text)
    }
}
