package com.github.asm0dey.typewriter.ide

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.github.asm0dey.typewriter.ui.TypeWriterProjectSettings
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.junit5.RunInEdt
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Task 15, spec section 9 "Command completion": [MarkerCompletionContributor] offers command and
 * directive names, and action ids, only inside a marker (a `PsiComment` whose body starts with the
 * configured sentinel) in a file under a configured snippet directory.
 *
 * The first four tests exercise [completionsFor] directly -- the pure
 * per-position logic the brief specifies verbatim. They say nothing about the two guards
 * ([SnippetFiles.isSnippet] and the `PsiComment` check) in [MarkerCompletionContributor.fillCompletionVariants]
 * itself, since that method is never invoked. The remaining tests close that gap by driving real
 * completion ([org.junit.jupiter.api.extension] fixture, [com.intellij.testFramework.fixtures.CodeInsightTestFixture.completeBasic])
 * against files that vary exactly one guard at a time, so deleting either guard would fail one of
 * them.
 */
@RunInEdt(writeIntent = true)
class MarkerCompletionTest : TypeWriterFixtureTestCase() {

    // ---- Step 1's cases: the pure completionsFor(body, sentinel) helper. ----

    @Test
    fun testCommandNamesAreOfferedAfterTheSentinel() {
        val names = completionsFor("tw: ", "tw:")
        assertTrue(names.containsAll(listOf("pause", "action", "raw", "speed", "jitter", "newline")))
    }

    @Test
    fun testActionIdsAreOfferedAfterAction() {
        val ids = completionsFor("tw: action ", "tw:")
        assertTrue(ids.contains("ReformatCode"))
        assertTrue(ids.size > 100)
    }

    @Test
    fun testNumericArgumentsGetNoCompletions() {
        assertTrue(completionsFor("tw: pause ", "tw:").isEmpty())
        assertTrue(completionsFor("tw: speed ", "tw:").isEmpty())
    }

    @Test
    fun testNonMarkerCommentGetsNoCompletions() {
        assertTrue(completionsFor("just a note", "tw:").isEmpty())
    }

    // ---- Real-invocation guard tests: prove fillCompletionVariants' two guards actually fire. ----

    private val savedProjectDirs = mutableListOf<String>()
    private val tempDirs = mutableListOf<Path>()

    @AfterEach
    fun cleanup() {
        val settings = fixture.project.getService(TypeWriterProjectSettings::class.java)
        savedProjectDirs.lastOrNull()?.let { settings.loadState(TypeWriterProjectSettings.State(projectDir = it)) }
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    /**
     * Points the fixture project's snippet dir at a real, LocalFileSystem-backed directory --
     * SnippetDirs.project resolves through LocalFileSystem.findFileByNioFile, which cannot see
     * fixture.tempDirFixture's in-memory temp:// files (same reasoning as SnippetRunnerTest and
     * SnippetHighlightingTest). An absolute path here makes Path.resolve(relative) in
     * SnippetDirs.projectPath return this directory regardless of the light fixture project's own
     * basePath.
     */
    private fun pointProjectAt(dir: Path) {
        val settings = fixture.project.getService(TypeWriterProjectSettings::class.java)
        savedProjectDirs.add(settings.state.projectDir)
        settings.loadState(TypeWriterProjectSettings.State(projectDir = dir.toString()))
    }

    /** Writes [text] (which may contain a `<caret>` marker) to a real file and opens it in the fixture editor. */
    private fun openRealFile(dir: Path, name: String, text: String) {
        val path = dir.resolve(name)
        Files.writeString(path, text)
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        fixture.configureFromExistingVirtualFile(vFile)
    }

    private fun completionStrings(): List<String> {
        fixture.completeBasic()
        return fixture.lookupElementStrings ?: emptyList()
    }

    // Positive case: caret inside a marker's body, in a file under the configured snippet
    // directory. If either guard in fillCompletionVariants were wrongly inverted, this would fail
    // alongside the negative cases below -- it is the control that proves the contributor can fire
    // at all under real IntelliJ completion machinery, not just through the pure helper above.
    @Test
    fun testCompletionFiresInsideAMarkerInASnippetFile() {
        val dir = Files.createTempDirectory("tw-cmp-positive")
        tempDirs.add(dir)
        pointProjectAt(dir)

        openRealFile(dir, "01.java", "// tw: <caret>")

        val names = completionStrings()
        assertTrue(
            names.containsAll(listOf("pause", "action", "raw", "speed", "jitter", "newline")),
            "expected command/directive names inside a marker in a snippet file, got $names",
        )
    }

    // Spec section 9's table: action ids are "presented with the action's own text and icon", not
    // a bare id. Drives real completion (rather than the pure completionsFor helper, which only
    // returns names) so the actual LookupElement presentation can be inspected.
    //
    // Does not pin down any single action id: the platform's own completion machinery arranges
    // and can truncate a 2000+-entry candidate list (there are ~2750 registered action ids in
    // this test sandbox) before it reaches the caller, so which ids survive is not stable across
    // environments. What must hold regardless is that at least one of whatever *does* come back
    // carries the enrichment -- if fillCompletionVariants regressed to a bare
    // LookupElementBuilder.create(name) for every action id, none of them would.
    @Test
    fun testActionIdCompletionsCarryTheActionsTextAndIcon() {
        val dir = Files.createTempDirectory("tw-cmp-action-presentation")
        tempDirs.add(dir)
        pointProjectAt(dir)

        openRealFile(dir, "01.java", "// tw: action <caret>")

        val elements = fixture.completeBasic() ?: emptyArray()
        assertTrue(elements.isNotEmpty(), "expected action id completions to be offered")
        val presentations = elements.map { LookupElementPresentation.renderElement(it) }
        assertTrue(
            presentations.any { it.icon != null },
            "expected at least one action id completion to carry the action's icon",
        )
        assertTrue(
            presentations.any { it.typeText != null },
            "expected at least one action id completion to carry the action's own text",
        )
    }

    // Guard 1 (PsiComment check): same snippet file, but the caret sits in ordinary code, not
    // inside any comment at all. If the PsiComment guard were deleted or loosened to "any comment
    // in the file" rather than "the comment the caret is actually inside", fillCompletionVariants
    // could either crash on a null comment or spuriously pick up an unrelated marker elsewhere in
    // the file -- so the file deliberately carries a real, in-progress marker comment ("// tw: ")
    // ahead of the caret, and the caret itself sits in ordinary code well outside it. Verified by
    // mutation: falling back to "the first comment anywhere in the file" when the caret isn't
    // inside one made this test fail (the offset gets clamped into that unrelated comment, whose
    // body is itself a bare, unfinished marker that legitimately offers every command name) while
    // a file with no comment at all would not catch that same mutation, since there would be
    // nothing to fall back to.
    @Test
    fun testCompletionDoesNotFireOutsideACommentInASnippetFile() {
        val dir = Files.createTempDirectory("tw-cmp-outside-comment")
        tempDirs.add(dir)
        pointProjectAt(dir)

        openRealFile(dir, "01.java", "// tw: \nclass A { <caret> }")

        val names = completionStrings()
        assertFalse(names.contains("pause"), "must not offer marker completions outside any comment: $names")
    }

    // Guard 1, other half: caret inside a real comment in a snippet file, but the comment's body
    // does not start with the sentinel -- an ordinary comment, not a marker. Proves the sentinel
    // check inside completionsFor is actually reached from fillCompletionVariants, not only
    // exercised in isolation by testNonMarkerCommentGetsNoCompletions above.
    //
    // The body is deliberately empty (just "// " with the caret right after the trailing space),
    // not a multi-word sentence like "not a marker": completionsFor's own `rest.isEmpty()` branch
    // offers the command names whenever the sentinel check is skipped, so this is what actually
    // exercises that check. Verified by mutation: temporarily short-circuiting the sentinel check
    // in completionsFor made this test fail while a multi-word body ("not a marker") kept passing
    // regardless -- multi-word bodies fall through every branch of the `when` on word count alone
    // and never reach the sentinel check at all. A single trailing word (no space before the
    // caret) has the same masking problem from a different angle: IntelliJ's own completion prefix
    // matcher filters "pause" etc. out because it does not start with that word, independent of
    // completionsFor's return value. An empty body sidesteps both: the caret's prefix is empty (no
    // filtering) and completionsFor's `rest.isEmpty()` branch is exactly what the sentinel check
    // guards.
    @Test
    fun testCompletionDoesNotFireInsideAnOrdinaryCommentInASnippetFile() {
        val dir = Files.createTempDirectory("tw-cmp-ordinary-comment")
        tempDirs.add(dir)
        pointProjectAt(dir)

        openRealFile(dir, "01.java", "// <caret>")

        val names = completionStrings()
        assertFalse(names.contains("pause"), "must not offer marker completions inside a non-sentinel comment: $names")
    }

    // Guard 2 (SnippetFiles.isSnippet): a marker-shaped comment, caret in the right place, but the
    // file is NOT under any configured snippet directory (the project snippet dir is left at its
    // default, which resolves nowhere near this temp file). Proves the contributor does not fire
    // in ordinary project code just because a comment happens to look like a marker.
    @Test
    fun testCompletionDoesNotFireInsideAMarkerInANonSnippetFile() {
        val dir = Files.createTempDirectory("tw-cmp-non-snippet")
        tempDirs.add(dir)
        // Deliberately not calling pointProjectAt(dir): this directory is never configured as a
        // snippet root, so SnippetFiles.isSnippet must return false for a file inside it.

        openRealFile(dir, "Ordinary.java", "// tw: <caret>")

        val names = completionStrings()
        assertFalse(names.contains("pause"), "must not offer marker completions in a non-snippet file: $names")
    }
}
