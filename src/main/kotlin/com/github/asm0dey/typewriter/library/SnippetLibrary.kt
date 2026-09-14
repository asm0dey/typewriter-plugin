package com.github.asm0dey.typewriter.library

import com.github.asm0dey.typewriter.model.Snippet
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor

/**
 * Collects [Snippet]s from the two layered snippet directories (spec section 8: "Directories").
 * The global directory is the speaker's reusable toolkit; the project directory holds one talk's
 * steps and shadows the global directory on relative path.
 */
object SnippetLibrary {

    const val ID_PREFIX = "typewriter.snippet."

    /** Spec section 8, "Actions": the action id is the path relative to the snippet directory. */
    fun actionId(relativePath: String): String = ID_PREFIX + relativePath

    /**
     * The union of both directories, project shadowing global on relative path, sorted by
     * relative path.
     */
    fun collect(globalDir: VirtualFile?, projectDir: VirtualFile?): List<Snippet> {
        val byPath = LinkedHashMap<String, Snippet>()
        globalDir?.let { root -> walk(root, fromProject = false).forEach { byPath[it.relativePath] = it } }
        projectDir?.let { root -> walk(root, fromProject = true).forEach { byPath[it.relativePath] = it } }
        return byPath.values.sortedBy { it.relativePath }
    }

    /**
     * The talk's ordered steps. Project directory only, sorted by relative path (spec section 8,
     * "Sequence": global snippets are utilities, not talk steps).
     */
    fun sequence(projectDir: VirtualFile?): List<Snippet> =
        projectDir?.let { walk(it, fromProject = true).sortedBy { s -> s.relativePath } } ?: emptyList()

    /** Spec section 5, "Authoritative text": the Document, never the bytes on disk. */
    fun textOf(snippet: Snippet): String? =
        FileDocumentManager.getInstance().getDocument(snippet.file)?.text

    /**
     * Collects every non-directory file under [root], recursively, via the platform's sanctioned
     * VFS walk (rather than a hand-rolled recursion over [VirtualFile.getChildren]).
     */
    private fun walk(root: VirtualFile, fromProject: Boolean): List<Snippet> {
        val snippets = mutableListOf<Snippet>()
        VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (!file.isDirectory) {
                    // file is always a descendant of root -- visitChildrenRecursively only ever
                    // reaches this callback for files under the root it was given -- so
                    // getRelativePath can never return null here.
                    val relative = checkNotNull(VfsUtilCore.getRelativePath(file, root))
                    snippets += Snippet(
                        id = actionId(relative),
                        relativePath = relative,
                        file = file,
                        fileType = FileTypeManager.getInstance().getFileTypeByFileName(file.name),
                        fromProject = fromProject,
                    )
                }
                return true
            }
        })
        return snippets
    }
}
