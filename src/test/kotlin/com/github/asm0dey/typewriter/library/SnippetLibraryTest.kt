package com.github.asm0dey.typewriter.library

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SnippetLibraryTest : TypeWriterFixtureTestCase() {

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
    fun testActionIdIsTheRelativePath() {
        assertEquals("typewriter.snippet.01-entity.kt", SnippetLibrary.actionId("01-entity.kt"))
        assertEquals("typewriter.snippet.jcon26/01.kt", SnippetLibrary.actionId("jcon26/01.kt"))
    }

    @Test
    fun testProjectShadowsGlobalOnTheSameRelativePath() {
        val global = dir("global")
        val local = dir("local")
        file(global, "01.java", "// global")
        file(local, "01.java", "// project")
        val snippets = SnippetLibrary.collect(global, local)
        assertEquals(1, snippets.size)
        assertTrue(snippets[0].fromProject)
    }

    @Test
    fun testGlobalSnippetsRemainAvailableAlongsideProjectOnes() {
        val global = dir("global2")
        val local = dir("local2")
        file(global, "toolkit.java", "// toolkit")
        file(local, "01.java", "// step")
        val paths = SnippetLibrary.collect(global, local).map { it.relativePath }
        assertEquals(listOf("01.java", "toolkit.java"), paths)
    }

    @Test
    fun testSubdirectoriesAreIncludedAndSortedByRelativePath() {
        val local = dir("local3")
        file(local, "jcon26/02.java", "// b")
        file(local, "jcon26/01.java", "// a")
        val paths = SnippetLibrary.collect(null, local).map { it.relativePath }
        assertEquals(listOf("jcon26/01.java", "jcon26/02.java"), paths)
    }

    @Test
    fun testSequenceIsTheProjectDirectoryOnly() {
        val global = dir("global4")
        val local = dir("local4")
        file(global, "toolkit.java", "// toolkit")
        file(local, "01.java", "// step")
        assertEquals(listOf("01.java"), SnippetLibrary.sequence(local).map { it.relativePath })
    }

    // Distinguishes textOf() reading the Document from reading the bytes on disk: the file is
    // written with "// saved" content, then the Document is mutated in memory without saving.
    // If textOf() read the VirtualFile's bytes instead of the Document, this would see the
    // stale "// saved" bytes and fail.
    @Test
    fun testTextComesFromTheDocumentIncludingUnsavedEdits() {
        val local = dir("local5")
        val vf = file(local, "01.java", "// saved")
        val document = FileDocumentManager.getInstance().getDocument(vf)!!
        WriteAction.run<Exception> { document.setText("// unsaved") }
        val snippet = SnippetLibrary.collect(null, local).single()
        assertEquals("// unsaved", SnippetLibrary.textOf(snippet))
    }
}
