package com.github.asm0dey.typewriter.run

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.github.asm0dey.typewriter.model.Directives
import com.github.asm0dey.typewriter.model.ParseError
import com.github.asm0dey.typewriter.model.Program
import com.github.asm0dey.typewriter.model.Snippet
import com.github.asm0dey.typewriter.model.Step
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.vfs.VirtualFile
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreFlightTest : TypeWriterFixtureTestCase() {

    private fun snippet(name: String = "01.java"): Snippet {
        val dir = WriteAction.compute<VirtualFile, Exception> {
            fixture.tempDirFixture.findOrCreateDir("pf")
        }
        val vf = WriteAction.compute<VirtualFile, Exception> {
            (dir.findChild(name) ?: dir.createChildData(this, name)).also {
                it.setBinaryContent("// x".toByteArray())
            }
        }
        return Snippet("typewriter.snippet.$name", name, vf, JavaFileType.INSTANCE, true)
    }

    private fun program(vararg steps: Step, errors: List<ParseError> = emptyList()) =
        Program(steps.toList(), Directives(), errors)

    @Test
    fun testNoEditorIsAnError() {
        val checks = PreFlight.check(fixture.project, null, snippet(), program(Step.Type("x")), null)
        assertTrue(checks.blocked())
    }

    @Test
    fun testParseErrorsAreErrors() {
        fixture.configureByText("T.java", "<caret>")
        val checks = PreFlight.check(
            fixture.project, fixture.editor, snippet(),
            program(Step.Type("x"), errors = listOf(ParseError(3, "unknown command"))), null,
        )
        assertTrue(checks.blocked())
        assertTrue(checks.any { it is Check.Error && it.message.contains("line 3") })
    }

    @Test
    fun testUnknownActionIdIsAnError() {
        fixture.configureByText("T.java", "<caret>")
        val checks = PreFlight.check(
            fixture.project, fixture.editor, snippet(),
            program(Step.Action("NoSuchActionIdAnywhere")), null,
        )
        assertTrue(checks.blocked())
    }

    @Test
    fun testKnownActionIdIsAccepted() {
        fixture.configureByText("T.java", "<caret>")
        val checks = PreFlight.check(
            fixture.project, fixture.editor, snippet(),
            program(Step.Action("ReformatCode"), Step.Type("x")), null,
        )
        assertFalse(checks.blocked())
    }

    @Test
    fun testEmptyProgramIsAWarningNotAnError() {
        fixture.configureByText("T.java", "<caret>")
        val checks = PreFlight.check(fixture.project, fixture.editor, snippet(), program(), null)
        assertFalse(checks.blocked())
        assertTrue(checks.any { it is Check.Warning })
    }

    @Test
    fun testFormatWarningIsCarriedThrough() {
        fixture.configureByText("T.java", "<caret>")
        val checks = PreFlight.check(
            fixture.project, fixture.editor, snippet(), program(Step.Type("x")), "guard tripped",
        )
        assertFalse(checks.blocked())
        assertTrue(checks.any { it is Check.Warning && it.message.contains("guard tripped") })
    }

    @Test
    fun testTypingIntoTheSnippetsOwnFileIsAnError() {
        val s = snippet("self.java")
        fixture.openFileInEditor(s.file)
        val checks = PreFlight.check(fixture.project, fixture.editor, s, program(Step.Type("x")), null)
        assertTrue(checks.blocked())
    }

    // Spec section 11 "Pre-flight": "No focused editor, read-only file, or guarded region | error".
    // Not exercised by any of the checks above -- every one of them uses a freshly configured,
    // writable document, so this pins the read-only branch on its own.
    @Test
    fun testReadOnlyTargetIsAnError() {
        fixture.configureByText("T.java", "<caret>")
        fixture.editor.document.setReadOnly(true)
        val checks = PreFlight.check(fixture.project, fixture.editor, snippet(), program(Step.Type("x")), null)
        assertTrue(checks.blocked())
        assertTrue(checks.any { it is Check.Error && it.message.contains("read-only") })
    }

    // Spec section 11 "Pre-flight": "Snippet file missing, or has no Document | error". Deleting
    // the snippet's VirtualFile out from under it is the "missing" half of that row; the
    // FileDocumentManager lookup this check relies on returns null for a file that is no longer
    // valid.
    @Test
    fun testSnippetFileWithNoDocumentIsAnError() {
        fixture.configureByText("T.java", "<caret>")
        val s = snippet("gone.java")
        WriteAction.run<Exception> { s.file.delete(this) }
        val checks = PreFlight.check(fixture.project, fixture.editor, s, program(Step.Type("x")), null)
        assertTrue(checks.blocked())
    }

    // Spec section 11 "Pre-flight": "Snippet language != target file language | warn, proceed".
    @Test
    fun testFileTypeMismatchIsAWarningNotAnError() {
        fixture.configureByText("T.java", "<caret>")
        val plainTextSnippet = snippet("01.txt").copy(fileType = PlainTextFileType.INSTANCE)
        val checks = PreFlight.check(
            fixture.project, fixture.editor, plainTextSnippet, program(Step.Type("x")), null,
        )
        assertFalse(checks.blocked())
        assertTrue(checks.any { it is Check.Warning })
    }

    // Spec section 11 "Edges": "Multiple carets: primary only, warn."
    @Test
    fun testMultipleCaretsIsAWarningNotAnError() {
        fixture.configureByText("T.java", "ab<caret>c")
        fixture.editor.caretModel.addCaret(fixture.editor.offsetToVisualPosition(0))
        val checks = PreFlight.check(fixture.project, fixture.editor, snippet(), program(Step.Type("x")), null)
        assertFalse(checks.blocked())
        assertTrue(checks.any { it is Check.Warning && it.message.contains("caret") })
    }
}
