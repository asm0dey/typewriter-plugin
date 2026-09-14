package com.github.asm0dey.typewriter.parse

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.github.asm0dey.typewriter.model.Program
import com.github.asm0dey.typewriter.model.Step
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkerParserTest : TypeWriterFixtureTestCase() {

    private fun parse(text: String): Program {
        val psi = fixture.configureByText("S.java", text)
        return MarkerParser.parse(psi.text, MarkerScanner.scan(psi, "tw:"), "tw:")
    }

    private fun typed(text: String) =
        parse(text).steps.filterIsInstance<Step.Type>().joinToString("") { it.text }

    @Test
    fun testWholeLineMarkerLeavesNoBlankLine() {
        assertEquals(
            // language="JAVA"
            """
            |class A {
            |    int x;
            |}
            """.trimMargin(),
            typed(
                // language="JAVA"
                """
                |class A {
                |// tw: pause 800
                |    int x;
                |}
                """.trimMargin()
            ),
        )
    }

    @Test
    fun testWholeLineMarkerKeepsItsIndentation() {
        assertEquals(
            // language="JAVA"
            """
            |class A {
            |    int x;
            |}
            """.trimMargin(),
            typed(
                // language="JAVA"
                """
                |class A {
                |    // tw: pause 800
                |    int x;
                |}
                """.trimMargin()
            ),
        )
    }

    @Test
    fun testTrailingMarkerDropsSeparatingWhitespaceKeepsNewline() {
        assertEquals(
            // language="JAVA"
            """
            |class A {
            |    int x;
            |}
            """.trimMargin(),
            typed(
                // language="JAVA"
                """
                |class A {
                |    int x;   // tw: pause 500
                |}
                """.trimMargin()
            ),
        )
    }

    @Test
    fun testMidLineMarkerConsumesFollowingWhitespace() {
        assertEquals(
            // language="JAVA"
            """
            |class A {
            |    int y = repo.finding();
            |}
            """.trimMargin(),
            typed(
                // language="JAVA"
                """
                |class A {
                |    int y = repo.fin/* tw: pause 1 */ ding();
                |}
                """.trimMargin()
            ),
        )
    }

    @Test
    fun testMidLineMarkerKeepsPrecedingWhitespace() {
        assertEquals(
            // language="JAVA"
            """
            |class A {
            |    int y = a + b;
            |}
            """.trimMargin(),
            typed(
                // language="JAVA"
                """
                |class A {
                |    int y = a /* tw: pause 1 */ + b;
                |}
                """.trimMargin()
            ),
        )
    }

    @Test
    fun testMultiLineMarkerYieldsSeveralStepsInOrder() {
        val steps = parse(
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
        ).steps
        val commands = steps.filter { it !is Step.Type }
        assertEquals(listOf(Step.Pause(500), Step.Action("ReformatCode")), commands)
    }

    @Test
    fun testEscapedSentinelIsTypedUnescaped() {
        assertEquals(
            // language="JAVA"
            """
            |class A {
            |// tw: pause 800
            |}
            """.trimMargin(),
            typed(
                // language="JAVA"
                """
                |class A {
                |// tw:: pause 800
                |}
                """.trimMargin()
            ),
        )
    }

    @Test
    fun testOrdinaryCommentIsTypedVerbatim() {
        // language="JAVA"
        val src = """
            |class A {
            |// just a note
            |}
        """.trimMargin()
        assertEquals(src, typed(src))
    }

    @Test
    fun testUnknownCommandIsAnError() {
        val program = parse(
            // language="JAVA"
            """
            |class A {
            |// tw: pasue 800
            |}
            """.trimMargin()
        )
        assertEquals(1, program.errors.size)
        assertTrue(program.errors[0].message.contains("pasue"))
    }

    @Test
    fun testBodyLineWithoutSentinelIsAnError() {
        val program = parse(
            // language="JAVA"
            """
            |class A {
            |/*
            |tw: pause 500
            |not a marker line
            |*/
            |}
            """.trimMargin()
        )
        assertEquals(1, program.errors.size)
    }

    @Test
    fun testDirectivesAreCollected() {
        val program = parse(
            // language="JAVA"
            """
            |// tw: raw speed 80 jitter 25 newline 400
            |class A {}
            """.trimMargin()
        )
        assertTrue(program.directives.raw)
        assertEquals(80, program.directives.speedMs)
        assertEquals(25, program.directives.jitterMs)
        assertEquals(400, program.directives.newlineMs)
    }

    @Test
    fun testDirectiveAfterTypedTextIsAnError() {
        val program = parse(
            // language="JAVA"
            // Trailing "\n" is load-bearing: the original fixture ends with a newline
            // after the marker line, so it's appended explicitly rather than relying on
            // trimMargin, which would otherwise drop it as a trailing blank line.
            """
            |class A {}
            |// tw: speed 80
            """.trimMargin() + "\n"
        )
        assertEquals(1, program.errors.size)
        assertTrue(program.errors[0].message.contains("directive"))
    }

    @Test
    fun testOneDirectivePerLineForLanguagesWithoutBlockComments() {
        val program = parse(
            // language="JAVA"
            """
            |// tw: raw
            |// tw: speed 80
            |class A {}
            """.trimMargin()
        )
        assertTrue(program.directives.raw)
        assertEquals(80, program.directives.speedMs)
    }
}
