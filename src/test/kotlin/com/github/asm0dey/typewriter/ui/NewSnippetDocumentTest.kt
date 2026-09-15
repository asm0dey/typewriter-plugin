package com.github.asm0dey.typewriter.ui

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.junit5.RunInEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The document [SnippetDialog] authors a NEW snippet in, before any file exists.
 *
 * Reported from a real session: "reformatting doesn't work in new snippet dialog". The cause was
 * `EditorFactory.createDocument`, which produces a document with no PSI behind it -- and every
 * language feature in that editor is a function of PSI, so Reformat Code silently did nothing.
 * Editing an EXISTING snippet was unaffected, because that document belongs to a real file.
 */
@RunInEdt(writeIntent = true)
class NewSnippetDocumentTest : TypeWriterFixtureTestCase() {

    @Test
    fun testTheNewSnippetDocumentHasAPsiFileBehindIt() {
        val document = newSnippetDocument(fixture.project, JavaFileType.INSTANCE, "class A {}")
        val psi = PsiDocumentManager.getInstance(fixture.project).getPsiFile(document)
        assertNotNull(psi, "no PsiFile means no reformat, no completion and no inspections")
        assertEquals(JavaFileType.INSTANCE, psi!!.fileType, "the PsiFile must speak the chosen language")
    }

    // The reported symptom itself: run the very action that did nothing, and require it to change
    // the text. This fails outright on an EditorFactory document, where getPsiFile returns null.
    @Test
    fun testReformatActuallyRestructuresTheNewSnippetDocument() {
        val document = newSnippetDocument(fixture.project, JavaFileType.INSTANCE, "class A {int x;   int y;}")
        val psi = checkNotNull(PsiDocumentManager.getInstance(fixture.project).getPsiFile(document))

        WriteCommandAction.runWriteCommandAction(fixture.project) {
            CodeStyleManager.getInstance(fixture.project).reformat(psi)
        }

        assertTrue(
            document.text.contains("\n"),
            "reformat must restructure the one-liner; it was still \"${document.text}\"",
        )
    }

    // Switching the language combo builds a fresh document rather than re-typing the old one, so
    // the PSI matches the language the speaker just chose -- and the text they already wrote
    // survives the switch.
    @Test
    fun testSwitchingLanguageKeepsTheTextAndRetypesThePsi() {
        val java = newSnippetDocument(fixture.project, JavaFileType.INSTANCE, "class A {}")
        val asText = newSnippetDocument(fixture.project, com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE, java.text)

        assertEquals("class A {}", asText.text, "the text carries over unchanged")
        val psi = checkNotNull(PsiDocumentManager.getInstance(fixture.project).getPsiFile(asText))
        assertEquals(
            com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE,
            psi.fileType,
            "the PSI must follow the newly chosen language, not the previous one",
        )
    }
}
