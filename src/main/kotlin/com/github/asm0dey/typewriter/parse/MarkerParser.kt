package com.github.asm0dey.typewriter.parse

import com.github.asm0dey.typewriter.model.Directives
import com.github.asm0dey.typewriter.model.ParseError
import com.github.asm0dey.typewriter.model.Program
import com.github.asm0dey.typewriter.model.Step

object MarkerParser {

    fun parse(text: String, markers: List<RawMarker>, sentinel: String): Program {
        val steps = mutableListOf<Step>()
        val errors = mutableListOf<ParseError>()
        var directives = Directives()
        var typedAnything = false
        var cursor = 0

        for (marker in markers.sortedBy { it.startOffset }) {
            val (from, to) = consumedRange(text, marker)
            if (from > cursor && appendChunk(text.substring(cursor, from), sentinel, steps)) {
                typedAnything = true
            }
            directives = processMarkerBody(marker, sentinel, typedAnything, directives, steps, errors)
            cursor = to
        }
        if (cursor < text.length) {
            appendChunk(text.substring(cursor), sentinel, steps)
        }
        return Program(steps, directives, errors)
    }

    /**
     * Processes one marker's body, line by line: dispatches `pause`/`action` commands into
     * [steps] and collects directive updates into the returned [Directives]. [typedAnything] is
     * only read here (never written) -- it governs whether a directive line is still legal at
     * this point in the file (spec: directives must precede all typed text).
     */
    private fun processMarkerBody(
        marker: RawMarker,
        sentinel: String,
        typedAnything: Boolean,
        directives: Directives,
        steps: MutableList<Step>,
        errors: MutableList<ParseError>,
    ): Directives {
        var result = directives
        for (line in marker.body.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (!trimmed.startsWith(sentinel)) {
                errors += ParseError(marker.line, "marker body line has no \"$sentinel\": $trimmed")
                continue
            }
            val rest = trimmed.removePrefix(sentinel).trim()
            when (val first = rest.substringBefore(' ').trim()) {
                "pause" -> parsePause(rest, marker, errors)?.let { steps += it }
                "action" -> parseAction(rest, marker, errors)?.let { steps += it }
                in Directives.NAMES -> {
                    if (typedAnything) {
                        errors += ParseError(marker.line, "directive \"$first\" must precede all typed text")
                    } else {
                        result = applyDirectives(rest, result, marker, errors)
                    }
                }
                else -> errors += ParseError(marker.line, "unknown command or directive: \"$first\"")
            }
        }
        return result
    }

    /**
     * Unescapes the sentinel-escape ("tw:: " -> "tw: ") in [raw] and, if anything is left,
     * appends it as a [Step.Type] to [steps]. Returns whether the appended chunk carried any
     * non-whitespace content, for the caller to fold into `typedAnything`.
     */
    private fun appendChunk(raw: String, sentinel: String, steps: MutableList<Step>): Boolean {
        val chunk = raw.replace("$sentinel:", sentinel)
        if (chunk.isEmpty()) return false
        steps += Step.Type(chunk)
        return chunk.isNotBlank()
    }

    /** Spec section 5, "Whitespace consumption". */
    private fun consumedRange(text: String, marker: RawMarker): Pair<Int, Int> = when (marker.kind) {
        MarkerKind.WHOLE_LINE -> {
            val lineStart = text.lastIndexOf('\n', (marker.startOffset - 1).coerceAtLeast(0))
                .let { if (it < 0) 0 else it + 1 }
            val newline = text.indexOf('\n', marker.endOffset)
            lineStart to if (newline < 0) text.length else newline + 1
        }
        MarkerKind.TRAILING -> {
            var from = marker.startOffset
            while (from > 0 && (text[from - 1] == ' ' || text[from - 1] == '\t')) from--
            from to marker.endOffset
        }
        MarkerKind.MID_LINE -> {
            var to = marker.endOffset
            while (to < text.length && (text[to] == ' ' || text[to] == '\t')) to++
            marker.startOffset to to
        }
    }

    private fun parsePause(rest: String, marker: RawMarker, errors: MutableList<ParseError>): Step? {
        val arg = rest.removePrefix("pause").trim()
        val millis = arg.toLongOrNull()
        if (millis == null || millis < 0) {
            errors += ParseError(marker.line, "pause needs a non-negative number of milliseconds, got \"$arg\"")
            return null
        }
        return Step.Pause(millis)
    }

    private fun parseAction(rest: String, marker: RawMarker, errors: MutableList<ParseError>): Step? {
        val id = rest.removePrefix("action").trim()
        if (id.isEmpty()) {
            errors += ParseError(marker.line, "action needs an action id")
            return null
        }
        return Step.Action(id)
    }

    /** A directive line may carry several pairs: "raw speed 80 jitter 25". */
    private fun applyDirectives(
        rest: String,
        current: Directives,
        marker: RawMarker,
        errors: MutableList<ParseError>,
    ): Directives {
        var result = current
        val tokens = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var i = 0
        while (i < tokens.size) {
            when (val key = tokens[i]) {
                "raw" -> { result = result.copy(raw = true); i++ }
                "speed", "jitter", "newline" -> {
                    val value = tokens.getOrNull(i + 1)?.toIntOrNull()
                    if (value == null || value < 0) {
                        errors += ParseError(marker.line, "$key needs a non-negative number, got \"${tokens.getOrNull(i + 1)}\"")
                    } else {
                        result = when (key) {
                            "speed" -> result.copy(speedMs = value)
                            "jitter" -> result.copy(jitterMs = value)
                            else -> result.copy(newlineMs = value)
                        }
                    }
                    i += 2
                }
                else -> {
                    errors += ParseError(marker.line, "unknown directive: \"$key\"")
                    i++
                }
            }
        }
        return result
    }
}
