package com.github.asm0dey.typewriter.record

import com.github.asm0dey.typewriter.record.VideoFormat.GIF
import com.github.asm0dey.typewriter.record.VideoFormat.MP4
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path

class FfmpegArgsTest {
    private val gifFilter = "split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:diff_mode=rectangle"

    @Test
    fun testMp4Args() {
        assertEquals(
            listOf(
                "/usr/bin/ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgr24",
                "-s", "1920x1080", "-r", "60", "-i", "-",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", "-preset", "medium",
                "-movflags", "+faststart", "/tmp/a.part.mp4",
            ),
            ffmpegArgs(Path.of("/usr/bin/ffmpeg"), 1920, 1080, 60, MP4, Path.of("/tmp/a.part.mp4")),
        )
    }

    @Test
    fun testGifArgs() {
        assertEquals(
            listOf(
                "/usr/bin/ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgr24",
                "-s", "1920x1080", "-r", "60", "-i", "-",
                "-vf", gifFilter, "-r", "50", "/tmp/a.part.gif",
            ),
            ffmpegArgs(Path.of("/usr/bin/ffmpeg"), 1920, 1080, 60, GIF, Path.of("/tmp/a.part.gif")),
        )
        val slow = ffmpegArgs(Path.of("ffmpeg"), 1280, 720, 30, GIF, Path.of("a.gif"))
        assertEquals(listOf("-r", "30", "a.gif"), slow.takeLast(3))
    }

    @Test
    fun testFormatFromExtension() {
        assertEquals(MP4, VideoFormat.of(Path.of("a.mp4")))
        assertEquals(GIF, VideoFormat.of(Path.of("A.GIF")))
        assertNull(VideoFormat.of(Path.of("a.webm")))
        assertNull(VideoFormat.of(Path.of("a")))
    }

    @Test
    fun testSizeError() {
        assertNull(sizeError(1920, 1080))
        assertEquals("width and height must be even", sizeError(1921, 1080))
        assertEquals("width and height must be even", sizeError(1920, 1081))
        assertEquals("width and height must be positive", sizeError(0, 1080))
    }
}
