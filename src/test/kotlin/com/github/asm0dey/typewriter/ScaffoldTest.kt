package com.github.asm0dey.typewriter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScaffoldTest : TypeWriterFixtureTestCase() {
    @Test
    fun testFixtureStarts() {
        val file = fixture.configureByText(
            "A.java",
            // language="JAVA"
            "class A {}",
        )
        assertEquals("class A {}", file.text)
    }
}
