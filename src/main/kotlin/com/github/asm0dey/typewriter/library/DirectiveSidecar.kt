package com.github.asm0dey.typewriter.library

import com.github.asm0dey.typewriter.model.Directives
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * Timing for a snippet whose language has no comment syntax at all (spec section 9, resolved
 * design question 22). A directive header needs a comment to hide inside it --
 * [com.github.asm0dey.typewriter.ui.DirectiveHeader.write] is a no-op for exactly this case, and
 * [com.github.asm0dey.typewriter.parse.MarkerScanner] returns no markers for the same underlying
 * reason -- so such a snippet's timing cannot live in the file at all. It lives instead in a
 * sidecar co-located with the snippet, `<snippetFileName>.twmeta`, one per snippet (not one per
 * directory), in [java.util.Properties] format, holding only the fields that were actually set.
 * [SnippetRunner.run] consults it for exactly the same languages, so playback timing matches what
 * the dialog shows and saves.
 *
 * Consulted **only** when [com.github.asm0dey.typewriter.parse.CommentSyntax.hasAny] is false for
 * a snippet's language -- a comment-capable snippet's header remains the sole source of truth for
 * that snippet, so the header and the sidecar never compete over the same snippet.
 *
 * `java.util.Properties`, not a JSON library: the JDK owns the format outright, so there is no
 * dependency to reason about at all -- in particular no repeat of the Gson problem an earlier
 * version of this file had, where the platform's bundled copy moves out of core and into a
 * modularised library jar on 253+, a plausible `NoClassDefFoundError` this project's
 * `sinceBuild = "252"` with no `untilBuild` cannot rule out. It is also line-oriented, which
 * matters because this file travels in a shared demo repository: two speakers' edits to the same
 * `.twmeta` conflict one line at a time, the way any other line-oriented source file does, rather
 * than as a single JSON structure.
 *
 * One sidecar per snippet, not one per directory, is what makes a corrupt sidecar harmless beyond
 * its own snippet: there is no other snippet's data in the same file for a read-modify-write to
 * put at risk, so [read] degrading a malformed or unreadable `.twmeta` to "no overrides" is
 * correct with no further guard needed -- contrast the one-file-per-directory design this
 * replaced, where the same degradation on the read side of a write was a real hazard (see
 * `git log -- docs/superpowers/specs/2026-09-14-typewriter2-design.md` for that design's own
 * rejection, resolved design question 22).
 *
 * Renames and deletions are not reconciled: a renamed or deleted snippet leaves its `.twmeta`
 * behind, orphaned. Nothing looks up a sidecar except by its owning snippet's own current
 * filename, so an orphan costs nothing beyond a few stale bytes on disk -- deliberately not worth
 * building reconciliation machinery for.
 */
object DirectiveSidecar {

    /**
     * Suffix appended to a snippet's own filename for its sidecar -- `01.txt` -> `01.txt.twmeta` --
     * co-located in the same directory as the snippet, at any nesting depth.
     * [SnippetLibrary.collect]/[SnippetLibrary.sequence] exclude any file whose name ends with this
     * suffix, so a sidecar never registers itself as a playable snippet with its own action -- by
     * extension, not by an exact name, so a sidecar nested in a subdirectory (right next to the
     * snippet it belongs to, since sidecars are per-snippet now) is excluded exactly like one at a
     * directory's root.
     */
    const val SUFFIX = ".twmeta"

    private const val KEY_RAW = "raw"
    private const val KEY_SPEED = "speed"
    private const val KEY_JITTER = "jitter"
    private const val KEY_NEWLINE = "newline"

    /** The sidecar file for [snippetFile], if one currently exists next to it. */
    fun sidecarFileFor(snippetFile: VirtualFile): VirtualFile? =
        snippetFile.parent?.findChild(snippetFile.name + SUFFIX)

    /**
     * [snippetFile]'s directives from its own sidecar, or [Directives()][Directives] (no override)
     * when the sidecar is absent, unreadable, or cannot be parsed as `.properties` text -- a broken
     * or missing sidecar degrades to "no timing overrides", never an error that blocks a demo. Safe
     * by construction (see the class kdoc): a `.twmeta` holds exactly one snippet's own fields, so
     * there is no other snippet's data for a corrupt read to put at risk.
     */
    fun read(snippetFile: VirtualFile): Directives {
        val file = sidecarFileFor(snippetFile) ?: return Directives()
        val text = try {
            String(file.contentsToByteArray(), StandardCharsets.UTF_8)
        } catch (e: IOException) {
            return Directives()
        }
        val props = Properties()
        try {
            props.load(StringReader(text))
        } catch (e: IOException) {
            return Directives()
        } catch (e: IllegalArgumentException) {
            // Properties.load's own documented failure mode: a malformed \uXXXX escape in the
            // file. Everything else -- git merge-conflict markers, arbitrary prose, blank lines --
            // it tolerates as odd-but-harmless keys/values none of the four below ever match.
            return Directives()
        }
        return Directives(
            raw = props.getProperty(KEY_RAW) == "true",
            speedMs = props.getProperty(KEY_SPEED)?.toIntOrNull(),
            jitterMs = props.getProperty(KEY_JITTER)?.toIntOrNull(),
            newlineMs = props.getProperty(KEY_NEWLINE)?.toIntOrNull(),
        )
    }

    /**
     * Writes [directives] into [snippetFile]'s own sidecar -- one `key=value` line per field that
     * is actually set, `raw` only when true (mirroring the header's own convention: absence means
     * unset/false; an explicit `raw=false` line is never written). Deletes the sidecar entirely
     * when [directives] has nothing set (`== Directives()`), rather than leaving an empty file next
     * to the snippet -- the sidecar analogue of
     * [com.github.asm0dey.typewriter.ui.DirectiveHeader.write]'s "empty directives removes the
     * header" behaviour.
     *
     * The output is hand-written, not [Properties.store]: `store` unconditionally prepends a
     * `#<timestamp>` comment line, which would change on every save regardless of whether the
     * directives themselves did, undermining the very line-level-diff friendliness the sidecar
     * format was chosen for (see the class kdoc). A fixed field order (`raw`, `speed`, `jitter`,
     * `newline`) keeps the output deterministic for the same reason.
     */
    fun write(snippetFile: VirtualFile, directives: Directives) {
        val existing = sidecarFileFor(snippetFile)
        if (directives == Directives()) {
            if (existing != null) WriteAction.run<IOException> { existing.delete(this) }
            return
        }
        val lines = buildList {
            if (directives.raw) add("$KEY_RAW=true")
            directives.speedMs?.let { add("$KEY_SPEED=$it") }
            directives.jitterMs?.let { add("$KEY_JITTER=$it") }
            directives.newlineMs?.let { add("$KEY_NEWLINE=$it") }
        }
        val bytes = (lines.joinToString("\n") + "\n").toByteArray(StandardCharsets.UTF_8)
        WriteAction.run<IOException> {
            val target = existing ?: checkNotNull(snippetFile.parent) { "${snippetFile.name} has no parent directory" }
                .createChildData(this, snippetFile.name + SUFFIX)
            target.setBinaryContent(bytes)
        }
    }
}
