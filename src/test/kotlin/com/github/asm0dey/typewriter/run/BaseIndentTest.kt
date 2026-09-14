package com.github.asm0dey.typewriter.run

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BaseIndentTest : TypeWriterFixtureTestCase() {

    private fun indentAndColumn(host: String, fileName: String = "H.java"): Pair<String, Int> {
        val file = fixture.configureByText(fileName, host)
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
        @Language("JAVA")
        val payload =
            """
            |int a = 1;
            |if (a > 0) {
            |    a++;
            |}
            """.trimMargin()
        val out = BaseIndent.apply(payload, "    ", 0)
        assertEquals(
            // language="JAVA"
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
        @Language("JAVA")
        val payload =
            """
            |int a = 1;
            |if (a > 0) {
            |}
            """.trimMargin()
        val out = BaseIndent.apply(payload, "    ", 4)
        assertEquals(
            // language="JAVA"
            """
            |int a = 1;
            |    if (a > 0) {
            |    }
            """.trimMargin(),
            out,
        )
    }

    // Plain text has no formatter, so getLineIndent returns null (spec section 7); compute
    // must fall back to the caret's own column rather than asking the IDE for a nonexistent
    // notion of "what indentation this context calls for".
    @Test
    fun testNullLineIndentFallsBackToTheCaretColumn() {
        // language="TEXT"
        val (indent, column) = indentAndColumn("if (true) {\n  <caret>\n}", "H.txt")
        assertEquals(2, indent.length)
        assertEquals(2, column)
    }

    @Test
    fun testApplyLeavesEmptyLinesEmpty() {
        @Language("JAVA")
        val payload =
            """
            |a;
            |
            |b;
            """.trimMargin()
        val out = BaseIndent.apply(payload, "  ", 2)
        assertEquals(
            // language="JAVA"
            """
            |a;
            |
            |  b;
            """.trimMargin(),
            out,
        )
    }
}
