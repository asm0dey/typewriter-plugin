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
            if (from > cursor) {
                val chunk = text.substring(cursor, from).replace("$sentinel:", sentinel)
                if (chunk.isNotEmpty()) {
                    steps += Step.Type(chunk)
                    if (chunk.isNotBlank()) typedAnything = true
                }
            }
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
                            directives = applyDirectives(rest, directives, marker, errors)
                        }
                    }
                    else -> errors += ParseError(marker.line, "unknown command or directive: \"$first\"")
                }
            }
            cursor = to
        }
        if (cursor < text.length) {
            val chunk = text.substring(cursor).replace("$sentinel:", sentinel)
            if (chunk.isNotEmpty()) steps += Step.Type(chunk)
        }
        return Program(steps, directives, errors)
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
