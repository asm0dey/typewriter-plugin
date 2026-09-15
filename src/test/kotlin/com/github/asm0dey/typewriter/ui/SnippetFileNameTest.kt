package com.github.asm0dey.typewriter.ui

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.testFramework.junit5.RunInEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Resolves FileTypeManager/ActionManager-backed singletons through the real platform service
// registry, same as TypeWriterSettingsTest/SnippetRegistrarTest -- fixture setup/teardown for
// LightJavaCodeInsightFixtureTestCase5 needs EDT + the write-intent lock, and nothing here calls
// runBlocking, so there is no deadlock risk in carrying the annotation.
@RunInEdt(writeIntent = true)
class SnippetFileNameTest : TypeWriterFixtureTestCase() {

    @Test
    fun testChoicesExcludeBinaryTypes() {
        val choices = SnippetFileNames.choices()
        assertTrue(choices.isNotEmpty())
        assertTrue(choices.none { it.isBinary })
        assertTrue(choices.contains(JavaFileType.INSTANCE))
    }

    @Test
    fun testExtensionTypeGetsStemPlusExtension() {
        assertEquals("01-entity.java", SnippetFileNames.suggestName(JavaFileType.INSTANCE, "01-entity"))
    }

    @Test
    fun testPlainTextFallsBackToTheDefaultExtension() {
        assertEquals("notes.txt", SnippetFileNames.suggestName(PlainTextFileType.INSTANCE, "notes"))
    }

    @Test
    fun testExactNameTypeIgnoresTheStem() {
        val dockerfile = FileTypeManager.getInstance().getFileTypeByFileName("Dockerfile")
        // Only meaningful when a Docker (or equivalent exact-name) plugin is present; skip loudly
        // otherwise. The brief's sample skip-check compared against PlainTextFileType.INSTANCE,
        // but on this platform build an unassociated name resolves to UnknownFileType.INSTANCE,
        // not PlainTextFileType.INSTANCE -- verified by a throwaway debug run before writing this
        // check. Guarding on PlainTextFileType.INSTANCE alone would let a genuinely-unassociated
        // "Dockerfile" fall through into the assertion below and fail for the wrong reason, so
        // both fallbacks are treated as "not associated" here.
        if (dockerfile == PlainTextFileType.INSTANCE || dockerfile == UnknownFileType.INSTANCE) {
            println("SKIPPED: Docker plugin absent, exact-name matching not exercised")
            return
        }
        assertEquals("Dockerfile", SnippetFileNames.suggestName(dockerfile, "ignored"))
    }

    // Fix-round regression test. Docker's file type registers NINE exact-name matchers (see
    // exactNameOf's kdoc for the full observed list: Dockerfile, Containerfile, and seven dotted
    // build-target variants). The original `.firstOrNull()` implementation was non-deterministic
    // over that list -- it happened to return "Dockerfile" until Task 18 added the Docker plugin
    // to the test classpath, at which point it started returning "Dockerfile.native" instead. This
    // pins exactNameOf directly (not just suggestName's pass-through of it) to the canonical,
    // deterministic choice.
    @Test
    fun testExactNameOfPicksTheCanonicalNameAmongSeveralExactMatchers() {
        val dockerfile = FileTypeManager.getInstance().getFileTypeByFileName("Dockerfile")
        if (dockerfile == PlainTextFileType.INSTANCE || dockerfile == UnknownFileType.INSTANCE) {
            println("SKIPPED: Docker plugin absent, exact-name matching not exercised")
            return
        }
        assertEquals("Dockerfile", SnippetFileNames.exactNameOf(dockerfile))
    }

    // Re-suggesting a name for the type already typed into the stem must not double the
    // extension -- "01-entity.java" chosen with Java again must stay "01-entity.java", not
    // become "01-entity.java.java".
    @Test
    fun testStemAlreadyCarryingTheChosenExtensionIsNotDoubled() {
        assertEquals("01-entity.java", SnippetFileNames.suggestName(JavaFileType.INSTANCE, "01-entity.java"))
    }

    // A stem carrying some OTHER (wrong) extension is not mistaken for the chosen one -- it is
    // left alone, and the chosen type's own extension is still appended last. The file's actual
    // extension (the one FileTypeManager.getFileTypeByFileName resolves on) is always the type
    // the speaker picked in the combo, never shadowed by whatever they typed in the name field.
    @Test
    fun testStemCarryingADifferentExtensionKeepsTheChosenTypeAuthoritative() {
        val suggested = SnippetFileNames.suggestName(JavaFileType.INSTANCE, "01-entity.py")
        assertEquals("01-entity.py.java", suggested)
        assertTrue(suggested.endsWith(".java"))
    }

    // Mechanical behaviour on an empty stem: SnippetFileNames has no opinion about blank names --
    // that guard lives in NewSnippetDialog.doValidate(), which blocks OK on a blank stem for any
    // extension-based type. suggestName just mechanically appends the extension either way.
    @Test
    fun testEmptyStemMechanicallyGetsJustTheExtension() {
        assertEquals(".java", SnippetFileNames.suggestName(JavaFileType.INSTANCE, ""))
    }

    // The chooser must never render a bare FileType's default Object#toString() -- that was the
    // fix-round defect: every combo row showed "com.intellij....@38776938" instead of a name.
    @Test
    fun testLabelShowsDisplayNameWithExtension() {
        assertEquals("Java (.java)", SnippetFileNames.label(JavaFileType.INSTANCE))
    }

    // An exact-name type has no default extension, so the label is just its display name -- no
    // dangling empty parentheses.
    @Test
    fun testLabelOmitsParenthesesWhenTypeHasNoDefaultExtension() {
        val dockerfile = FileTypeManager.getInstance().getFileTypeByFileName("Dockerfile")
        if (dockerfile == PlainTextFileType.INSTANCE || dockerfile == UnknownFileType.INSTANCE) {
            println("SKIPPED: Docker plugin absent, exact-name matching not exercised")
            return
        }
        assertEquals(dockerfile.displayName, SnippetFileNames.label(dockerfile))
    }

    // Fix-round defect 2: the dialog used to open on whatever registeredFileTypes happened to
    // sort first alphabetically (AiIgnore, in a real IDE) instead of anything relevant. The
    // currently-open file's type, when it is one of the offered choices, is the best guess for
    // what the speaker is about to type.
    @Test
    fun testPreselectedPrefersTheCurrentFileTypeWhenItIsAmongTheChoices() {
        val choices = listOf(PlainTextFileType.INSTANCE, JavaFileType.INSTANCE)
        assertEquals(JavaFileType.INSTANCE, SnippetFileNames.preselected(choices, JavaFileType.INSTANCE))
    }

    // No current editor (nothing open, or a brand-new empty project window) falls back to Plain
    // Text rather than leaving the choice to whatever sorts first.
    @Test
    fun testPreselectedFallsBackToPlainTextWhenThereIsNoCurrentFile() {
        val choices = listOf(PlainTextFileType.INSTANCE, JavaFileType.INSTANCE)
        assertEquals(PlainTextFileType.INSTANCE, SnippetFileNames.preselected(choices, null))
    }

    // The currently open file can have a type this dialog does not offer at all (e.g. it is
    // binary, so choices() excludes it) -- same fallback as "no current file".
    @Test
    fun testPreselectedFallsBackToPlainTextWhenTheCurrentFileTypeIsNotOffered() {
        val choices = listOf(PlainTextFileType.INSTANCE, JavaFileType.INSTANCE)
        assertEquals(PlainTextFileType.INSTANCE, SnippetFileNames.preselected(choices, UnknownFileType.INSTANCE))
    }

    // Defends the function's own last-resort branch: even if some future choices() filter change
    // ever dropped Plain Text from the list, preselected() still returns something in that list
    // rather than the hard-coded PlainTextFileType.INSTANCE fallback (which would not be a valid
    // combo selection here).
    @Test
    fun testPreselectedFallsBackToTheFirstChoiceWhenPlainTextIsNotAmongThem() {
        val choices = listOf(JavaFileType.INSTANCE)
        assertEquals(JavaFileType.INSTANCE, SnippetFileNames.preselected(choices, null))
    }
}
