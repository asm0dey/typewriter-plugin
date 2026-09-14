package com.github.asm0dey.typewriter.parse

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.intellij.testFramework.junit5.RunInEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@RunInEdt(writeIntent = true)
class MarkerScannerTest : TypeWriterFixtureTestCase() {

    private fun scan(text: String) =
        MarkerScanner.scan(fixture.configureByText("S.java", text), "tw:")

    @Test
    fun testWholeLineMarker() {
        val markers = scan(
            // language="JAVA"
            """
            |class A {
            |// tw: pause 800
            |    int x;
            |}
            """.trimMargin()
        )
        assertEquals(1, markers.size)
        assertEquals(MarkerKind.WHOLE_LINE, markers[0].kind)
        assertEquals("tw: pause 800", markers[0].body.trim())
    }

    @Test
    fun testTrailingMarker() {
        val markers = scan(
            // language="JAVA"
            """
            |class A {
            |    int x; // tw: pause 500
            |}
            """.trimMargin()
        )
        assertEquals(1, markers.size)
        assertEquals(MarkerKind.TRAILING, markers[0].kind)
    }

    @Test
    fun testMidLineMarker() {
        val markers = scan(
            // language="JAVA"
            """
            |class A {
            |    int x = a/* tw: pause 200 */+b;
            |}
            """.trimMargin()
        )
        assertEquals(1, markers.size)
        assertEquals(MarkerKind.MID_LINE, markers[0].kind)
    }

    @Test
    fun testMultiLineBlockMarkerIsWholeLine() {
        val markers = scan(
            // language="JAVA"
            """
            |class A {
            |/*
            |tw: pause 500
            |tw: action ReformatCode
            |*/
            |    int x;
            |}
            """.trimMargin()
        )
        assertEquals(1, markers.size)
        assertEquals(MarkerKind.WHOLE_LINE, markers[0].kind)
        assertTrue(markers[0].body.contains("tw: action ReformatCode"))
    }

    @Test
    fun testOrdinaryCommentIsNotAMarker() {
        val markers = scan(
            // language="JAVA"
            """
            |class A {
            |// just a note
            |    int x;
            |}
            """.trimMargin()
        )
        assertEquals(0, markers.size)
    }

    @Test
    fun testSentinelInsideStringLiteralIsNotAMarker() {
        val markers = scan(
            // language="JAVA"
            """
            |class A {
            |    String s = "// tw: pause 800";
            |}
            """.trimMargin()
        )
        assertEquals(0, markers.size)
    }

    @Test
    fun testEscapedSentinelIsNotAMarker() {
        val markers = scan(
            // language="JAVA"
            """
            |class A {
            |// tw:: pause 800
            |    int x;
            |}
            """.trimMargin()
        )
        assertEquals(0, markers.size)
    }
}
