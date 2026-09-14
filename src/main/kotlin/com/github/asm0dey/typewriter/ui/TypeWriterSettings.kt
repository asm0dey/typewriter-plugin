package com.github.asm0dey.typewriter.ui

import com.github.asm0dey.typewriter.model.Timing
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.nio.file.Paths

@Service(Service.Level.APP)
@State(name = "TypeWriterSettings", storages = [Storage("typewriter.xml")])
class TypeWriterSettings : PersistentStateComponent<TypeWriterSettings.State> {

    data class State(
        var globalDir: String = Paths.get(System.getProperty("user.home"), ".typewriter").toString(),
        var speedMs: Int = 100,
        var jitterMs: Int = 20,
        var newlineMs: Int = 300,
        var sentinel: String = "tw:",
        var formatOnPlay: Boolean = true,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun defaultTiming() = Timing(state.speedMs, state.jitterMs, state.newlineMs)
}

@Service(Service.Level.PROJECT)
@State(name = "TypeWriterProjectSettings", storages = [Storage("typewriter.xml")])
class TypeWriterProjectSettings : PersistentStateComponent<TypeWriterProjectSettings.State> {

    data class State(var projectDir: String = ".typewriter")

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }
}
