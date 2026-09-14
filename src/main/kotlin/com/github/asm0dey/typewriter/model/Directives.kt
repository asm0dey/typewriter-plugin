package com.github.asm0dey.typewriter.model

data class Directives(
    val raw: Boolean = false,
    val speedMs: Int? = null,
    val jitterMs: Int? = null,
    val newlineMs: Int? = null,
) {
    fun timing(defaults: Timing) = Timing(
        speedMs = speedMs ?: defaults.speedMs,
        jitterMs = jitterMs ?: defaults.jitterMs,
        newlineMs = newlineMs ?: defaults.newlineMs,
    )

    companion object {
        val NAMES = setOf("raw", "speed", "jitter", "newline")
    }
}
