package com.github.asm0dey.typewriter.parse

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class MarkerScannerTest : LightJavaCodeInsightFixtureTestCase() {

    private fun scan(text: String) =
        MarkerScanner.scan(myFixture.configureByText("S.java", text), "tw:")

    fun testWholeLineMarker() {
        val markers = scan("class A {\n// tw: pause 800\n    int x;\n}")
        assertEquals(1, markers.size)
        assertEquals(MarkerKind.WHOLE_LINE, markers[0].kind)
        assertEquals("pause 800", markers[0].body.trim())
    }

    fun testTrailingMarker() {
        val markers = scan("class A {\n    int x; // tw: pause 500\n}")
        assertEquals(1, markers.size)
        assertEquals(MarkerKind.TRAILING, markers[0].kind)
    }

    fun testMidLineMarker() {
        val markers = scan("class A {\n    int x = a/* tw: pause 200 */+b;\n}")
        assertEquals(1, markers.size)
        assertEquals(MarkerKind.MID_LINE, markers[0].kind)
    }

    fun testMultiLineBlockMarkerIsWholeLine() {
        val markers = scan("class A {\n/*\ntw: pause 500\ntw: action ReformatCode\n*/\n    int x;\n}")
        assertEquals(1, markers.size)
        assertEquals(MarkerKind.WHOLE_LINE, markers[0].kind)
        assertTrue(markers[0].body.contains("tw: action ReformatCode"))
    }

    fun testOrdinaryCommentIsNotAMarker() {
        assertEquals(0, scan("class A {\n// just a note\n    int x;\n}").size)
    }

    fun testSentinelInsideStringLiteralIsNotAMarker() {
        assertEquals(0, scan("class A {\n    String s = \"// tw: pause 800\";\n}").size)
    }

    fun testEscapedSentinelIsNotAMarker() {
        assertEquals(0, scan("class A {\n// tw:: pause 800\n    int x;\n}").size)
    }
}
