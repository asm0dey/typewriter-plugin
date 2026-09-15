package com.github.asm0dey.typewriter.ide

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.github.asm0dey.typewriter.ui.TypeWriterProjectSettings
import com.intellij.codeInsight.daemon.impl.analysis.FileHighlightingSetting
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Spec section 8, "Snippet files in the IDE": snippet files are fragments, often deliberately
 * broken, so [SnippetHighlightingSettingProvider] must suppress the highlighting pass for any file
 * under a configured snippet directory -- but ONLY those files.
 *
 * [testFileOutsideConfiguredDirectoryIsUnaffected] proves discrimination against a REAL, non-empty
 * configured directory rather than passing vacuously because nothing is configured (the pitfall
 * Task 13's review caught in SnippetWatcherTest -- see task-13-report.md, Finding 2).
 * [testSnippetInNestedSubdirectorySkipsHighlighting] and
 * [testNestedSnippetGetsSkipHighlightingThroughRealWiring] prove a snippet nested two levels below
 * the configured root -- not just a direct child -- is still recognised, since snippets nest
 * (talk-slug subfolders, spec section 8 "Directories").
 */
@RunInEdt(writeIntent = true)
class SnippetHighlightingTest : TypeWriterFixtureTestCase() {

    private val savedProjectDirs = mutableListOf<String>()
    private val tempDirs = mutableListOf<Path>()

    @AfterEach
    fun cleanup() {
        val settings = fixture.project.getService(TypeWriterProjectSettings::class.java)
        savedProjectDirs.lastOrNull()?.let { settings.loadState(TypeWriterProjectSettings.State(projectDir = it)) }
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    /** Points the fixture project's snippet dir at a real, VFS-refreshed directory on disk. */
    private fun pointProjectAt(dir: Path): VirtualFile {
        val settings = fixture.project.getService(TypeWriterProjectSettings::class.java)
        savedProjectDirs.add(settings.state.projectDir)
        settings.loadState(TypeWriterProjectSettings.State(projectDir = dir.toString()))
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!
    }

    private fun dir(name: String): VirtualFile = WriteAction.compute<VirtualFile, Exception> {
        fixture.tempDirFixture.findOrCreateDir(name)
    }

    private fun file(parent: VirtualFile, path: String, text: String): VirtualFile =
        WriteAction.compute<VirtualFile, Exception> {
            val segments = path.split("/")
            var target = parent
            for (segment in segments.dropLast(1)) {
                target = target.findChild(segment) ?: target.createChildDirectory(this, segment)
            }
            val created = target.createChildData(this, segments.last())
            created.setBinaryContent(text.toByteArray())
            created
        }

    @Test
    fun testNonSnippetFileGetsNoOpinion() {
        val file = fixture.configureByText("Ordinary.java", "class A {}").virtualFile
        assertNull(SnippetHighlightingSettingProvider().getDefaultSetting(fixture.project, file))
    }

    @Test
    fun testSnippetFileDirectlyUnderRootSkipsHighlighting() {
        val root = dir("hl-direct")
        val snippet = file(root, "01.java", "class A {")
        assertEquals(
            FileHighlightingSetting.SKIP_HIGHLIGHTING,
            SnippetHighlightingSettingProvider().settingFor(snippet, listOf(root)),
        )
    }

    // Snippets nest, so a file two levels below the configured root must still be recognised, not
    // just a direct child.
    @Test
    fun testSnippetInNestedSubdirectorySkipsHighlighting() {
        val root = dir("hl-nested")
        val snippet = file(root, "jcon26/talk/01.java", "class A {")
        assertEquals(
            FileHighlightingSetting.SKIP_HIGHLIGHTING,
            SnippetHighlightingSettingProvider().settingFor(snippet, listOf(root)),
        )
        assertTrue(SnippetFiles.isUnder(snippet, listOf(root)))
    }

    // The central self-review question: does a test prove a file OUTSIDE the snippet directories
    // is left alone, against a REAL, non-empty configured directory (not the vacuous "nothing is
    // configured" case testNonSnippetFileGetsNoOpinion above exercises)? The project is pointed at
    // a real, resolvable snippet directory via TypeWriterProjectSettings, and the file under test
    // lives in a separate, unrelated real directory alongside it.
    @Test
    fun testFileOutsideConfiguredDirectoryIsUnaffected() {
        val configured = Files.createTempDirectory("tw-hl-configured")
        tempDirs.add(configured)
        pointProjectAt(configured)

        val elsewhere = Files.createTempDirectory("tw-hl-elsewhere")
        tempDirs.add(elsewhere)
        val vElsewhere = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(elsewhere)!!
        val outsideFile = WriteAction.compute<VirtualFile, Exception> {
            vElsewhere.createChildData(this, "Outside.java").also {
                it.setBinaryContent("class A {".toByteArray())
            }
        }

        assertNull(
            SnippetHighlightingSettingProvider().getDefaultSetting(fixture.project, outsideFile),
            "a file outside every configured snippet directory must not have its highlighting suppressed",
        )
        assertFalse(SnippetFiles.isSnippet(fixture.project, outsideFile))
    }

    // Proves the full production wiring -- getDefaultSetting -> SnippetFiles.roots(project) ->
    // SnippetDirs.project(project) -- resolves a REAL configured project directory and recognises
    // a snippet nested inside it, not just the settingFor()/explicit-roots unit tests above.
    @Test
    fun testNestedSnippetGetsSkipHighlightingThroughRealWiring() {
        val configured = Files.createTempDirectory("tw-hl-real-nested")
        tempDirs.add(configured)
        val vConfigured = pointProjectAt(configured)
        val nested = WriteAction.compute<VirtualFile, Exception> {
            val sub = vConfigured.createChildDirectory(this, "jcon26")
            sub.createChildData(this, "01.java").also { it.setBinaryContent("class A {".toByteArray()) }
        }

        assertEquals(
            FileHighlightingSetting.SKIP_HIGHLIGHTING,
            SnippetHighlightingSettingProvider().getDefaultSetting(fixture.project, nested),
        )
        assertTrue(SnippetFiles.isSnippet(fixture.project, nested))
    }
}
