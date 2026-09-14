package com.github.asm0dey.typewriter.model

sealed interface Step {
    data class Type(val text: String) : Step
    data class Pause(val millis: Long) : Step
    data class Action(val actionId: String) : Step
}

data class Timing(val speedMs: Int, val jitterMs: Int, val newlineMs: Int)

data class Program(
    val steps: List<Step>,
    val directives: Directives,
    val errors: List<ParseError>,
)
