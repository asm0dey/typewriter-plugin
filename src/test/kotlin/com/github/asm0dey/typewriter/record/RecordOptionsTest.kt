package com.github.asm0dey.typewriter.record

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class RecordOptionsTest {
    @Test
    fun testOutputInConfiguredDir() {
        assertEquals(Path.of("/v/01-entity.mp4"), defaultOutput("/v", "01-entity"))
    }

    @Test
    fun testBlankDirMeansVideoDir() {
        assertEquals(defaultVideoDir(), defaultOutput("", "x").parent)
    }

    @Test
    fun testVideoDirExists() {
        assertTrue(Files.isDirectory(defaultVideoDir()))
    }
}
