package com.github.asm0dey.typewriter.run

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.model.Timing
import com.intellij.codeInsight.CodeInsightSettings
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Regression cover for design spec section 7, "Independence from the user's typing settings":
 * [Player] inserts via [com.intellij.openapi.editor.Document.insertString], never through
 * [com.intellij.openapi.editor.actionSystem.TypedAction], so the IDE's smart-typing settings have
 * no effect on a `Type` step's output, on or off. Spec section 16 row 17 records this as measured
 * (not assumed) behaviour on Java 2025.3; this class makes that measurement a standing assertion.
 */
class TypingIndependenceTest : TypeWriterFixtureTestCase() {

    // Trailing "\n" is load-bearing: the fixture ends with a newline after the unclosed method
    // body, so it's appended explicitly rather than relying on trimMargin, which would otherwise
    // drop it as a trailing blank line.
    // language="JAVA"
    private val hazard = """
        |class T {
        |    String s = "hi (unbalanced";
        |    char c = 'x';
        |    void m() {
        |        if (a[0] > 1) {
        |            f("}");
        |        }
        |    }
        """.trimMargin() + "\n"

    private fun typeWithFlags(on: Boolean): String {
        val s = CodeInsightSettings.getInstance()
        val saved = booleanArrayOf(
            s.AUTOINSERT_PAIR_BRACKET,
            s.AUTOINSERT_PAIR_QUOTE,
            s.SMART_INDENT_ON_ENTER,
            s.INSERT_BRACE_ON_ENTER,
            s.REFORMAT_BLOCK_ON_RBRACE,
            s.SURROUND_SELECTION_ON_QUOTE_TYPED,
        )
        s.AUTOINSERT_PAIR_BRACKET = on
        s.AUTOINSERT_PAIR_QUOTE = on
        s.SMART_INDENT_ON_ENTER = on
        s.INSERT_BRACE_ON_ENTER = on
        s.REFORMAT_BLOCK_ON_RBRACE = on
        s.SURROUND_SELECTION_ON_QUOTE_TYPED = on
        try {
            fixture.configureByText(if (on) "On.java" else "Off.java", "")
            val editor = fixture.editor
            runBlocking {
                Player(fixture.project, editor, Any()).play(listOf(Step.Type(hazard)), Timing(0, 0, 0))
            }
            return editor.document.text
        } finally {
            s.AUTOINSERT_PAIR_BRACKET = saved[0]
            s.AUTOINSERT_PAIR_QUOTE = saved[1]
            s.SMART_INDENT_ON_ENTER = saved[2]
            s.INSERT_BRACE_ON_ENTER = saved[3]
            s.REFORMAT_BLOCK_ON_RBRACE = saved[4]
            s.SURROUND_SELECTION_ON_QUOTE_TYPED = saved[5]
        }
    }

    @Test
    fun smartTypingFlagsDoNotAffectOutput() {
        val on = typeWithFlags(true)
        val off = typeWithFlags(false)
        assertEquals(hazard, on, "flags ON must not alter output")
        assertEquals(hazard, off, "flags OFF must not alter output")
        assertEquals(on, off)
    }

    @Test
    fun fragmentLandsAtBaseIndentInsideAnExistingBody() {
        fixture.configureByText(
            "N.java",
            // language="JAVA"
            """
            |class Host {
            |<caret>
            |}
            """.trimMargin(),
        )
        val editor = fixture.editor
        val offset = editor.caretModel.offset
        val indent = BaseIndent.compute(fixture.project, fixture.file, editor.document, offset)
        val column = BaseIndent.caretColumn(editor.document, offset)
        val payload = BaseIndent.apply(
            // language="JAVA"
            """
            |int a = 1;
            |if (a > 0) {
            |    a++;
            |}
            """.trimMargin(),
            indent,
            column,
        )
        runBlocking {
            Player(fixture.project, editor, Any()).play(listOf(Step.Type(payload)), Timing(0, 0, 0))
        }
        assertEquals(
            // language="JAVA"
            """
            |class Host {
            |    int a = 1;
            |    if (a > 0) {
            |        a++;
            |    }
            |}
            """.trimMargin(),
            editor.document.text,
        )
    }

    /**
     * Liveness proof, required by the task brief's "the thing that makes or breaks this task":
     * a settings-independence test is worthless if the setting was never actually in force here.
     *
     * `fixture.type(Char)` drives `TypedAction.actionPerformed` -- the real typed-handler chain
     * the player deliberately bypasses (verified by decompiling `EditorTestFixture.type` for the
     * exact platform version this project targets, 2025.2.6.2: it calls
     * `TypedAction.getInstance().actionPerformed(editor, c, dataContext)`). Typing `(` through
     * that path with `AUTOINSERT_PAIR_BRACKET` on inserts the matching `)`; with it off it does
     * not. That difference is the proof the flag is genuinely live in this fixture -- not a no-op
     * being asserted as a no-op.
     *
     * The second half repeats the identical toggle through [Player.play] and shows the two runs
     * produce identical output despite the flag having just been shown to matter one path over.
     */
    @Test
    fun autoInsertPairBracketIsLiveInThisFixtureButThePlayerIgnoresIt() {
        val s = CodeInsightSettings.getInstance()
        val saved = s.AUTOINSERT_PAIR_BRACKET
        try {
            s.AUTOINSERT_PAIR_BRACKET = true
            // language="JAVA"
            fixture.configureByText("LiveOn.java", "class T {<caret>}")
            fixture.type('(')
            val typedOn = fixture.editor.document.text

            s.AUTOINSERT_PAIR_BRACKET = false
            // language="JAVA"
            fixture.configureByText("LiveOff.java", "class T {<caret>}")
            fixture.type('(')
            val typedOff = fixture.editor.document.text

            assertNotEquals(
                typedOn,
                typedOff,
                "AUTOINSERT_PAIR_BRACKET must be observably live via the normal typing path in this fixture",
            )

            s.AUTOINSERT_PAIR_BRACKET = true
            // language="JAVA"
            fixture.configureByText("PlayerOn.java", "class T {<caret>}")
            var editor = fixture.editor
            runBlocking {
                Player(fixture.project, editor, Any()).play(listOf(Step.Type("(")), Timing(0, 0, 0))
            }
            val playerOn = editor.document.text

            s.AUTOINSERT_PAIR_BRACKET = false
            // language="JAVA"
            fixture.configureByText("PlayerOff.java", "class T {<caret>}")
            editor = fixture.editor
            runBlocking {
                Player(fixture.project, editor, Any()).play(listOf(Step.Type("(")), Timing(0, 0, 0))
            }
            val playerOff = editor.document.text

            assertEquals(playerOn, playerOff, "Player output must not depend on AUTOINSERT_PAIR_BRACKET")
        } finally {
            s.AUTOINSERT_PAIR_BRACKET = saved
        }
    }
}
