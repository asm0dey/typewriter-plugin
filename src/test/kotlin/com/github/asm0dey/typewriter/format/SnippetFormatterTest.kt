package com.github.asm0dey.typewriter.format

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.intellij.ide.highlighter.JavaFileType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SnippetFormatterTest : TypeWriterFixtureTestCase() {

    private fun format(text: String) =
        SnippetFormatter.format(fixture.project, JavaFileType.INSTANCE, "S.java", text)

    @Test
    fun testFixesIndentation() {
        val out = format(
            // language="JAVA"
            """
            |class A {
            |int x;
            |        void m() {
            |int y = 1;
            |}
            |}
            """.trimMargin()
        )
        assertEquals(
            // language="JAVA"
            """
            |class A {
            |    int x;
            |    void m() {
            |        int y = 1;
            |    }
            |}
            """.trimMargin(),
            out.text,
        )
        assertNull(out.warning)
    }

    @Test
    fun testRemovesFormatterInsertedBlankLine() {
        // The formatter wants a blank line between members; the original had none.
        val out = format(
            // language="JAVA"
            """
            |class A {
            |    int x;
            |    void m() {
            |    }
            |}
            """.trimMargin()
        )
        assertEquals(5, out.text.lines().size)
        assertFalse(out.text.contains("\n\n"), "no blank line may be introduced")
    }

    @Test
    fun testStatementFragmentKeepsItsTwoLines() {
        val out = format(
            // language="JAVA"
            """
            |int x = 1;
            |foo(x);
            """.trimMargin()
        )
        assertEquals(listOf("int x = 1;", "foo(x);"), out.text.lines())
    }

    @Test
    fun testFragmentDedentsToColumnZero() {
        val out = format("    private final Repo repo;")
        assertEquals("private final Repo repo;", out.text)
    }

    @Test
    fun testBlankLinesAuthoredByTheUserSurvive() {
        val out = format(
            // language="JAVA"
            """
            |class A {
            |
            |    int x;
            |
            |    int y;
            |}
            """.trimMargin()
        )
        assertEquals(6, out.text.lines().size)
    }

    @Test
    fun testUnbalancedFragmentIsNotDamaged() {
        val src =
            // language="JAVA"
            """
            |public class S {
            |    private final int x = 1;
            |
            """.trimMargin()
        val out = format(src)
        assertEquals(
            src.filterNot { it.isWhitespace() },
            out.text.filterNot { it.isWhitespace() },
        )
    }
}
