package com.github.asm0dey.typewriter.ui

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.github.asm0dey.typewriter.model.Directives
import com.github.asm0dey.typewriter.parse.CommentSyntax
import com.intellij.lang.java.JavaLanguage
import com.intellij.testFramework.junit5.RunInEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@RunInEdt(writeIntent = true)
class DirectiveHeaderTest : TypeWriterFixtureTestCase() {

    // Deferred: at construction time (this property's naive initializer would run there) the
    // platform test application is not yet up, and CommentSyntax.of's LanguageExtension lookup
    // needs it. By the time a @Test method body runs -- where `java` is first touched -- the
    // EDT/write-intent wrapping around test invocation has already brought the application up.
    private val java by lazy { CommentSyntax.of(JavaLanguage.INSTANCE) }

    @Test
    fun testWriteAddsAHeaderWhenThereIsNone() {
        val out = DirectiveHeader.write("class A {}", Directives(speedMs = 80), "tw:", java)
        assertEquals("// tw: speed 80\nclass A {}", out)
    }

    @Test
    fun testWriteReplacesAnExistingHeader() {
        val out = DirectiveHeader.write("// tw: speed 40\nclass A {}", Directives(speedMs = 80), "tw:", java)
        assertEquals("// tw: speed 80\nclass A {}", out)
    }

    @Test
    fun testWriteRemovesTheHeaderWhenNothingIsSet() {
        val out = DirectiveHeader.write("// tw: speed 40\nclass A {}", Directives(), "tw:", java)
        assertEquals("class A {}", out)
    }

    @Test
    fun testWriteKeepsACommandMarkerThatLeadsTheFile() {
        val src = "// tw: pause 500\nclass A {}"
        val out = DirectiveHeader.write(src, Directives(speedMs = 80), "tw:", java)
        assertEquals("// tw: speed 80\n// tw: pause 500\nclass A {}", out)
    }

    @Test
    fun testRoundTrip() {
        val directives = Directives(raw = true, speedMs = 80, jitterMs = 25, newlineMs = 400)
        val text = DirectiveHeader.write("class A {}", directives, "tw:", java)
        assertEquals(directives, DirectiveHeader.read(text, "tw:"))
    }
}

/**
 * [DirectiveHeader.resolve] is a pure decision over three [Directives] snapshots -- opened,
 * current (from the editor), and spinner -- so it needs no PSI fixture at all: a test needing no
 * PSI fixture extends nothing (plan "Test conventions").
 */
class DirectiveHeaderResolveTest {

    @Test
    fun testResolveWritesSpinnerWhenNeitherChanged() {
        val d = Directives(speedMs = 80)
        assertEquals(DirectiveHeader.Resolution.WRITE_SPINNER, DirectiveHeader.resolve(d, d, d))
    }

    @Test
    fun testResolveWritesSpinnerWhenOnlySpinnerChanged() {
        val opened = Directives(speedMs = 80)
        val spinner = Directives(speedMs = 90)
        assertEquals(DirectiveHeader.Resolution.WRITE_SPINNER, DirectiveHeader.resolve(opened, opened, spinner))
    }

    @Test
    fun testResolveKeepsEditorWhenOnlyHeaderChanged() {
        val opened = Directives(speedMs = 80)
        val current = Directives(speedMs = 90)
        assertEquals(DirectiveHeader.Resolution.KEEP_EDITOR, DirectiveHeader.resolve(opened, current, opened))
    }

    @Test
    fun testResolveConflictsWhenBothChanged() {
        val opened = Directives(speedMs = 80)
        val current = Directives(speedMs = 90)
        val spinner = Directives(speedMs = 70)
        assertEquals(
            DirectiveHeader.Resolution.CONFLICT_PREFER_EDITOR,
            DirectiveHeader.resolve(opened, current, spinner),
        )
    }
}
