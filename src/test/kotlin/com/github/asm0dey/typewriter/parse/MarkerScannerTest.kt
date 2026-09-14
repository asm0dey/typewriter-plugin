package com.github.asm0dey.typewriter.parse

import com.intellij.openapi.Disposable
import com.intellij.testFramework.common.ThreadLeakTracker
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase5
import com.intellij.testFramework.junit5.RunInEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// configureByText mutates PSI, so tests need EDT + the write-intent lock.
@RunInEdt(writeIntent = true)
class MarkerScannerTest : LightJavaCodeInsightFixtureTestCase5() {

    // No file-based fixtures are used (only configureByText), so no test data directory exists;
    // the default tries to resolve one against a full intellij-community checkout and throws.
    override fun getRelativePath(): String = ""

    companion object {
        init {
            // Touching the AWT event queue (via @RunInEdt) lazily starts sun.awt.UNIXToolkit's
            // "SystemPropertyWatcher" background thread on Linux, regardless of headless mode.
            // It's a one-time, JVM-lifetime thread the platform's own allowlist doesn't yet know
            // about on this JDK/build, so register it as long-running to avoid a false leak report.
            ThreadLeakTracker.longRunningThreadCreated(Disposable { }, "SystemPropertyWatcher")
        }
    }

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
