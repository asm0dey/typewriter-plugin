package com.github.asm0dey.typewriter.ui

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.github.asm0dey.typewriter.library.SnippetDirs
import com.github.asm0dey.typewriter.library.SnippetLibrary
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.junit5.RunInEdt
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Resolves FileTypeManager/ActionManager-backed singletons through the real platform service
// registry, same as TypeWriterSettingsTest/SnippetRegistrarTest -- fixture setup/teardown for
// LightJavaCodeInsightFixtureTestCase5 needs EDT + the write-intent lock. NewSnippetAction.create
// runs synchronously (no coroutine hand-off, unlike SnippetRunner.run), so calling it directly
// under this annotation carries none of RunServiceTest's runBlocking-vs-EDT deadlock risk.
@RunInEdt(writeIntent = true)
class SnippetFileNameTest : TypeWriterFixtureTestCase() {

    /**
     * Docker's file type, or `null` with a loud skip if the Docker plugin is somehow absent from
     * the test classpath. Docker is **not** an exact-only type (spec question 23) -- it carries an
     * `ExtensionFileNameMatcher("dockerfile")` alongside its nine exact matchers -- so it exercises
     * the "has an extension, exact-name matchers are consulted but not used for naming" branches.
     */
    private fun dockerfileOrSkip(): FileType? {
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName("Dockerfile")
        // An unassociated name resolves to UnknownFileType.INSTANCE on this platform build, not
        // PlainTextFileType.INSTANCE -- established by a throwaway debug run before writing this
        // check (see the task report). Both are treated as "not associated" so a genuinely
        // unassociated name can never silently fall through into an assertion below.
        if (fileType == PlainTextFileType.INSTANCE || fileType == UnknownFileType.INSTANCE) {
            println("SKIPPED: Docker plugin absent, Dockerfile file type not exercised")
            return null
        }
        return fileType
    }

    /**
     * EditorConfig's file type, or `null` with a loud skip if the EditorConfig plugin is absent.
     * EditorConfig IS exact-only (spec question 23) -- a single `ExactFileNameMatcher(".editorconfig")`
     * and no extension matcher at all, confirmed by printing its associations from a throwaway
     * debug run -- so it exercises the stem-as-directory branch. Deliberately not Dockerfile: an
     * earlier version of this task sent Dockerfile down this branch on an unverified assumption,
     * which printing its associations disproved (see relativePath's kdoc).
     */
    private fun editorConfigOrSkip(): FileType? {
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(".editorconfig")
        if (fileType == PlainTextFileType.INSTANCE || fileType == UnknownFileType.INSTANCE) {
            println("SKIPPED: EditorConfig plugin absent, exact-only file type not exercised")
            return null
        }
        return fileType
    }

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

    // extensionOf's central trap: FileType.getDefaultExtension() is empty for Docker's file type
    // despite it genuinely resolving files by extension. Trusting defaultExtension alone (the
    // original implementation) would wrongly treat Docker as exact-only.
    @Test
    fun testExtensionOfReadsDockersExtensionMatcherDespiteAnEmptyDefaultExtension() {
        val dockerfile = dockerfileOrSkip() ?: return
        assertEquals("", dockerfile.defaultExtension, "defaultExtension is the trap, not the answer")
        assertEquals("dockerfile", SnippetFileNames.extensionOf(dockerfile))
    }

    // The inverse trap: EditorConfig's defaultExtension is "editorconfig" -- non-empty -- despite
    // it registering NO ExtensionFileNameMatcher at all. Trusting a non-empty defaultExtension as
    // "this type has an extension" would wrongly suggest "01-setup.editorconfig", a name that does
    // not actually resolve back to EditorConfig.
    @Test
    fun testExtensionOfReturnsNullForEditorConfigDespiteANonEmptyDefaultExtension() {
        val editorconfig = editorConfigOrSkip() ?: return
        assertEquals("editorconfig", editorconfig.defaultExtension, "defaultExtension is the trap, not the answer")
        assertEquals(null, SnippetFileNames.extensionOf(editorconfig))
    }

    // Plain Text registers TWO ExtensionFileNameMatchers, "*.txt" and "*.log" -- confirmed by a
    // throwaway debug run. A naive shortest-then-alphabetical tie-break would pick "log" (same
    // length as "txt", but alphabetically first), silently breaking testPlainTextFallsBackToTheDefaultExtension.
    // extensionOf must prefer the one matching FileType.getDefaultExtension() when there is more
    // than one candidate.
    @Test
    fun testExtensionOfPrefersDefaultExtensionAmongSeveralCandidates() {
        assertEquals("txt", SnippetFileNames.extensionOf(PlainTextFileType.INSTANCE))
    }

    // Fix-round regression test. Docker's file type registers NINE exact-name matchers (see
    // exactNameOf's kdoc for the full observed list: Dockerfile, Containerfile, and seven dotted
    // build-target variants). The original `.firstOrNull()` implementation was non-deterministic
    // over that list -- it happened to return "Dockerfile" until Task 18 added the Docker plugin
    // to the test classpath, at which point it started returning "Dockerfile.native" instead. This
    // pins exactNameOf directly to the canonical, deterministic choice. (Docker itself now takes
    // the extension branch in suggestName/relativePath -- see below -- so this exercises
    // exactNameOf's own determinism in isolation, independent of whether anything downstream
    // actually consults it for Docker.)
    @Test
    fun testExactNameOfPicksTheCanonicalNameAmongSeveralExactMatchers() {
        val dockerfile = dockerfileOrSkip() ?: return
        assertEquals("Dockerfile", SnippetFileNames.exactNameOf(dockerfile))
    }

    // The corrected spec question 23 rule: Docker is NOT exact-only (it has *.dockerfile), so
    // suggestName must use the stem-plus-extension form, exactly like Java -- never the bare exact
    // name. This is the regression test for the coordinator's corrected premise: an earlier version
    // of this task asserted suggestName(dockerfile, "ignored") == "Dockerfile", which encoded the
    // wrong assumption that Docker was exact-only.
    @Test
    fun testDockerfileUsesItsExtensionNotItsExactNameInSuggestName() {
        val dockerfile = dockerfileOrSkip() ?: return
        assertEquals("01-build.dockerfile", SnippetFileNames.suggestName(dockerfile, "01-build"))
    }

    // EditorConfig has no extension matcher at all, so suggestName falls to exactNameOf and
    // ignores the stem entirely -- the genuinely exact-only case.
    @Test
    fun testExactOnlyTypeIgnoresTheStemInSuggestName() {
        val editorconfig = editorConfigOrSkip() ?: return
        assertEquals(".editorconfig", SnippetFileNames.suggestName(editorconfig, "ignored"))
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
    // that guard lives in NewSnippetDialog.doValidate(), which now blocks OK on a blank stem for
    // every type. suggestName just mechanically appends the extension either way.
    @Test
    fun testEmptyStemMechanicallyGetsJustTheExtension() {
        assertEquals(".java", SnippetFileNames.suggestName(JavaFileType.INSTANCE, ""))
    }

    // relativePath: the pure path-decision function (spec question 23). Extension-based types
    // create a single file at the snippet directory's root -- identical to suggestName.
    @Test
    fun testRelativePathForExtensionBasedTypeIsJustTheFileNameAtTheRoot() {
        assertEquals("01-entity.java", SnippetFileNames.relativePath(JavaFileType.INSTANCE, "01-entity"))
    }

    // The regression this whole fix-round exists for: Docker has an extension matcher, so it
    // must NOT get the stem-as-directory treatment, despite also having exact-name matchers.
    @Test
    fun testRelativePathForDockerfileStaysAtTheRootBecauseItHasAnExtensionMatcher() {
        val dockerfile = dockerfileOrSkip() ?: return
        assertEquals("01-build.dockerfile", SnippetFileNames.relativePath(dockerfile, "01-build"))
    }

    // The genuinely exact-only case: the stem names a directory, the exact name names the file
    // inside it -- exactly spec question 23's own example.
    @Test
    fun testRelativePathForExactOnlyTypePutsTheStemAsADirectory() {
        val editorconfig = editorConfigOrSkip() ?: return
        assertEquals("01-setup/.editorconfig", SnippetFileNames.relativePath(editorconfig, "01-setup"))
    }

    // The chooser must never render a bare FileType's default Object#toString() -- that was a
    // fix-round defect: every combo row showed "com.intellij....@38776938" instead of a name.
    @Test
    fun testLabelShowsDisplayNameWithExtension() {
        assertEquals("Java (.java)", SnippetFileNames.label(JavaFileType.INSTANCE))
    }

    // Docker's label must show its REAL extension (read via extensionOf, not the misleading empty
    // defaultExtension) -- "Dockerfile (.dockerfile)", not a bare "Dockerfile".
    @Test
    fun testLabelShowsDockersRealExtensionDespiteAnEmptyDefaultExtension() {
        val dockerfile = dockerfileOrSkip() ?: return
        assertEquals("Dockerfile (.dockerfile)", SnippetFileNames.label(dockerfile))
    }

    // An exact-only type has no extensionOf, so the label is just its display name -- no dangling
    // empty parentheses.
    @Test
    fun testLabelOmitsParenthesesForAnExactOnlyType() {
        val editorconfig = editorConfigOrSkip() ?: return
        assertEquals(editorconfig.displayName, SnippetFileNames.label(editorconfig))
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

    // The check only this suite can do cheaply, per the coordinator's fix-round request: create
    // BOTH kinds (extension-based and exact-only) through NewSnippetAction's real creation path --
    // duplicate detection, directory creation, VFS sync -- and confirm SnippetLibrary's actual
    // collection and sequencing see the result the way spec section 8 promises: the exact-only
    // snippet's stem-as-directory name sorts it INTO the "01-" numbered sequence, not after it
    // under "E" for EditorConfig. A real directory on disk is required (not the fixture's
    // in-memory temp:// files) because SnippetDirs.project resolves through
    // LocalFileSystem.findFileByNioFile -- same reasoning as SnippetRunnerTest's
    // testTypeNextAdvancesTheCursorAndClampsAtTheEndWithoutThrowing.
    @Test
    fun testCreateBothKindsThroughTheRealActionPathAndSequenceThemCorrectly() {
        val editorconfig = editorConfigOrSkip() ?: return

        val realDir = Files.createTempDirectory("tw-newsnippet")
        val projectSettings = fixture.project.getService(TypeWriterProjectSettings::class.java)
        val savedProjectDir = projectSettings.state.projectDir
        try {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(realDir)
            projectSettings.loadState(TypeWriterProjectSettings.State(projectDir = realDir.toString()))

            val action = NewSnippetAction()
            val introPath = SnippetFileNames.relativePath(JavaFileType.INSTANCE, "00-intro")
            val setupPath = SnippetFileNames.relativePath(editorconfig, "01-setup")
            val outroPath = SnippetFileNames.relativePath(JavaFileType.INSTANCE, "02-outro")

            assertNotNull(action.create(fixture.project, introPath))
            assertNotNull(action.create(fixture.project, setupPath))
            assertNotNull(action.create(fixture.project, outroPath))

            val projectDir = SnippetDirs.project(fixture.project)
            assertNotNull(projectDir)
            val paths = SnippetLibrary.sequence(projectDir).map { it.relativePath }
            assertEquals(listOf("00-intro.java", "01-setup/.editorconfig", "02-outro.java"), paths)
        } finally {
            projectSettings.loadState(TypeWriterProjectSettings.State(projectDir = savedProjectDir))
            realDir.toFile().deleteRecursively()
        }
    }

    // A duplicate at the SAME resolved path must be refused -- the pre-check in
    // NewSnippetAction.create must walk relativePath's segments (VfsUtil.findRelativeFile), not
    // just directory.findChild's direct-children check, since an exact-only type's path has a
    // directory segment.
    @Test
    fun testCreateRefusesAnExactDuplicatePath() {
        val editorconfig = editorConfigOrSkip() ?: return

        val realDir = Files.createTempDirectory("tw-dup")
        val projectSettings = fixture.project.getService(TypeWriterProjectSettings::class.java)
        val savedProjectDir = projectSettings.state.projectDir
        try {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(realDir)
            projectSettings.loadState(TypeWriterProjectSettings.State(projectDir = realDir.toString()))

            val action = NewSnippetAction()
            val path = SnippetFileNames.relativePath(editorconfig, "01-setup")

            assertNotNull(action.create(fixture.project, path), "first creation should succeed")
            assertEquals(null, action.create(fixture.project, path), "an exact repeat must be refused")
        } finally {
            projectSettings.loadState(TypeWriterProjectSettings.State(projectDir = savedProjectDir))
            realDir.toFile().deleteRecursively()
        }
    }

    // Two exact-only snippets can legitimately share a stem DIRECTORY as long as they differ in
    // file name -- the directory already existing from the first must not block the second.
    @Test
    fun testCreateSucceedsWhenTheDirectoryExistsButTheFileDoesNot() {
        val editorconfig = editorConfigOrSkip() ?: return

        val realDir = Files.createTempDirectory("tw-shared-dir")
        val projectSettings = fixture.project.getService(TypeWriterProjectSettings::class.java)
        val savedProjectDir = projectSettings.state.projectDir
        try {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(realDir)
            projectSettings.loadState(TypeWriterProjectSettings.State(projectDir = realDir.toString()))

            val action = NewSnippetAction()
            // First snippet creates "01-setup/.editorconfig". A second, unrelated Java snippet
            // manually targeted at "01-setup/other.java" reuses the same directory the first one
            // created -- VfsUtil.createDirectoryIfMissing must find it already there and just add
            // the new file, not fail because the directory exists.
            assertNotNull(action.create(fixture.project, SnippetFileNames.relativePath(editorconfig, "01-setup")))
            assertNotNull(action.create(fixture.project, "01-setup/other.java"))
        } finally {
            projectSettings.loadState(TypeWriterProjectSettings.State(projectDir = savedProjectDir))
            realDir.toFile().deleteRecursively()
        }
    }
}
