package com.github.asm0dey.typewriter.run

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BaseIndentTest : TypeWriterFixtureTestCase() {

    private fun indentAndColumn(host: String): Pair<String, Int> {
        val file = fixture.configureByText("H.java", host)
        val editor = fixture.editor
        val offset = editor.caretModel.offset
        return BaseIndent.compute(fixture.project, file, editor.document, offset) to
            BaseIndent.caretColumn(editor.document, offset)
    }

    @Test
    fun testBlankLineInsideClassBodyUsesTheContextIndent() {
        val (indent, column) = indentAndColumn(
            // language="JAVA"
            """
            |class Host {
            |<caret>
            |}
            """.trimMargin()
        )
        assertEquals(4, indent.length)
        assertEquals(0, column)
    }

    @Test
    fun testCaretAlreadyAtTheContextIndent() {
        val (indent, column) = indentAndColumn(
            // language="JAVA"
            """
            |class Host {
            |    <caret>
            |}
            """.trimMargin()
        )
        assertEquals(4, indent.length)
        assertEquals(4, column)
    }

    @Test
    fun testTwoLevelsDeep() {
        val (indent, _) = indentAndColumn(
            // language="JAVA"
            """
            |class Host {
            |    void m() {
            |<caret>
            |    }
            |}
            """.trimMargin()
        )
        assertEquals(8, indent.length)
    }

    @Test
    fun testMidLineUsesTheCaretColumn() {
        val (indent, column) = indentAndColumn(
            // language="JAVA"
            """
            |class Host {
            |    void m() {
            |        int q = <caret>
            |    }
            |}
            """.trimMargin()
        )
        assertEquals(16, indent.length)
        assertEquals(16, column)
    }

    @Test
    fun testApplyPadsTheFirstLineByTheShortfall() {
        val payload =
            """
            |int a = 1;
            |if (a > 0) {
            |    a++;
            |}
            """.trimMargin()
        val out = BaseIndent.apply(payload, "    ", 0)
        assertEquals(
            """
            |    int a = 1;
            |    if (a > 0) {
            |        a++;
            |    }
            """.trimMargin(),
            out,
        )
    }

    @Test
    fun testApplyDoesNotPadTheFirstLineWhenTheCaretIsAlreadyThere() {
        val payload =
            """
            |int a = 1;
            |if (a > 0) {
            |}
            """.trimMargin()
        val out = BaseIndent.apply(payload, "    ", 4)
        assertEquals(
            """
            |int a = 1;
            |    if (a > 0) {
            |    }
            """.trimMargin(),
            out,
        )
    }

    @Test
    fun testApplyLeavesEmptyLinesEmpty() {
        val payload =
            """
            |a;
            |
            |b;
            """.trimMargin()
        val out = BaseIndent.apply(payload, "  ", 2)
        assertEquals(
            """
            |a;
            |
            |  b;
            """.trimMargin(),
            out,
        )
    }
}
