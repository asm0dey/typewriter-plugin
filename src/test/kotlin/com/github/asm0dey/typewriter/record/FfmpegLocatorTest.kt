package com.github.asm0dey.typewriter.record

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FfmpegLocatorTest {
    @TempDir
    lateinit var dir: Path

    private fun fakeExe(parent: Path, name: String, script: String, executable: Boolean = true): Path {
        Files.createDirectories(parent)
        val file = parent.resolve(name)
        Files.writeString(file, "#!/bin/sh\n$script\n")
        file.toFile().setExecutable(executable)
        return file
    }

    @Test
    fun testBareNameResolvedOnGivenPath() {
        fakeExe(dir, "ffmpeg", "echo 'ffmpeg version 7.1 Copyright (c)'")
        val status = locateFfmpeg("ffmpeg", dir.toString())
        assertEquals(FfmpegStatus.Found(dir.resolve("ffmpeg"), "7.1"), status)
        assertEquals("ffmpeg 7.1", status.describe())
    }

    @Test
    fun testValueWithSeparatorUsedAsIs() {
        fakeExe(dir.resolve("bin"), "ffmpeg", "echo 'ffmpeg version 7.1 Copyright (c)'")
        assertTrue(locateFfmpeg("$dir/bin/ffmpeg", pathEnv = "") is FfmpegStatus.Found)
    }

    @Test
    fun testMissing() {
        val status = locateFfmpeg("$dir/nope", "")
        assertEquals(FfmpegStatus.NotFound("$dir/nope"), status)
        assertEquals("not found: $dir/nope", status.describe())
        assertEquals(FfmpegStatus.NotFound("ffmpeg"), locateFfmpeg("ffmpeg", dir.toString()))
    }

    @Test
    fun testNonExecutableIsNotRunnable() {
        val file = fakeExe(dir, "ffmpeg", "echo 'ffmpeg version 7.1'", executable = false)
        assertEquals(FfmpegStatus.NotRunnable(file.toString()), locateFfmpeg(file.toString(), ""))
    }

    @Test
    fun testWrongProgramIsNotRunnable() {
        val file = fakeExe(dir, "ffmpeg", "echo hello")
        val status = locateFfmpeg(file.toString(), "")
        assertEquals(FfmpegStatus.NotRunnable(file.toString()), status)
        assertEquals("not runnable: $file", status.describe())
    }

    @Test
    fun testFailingExitIsNotRunnable() {
        val file = fakeExe(dir, "ffmpeg", "echo 'ffmpeg version 7.1'; exit 3")
        assertEquals(FfmpegStatus.NotRunnable(file.toString()), locateFfmpeg(file.toString(), ""))
    }

    @Test
    fun testBareNameFindsExeOnWindows() {
        fakeExe(dir, "ffmpeg.exe", "echo 'ffmpeg version 7.1 Copyright (c)'")
        assertEquals(FfmpegStatus.Found(dir.resolve("ffmpeg.exe"), "7.1"), locateFfmpeg("ffmpeg", dir.toString(), windows = true))
        assertEquals(FfmpegStatus.NotFound("ffmpeg"), locateFfmpeg("ffmpeg", dir.toString(), windows = false))
    }
}
