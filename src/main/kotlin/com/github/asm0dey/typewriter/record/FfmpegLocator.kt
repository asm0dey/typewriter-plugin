package com.github.asm0dey.typewriter.record

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

sealed interface FfmpegStatus {
    data class Found(val path: Path, val version: String) : FfmpegStatus
    data class NotFound(val value: String) : FfmpegStatus
    data class NotRunnable(val value: String) : FfmpegStatus
}

fun FfmpegStatus.describe(): String = when (this) {
    is FfmpegStatus.Found -> "ffmpeg $version"
    is FfmpegStatus.NotFound -> "not found: $value"
    is FfmpegStatus.NotRunnable -> "not runnable: $value"
}

/**
 * Resolves [value] (a bare command name or a path) and checks that it really is ffmpeg.
 * The default [pathEnv] is the login-shell PATH the IDE loaded, so Homebrew's bin dir is found
 * even when the IDE was started from the Dock.
 */
// ponytail: runs ffmpeg -version synchronously; move to a background task if Apply ever feels slow
fun locateFfmpeg(
    value: String, pathEnv: String? = EnvironmentUtil.getValue("PATH"), windows: Boolean = SystemInfo.isWindows,
): FfmpegStatus {
    val path = if ('/' in value || '\\' in value) Path.of(value)
    // findInPath matches the exact name only, and on Windows the file is ffmpeg.exe.
    else (if (windows) listOf(value, "$value.exe") else listOf(value))
        .firstNotNullOfOrNull { PathEnvironmentVariableUtil.findInPath(it, pathEnv, null) }?.toPath()
    if (path == null || !Files.exists(path)) return FfmpegStatus.NotFound(value)
    return try {
        val process = ProcessBuilder(path.toString(), "-version").redirectErrorStream(true).start()
        process.outputStream.close()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return FfmpegStatus.NotRunnable(value)
        }
        val first = process.inputStream.bufferedReader().readLine().orEmpty()
        if (process.exitValue() == 0 && first.startsWith("ffmpeg version ")) {
            FfmpegStatus.Found(path, first.split(Regex("\\s+"))[2])
        } else FfmpegStatus.NotRunnable(value)
    } catch (_: Exception) {
        FfmpegStatus.NotRunnable(value)
    }
}
