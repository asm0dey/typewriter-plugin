package com.github.asm0dey.typewriter.ui

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.github.asm0dey.typewriter.model.Directives
import com.github.asm0dey.typewriter.parse.CommentSyntax
import com.intellij.lang.java.JavaLanguage
import com.intellij.testFramework.junit5.RunInEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun testWriteLeavesTextUnchangedWhenSyntaxHasNoComments() {
        val noComments = CommentSyntax(linePrefix = null, blockPrefix = null, blockSuffix = null)
        val out = DirectiveHeader.write("class A {}", Directives(speedMs = 80), "tw:", noComments)
        assertEquals("class A {}", out)
    }
}

/**
 * [DirectiveHeader.resolveField] is a pure decision over one field's opened/current/control
 * values, so it needs no PSI fixture at all: a test needing no PSI fixture extends nothing (plan
 * "Test conventions"). Each case names the field-level scenario from `resolveField`'s kdoc; the
 * "neither changed, header had nothing" case is the actual regression this exists to prevent --
 * see its comment below.
 */
class DirectiveHeaderResolveFieldTest {

    @Test
    fun testKeepsCurrentWhenNeitherHeaderNorControlChanged() {
        val outcome = DirectiveHeader.resolveField(opened = 80, current = 80, controlChanged = false, controlValue = 80)
        assertEquals(DirectiveHeader.FieldOutcome(80, conflicted = false), outcome)
    }

    @Test
    fun testAbsentFieldStaysAbsentWhenNeitherChanged() {
        // The regression: a spinner always shows *some* number (the app default, here 100) even
        // when the header never had this field at all (opened/current == null). Confirming the
        // outcome is null -- not the spinner's default -- is what proves opening a header-less
        // snippet and clicking OK does not pin that default into the file.
        val outcome = DirectiveHeader.resolveField<Int?>(
            opened = null, current = null, controlChanged = false, controlValue = 100,
        )
        assertEquals(DirectiveHeader.FieldOutcome<Int?>(null, conflicted = false), outcome)
    }

    @Test
    fun testWritesControlValueWhenOnlyControlChanged() {
        val outcome = DirectiveHeader.resolveField(opened = 80, current = 80, controlChanged = true, controlValue = 90)
        assertEquals(DirectiveHeader.FieldOutcome(90, conflicted = false), outcome)
    }

    @Test
    fun testKeepsHeaderEditWhenOnlyHeaderChanged() {
        val outcome = DirectiveHeader.resolveField(opened = 80, current = 90, controlChanged = false, controlValue = 80)
        assertEquals(DirectiveHeader.FieldOutcome(90, conflicted = false), outcome)
    }

    @Test
    fun testConflictsAndPrefersHeaderWhenBothChanged() {
        val outcome = DirectiveHeader.resolveField(opened = 80, current = 90, controlChanged = true, controlValue = 70)
        assertEquals(DirectiveHeader.FieldOutcome(90, conflicted = true), outcome)
    }
}

/**
 * [DirectiveHeader.decideSave] is the pure function behind `SnippetDialog`'s OK button: combining
 * [DirectiveHeader.resolveField] across all four fields, the write gate, and the header-vs-sidecar
 * store routing -- previously all three sat untested inside the `DialogWrapper` itself, which is
 * exactly where two real defects were found by review (a header-less/sidecar-less snippet's
 * spinners pinning app defaults on a bare OK, and the playback path never consulting the
 * sidecar). No PSI fixture needed: a test needing no PSI fixture extends nothing.
 */
class DirectiveHeaderDecideSaveTest {

    private val opened = Directives()
    // Baselines matching `opened`'s absent fields -- exactly what SnippetDialog seeds a spinner
    // with when nothing is stored: `openedDirectives.speedMs ?: settings.state.speedMs`.
    private val appDefaultSpeed = 100
    private val appDefaultJitter = 20
    private val appDefaultNewline = 300

    // The regression this function exists to prevent, at the composed level (see
    // DirectiveHeaderResolveFieldTest.testAbsentFieldStaysAbsentWhenNeitherChanged for the
    // single-field version): nothing was ever stored, the speaker touched nothing, the spinners
    // sit at their app-default seed values -- a bare OK must not need a write at all.
    @Test
    fun testNeedsNoWriteWhenNothingWasStoredAndNothingWasTouched() {
        val decision = DirectiveHeader.decideSave(
            hasCommentSyntax = true,
            opened = opened,
            current = opened,
            controlRaw = false,
            controlSpeedMs = appDefaultSpeed,
            controlJitterMs = appDefaultJitter,
            controlNewlineMs = appDefaultNewline,
            speedBaseline = appDefaultSpeed,
            jitterBaseline = appDefaultJitter,
            newlineBaseline = appDefaultNewline,
        )
        assertEquals(Directives(), decision.toWrite)
        assertFalse(decision.needsWrite)
        assertFalse(decision.conflicted)
    }

    @Test
    fun testNeedsWriteWhenASpinnerMoved() {
        val decision = DirectiveHeader.decideSave(
            hasCommentSyntax = true,
            opened = opened,
            current = opened,
            controlRaw = false,
            controlSpeedMs = 90,
            controlJitterMs = appDefaultJitter,
            controlNewlineMs = appDefaultNewline,
            speedBaseline = appDefaultSpeed,
            jitterBaseline = appDefaultJitter,
            newlineBaseline = appDefaultNewline,
        )
        assertEquals(Directives(speedMs = 90), decision.toWrite)
        assertTrue(decision.needsWrite)
        assertFalse(decision.conflicted)
    }

    @Test
    fun testRoutesToHeaderWhenCommentSyntaxIsPresent() {
        val decision = DirectiveHeader.decideSave(
            hasCommentSyntax = true,
            opened = opened, current = opened,
            controlRaw = false, controlSpeedMs = 90, controlJitterMs = appDefaultJitter, controlNewlineMs = appDefaultNewline,
            speedBaseline = appDefaultSpeed, jitterBaseline = appDefaultJitter, newlineBaseline = appDefaultNewline,
        )
        assertEquals(DirectiveHeader.Store.HEADER, decision.store)
    }

    @Test
    fun testRoutesToSidecarWhenCommentSyntaxIsAbsent() {
        val decision = DirectiveHeader.decideSave(
            hasCommentSyntax = false,
            opened = opened, current = opened,
            controlRaw = false, controlSpeedMs = 90, controlJitterMs = appDefaultJitter, controlNewlineMs = appDefaultNewline,
            speedBaseline = appDefaultSpeed, jitterBaseline = appDefaultJitter, newlineBaseline = appDefaultNewline,
        )
        assertEquals(DirectiveHeader.Store.SIDECAR, decision.store)
    }

    @Test
    fun testConflictedWhenOneFieldWasEditedDirectlyAndItsControlAlsoMoved() {
        val current = Directives(speedMs = 95) // the stored speed changed from opened's null
        val decision = DirectiveHeader.decideSave(
            hasCommentSyntax = true,
            opened = opened,
            current = current,
            controlRaw = false,
            controlSpeedMs = 70, // the spinner also moved, away from its own baseline
            controlJitterMs = appDefaultJitter,
            controlNewlineMs = appDefaultNewline,
            speedBaseline = appDefaultSpeed,
            jitterBaseline = appDefaultJitter,
            newlineBaseline = appDefaultNewline,
        )
        assertTrue(decision.conflicted)
        assertEquals(95, decision.toWrite.speedMs, "the stored value wins a conflict, not the control's")
    }
}
