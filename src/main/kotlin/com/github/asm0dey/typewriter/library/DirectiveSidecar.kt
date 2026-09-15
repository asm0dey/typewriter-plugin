package com.github.asm0dey.typewriter.library

import com.github.asm0dey.typewriter.model.Directives
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Timing for snippets whose language has no comment syntax at all (spec section 9, resolved
 * design question 22). A directive header needs a comment to hide inside it --
 * [com.github.asm0dey.typewriter.ui.DirectiveHeader.write] is a no-op for exactly this case, and
 * [com.github.asm0dey.typewriter.parse.MarkerScanner] returns no markers for the same underlying
 * reason -- so such a snippet's timing cannot live in the file at all. It lives instead in a JSON
 * sidecar, one per snippet directory (global and project each have their own), keyed by the
 * snippet's relative path within that directory.
 *
 * Consulted **only** when [com.github.asm0dey.typewriter.parse.CommentSyntax.hasAny] is false for
 * a snippet's language -- a comment-capable snippet's header remains the sole source of truth for
 * that snippet, so the header and the sidecar never compete over the same snippet.
 *
 * Renames and deletions are not reconciled: a snippet renamed or removed leaves its sidecar entry
 * behind, orphaned. This is a deliberate, cheap consequence rather than a bug -- reconciliation
 * machinery is not built for it. An orphaned entry is simply never looked up again (nothing reads
 * the sidecar except by an actual snippet's own relative path), so it costs nothing beyond a few
 * stale bytes on disk until the sidecar is next rewritten for an unrelated entry, at which point
 * [write] preserves it as-is (it is never the entry being touched).
 */
object DirectiveSidecar {

    /**
     * Exact filename. [SnippetLibrary.collect]/[SnippetLibrary.sequence] exclude it by this exact
     * name so it never registers itself as a playable snippet with its own action.
     */
    const val FILE_NAME = ".typewriter.json"

    private val gson = Gson()

    /**
     * The JSON shape of one snippet's sidecar entry. `raw` is `null` rather than `false` when
     * unset -- mirroring [Directives]' own header-text convention, where `raw` is only ever
     * mentioned when true -- so [Entry.toDirectives] can tell "never set" from "explicitly off"
     * apart the same way a header does, even though [Directives.raw] itself has no such
     * distinction to preserve.
     */
    private data class Entry(
        val raw: Boolean? = null,
        val speedMs: Int? = null,
        val jitterMs: Int? = null,
        val newlineMs: Int? = null,
    )

    private fun Entry.toDirectives() = Directives(raw = raw == true, speedMs = speedMs, jitterMs = jitterMs, newlineMs = newlineMs)
    private fun Directives.toEntry() = Entry(raw = raw.takeIf { it }, speedMs = speedMs, jitterMs = jitterMs, newlineMs = newlineMs)

    /**
     * Every entry currently in [directory]'s sidecar, or empty when the file is absent, unreadable,
     * or not valid JSON -- a broken or missing sidecar degrades to "no timing overrides", never an
     * error that blocks a demo.
     */
    fun read(directory: VirtualFile): Map<String, Directives> {
        val file = directory.findChild(FILE_NAME) ?: return emptyMap()
        val json = try {
            String(file.contentsToByteArray(), StandardCharsets.UTF_8)
        } catch (e: IOException) {
            return emptyMap()
        }
        // Parsed via a plain JsonObject and one fromJson(JsonElement, Class<Entry>) per entry --
        // not the `Map<String, Entry>` TypeToken idiom, which (anonymous-subclass form or
        // TypeToken.getParameterized alike) deserialised every value as a raw LinkedTreeMap
        // instead of an Entry here, throwing a ClassCastException the moment a field was read off
        // one. Class<T> overloads throughout sidestep whatever was going wrong with reifying the
        // parameterized Map type.
        val root = try {
            gson.fromJson(json, JsonObject::class.java) ?: return emptyMap()
        } catch (e: JsonParseException) {
            return emptyMap()
        }
        return try {
            root.entrySet().associate { (key, value) -> key to gson.fromJson(value, Entry::class.java).toDirectives() }
        } catch (e: JsonParseException) {
            emptyMap()
        }
    }

    /**
     * [relativePath]'s directives from [directory]'s sidecar, or [Directives()][Directives] (no
     * override) when it has no entry for that path -- the same default
     * [com.github.asm0dey.typewriter.ui.DirectiveHeader.read] returns for a header-less
     * comment-capable snippet.
     */
    fun directivesFor(directory: VirtualFile, relativePath: String): Directives =
        read(directory)[relativePath] ?: Directives()

    /**
     * Sets [relativePath]'s entry in [directory]'s sidecar to [directives], or removes it entirely
     * when [directives] has nothing set (`== Directives()`) -- the sidecar analogue of
     * [com.github.asm0dey.typewriter.ui.DirectiveHeader.write]'s "empty directives removes the
     * header" behaviour, so a comment-less snippet the speaker never adjusts gains no entry, and
     * one that had timing but had it fully cleared loses its entry rather than being written as an
     * all-null husk. Deletes the sidecar file itself once its last entry is removed, rather than
     * leaving an empty `{}` behind in the directory.
     */
    fun write(directory: VirtualFile, relativePath: String, directives: Directives) {
        val current = read(directory).toMutableMap()
        if (directives == Directives()) current.remove(relativePath) else current[relativePath] = directives

        val existing = directory.findChild(FILE_NAME)
        if (current.isEmpty()) {
            if (existing != null) WriteAction.run<IOException> { existing.delete(this) }
            return
        }
        val json = gson.toJson(current.mapValues { it.value.toEntry() })
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        WriteAction.run<IOException> {
            val target = existing ?: directory.createChildData(this, FILE_NAME)
            target.setBinaryContent(bytes)
        }
    }
}
