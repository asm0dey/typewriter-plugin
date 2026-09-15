package com.github.asm0dey.typewriter.ui

import com.github.asm0dey.typewriter.model.Timing
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "TypeWriterSettings", storages = [Storage("typewriter.xml")])
class TypeWriterSettings : PersistentStateComponent<TypeWriterSettings.State> {

    data class State(
        var globalDir: String = defaultGlobalDir(),
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

/**
 * The default global snippet directory: `typewriter` under the JetBrains **common** data
 * directory ([PathManager.getCommonDataPath]) -- `~/.local/share/JetBrains` on Linux,
 * `~/Library/Application Support/JetBrains` on macOS, `%APPDATA%\JetBrains` on Windows. The old
 * hard-coded `~/.typewriter` was a Unix-ism that put a dotfile in the user's home on every OS.
 *
 * Common data rather than [PathManager.getConfigPath], which is per-product AND per-version
 * (measured on this platform: `~/.config/JetBrains/IntelliJIdea2026.2`). The global directory is
 * the speaker's *reusable toolkit* (spec section 8) -- storing it under a product-versioned config
 * path would hide it from the same speaker's PyCharm or RubyMine and strand it on every IDE
 * upgrade. Common data carries no product or version segment, so one toolkit serves every
 * JetBrains IDE the speaker demos in.
 *
 * No leading dot on the directory name: it already sits inside an application data directory, and
 * hiding it there only makes it harder to find. The PROJECT directory keeps its `.typewriter`
 * dot-name -- that one lives in the talk repository, where a dotfile is the right convention.
 */
fun defaultGlobalDir(): String = PathManager.getCommonDataPath().resolve("typewriter").toString()
