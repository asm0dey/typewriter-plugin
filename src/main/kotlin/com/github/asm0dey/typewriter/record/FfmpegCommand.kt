package com.github.asm0dey.typewriter.record

import java.nio.file.Path

enum class VideoFormat {
    MP4, GIF;

    companion object {
        fun of(path: Path): VideoFormat? = when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
            "mp4" -> MP4
            "gif" -> GIF
            else -> null
        }
    }
}

private const val GIF_FILTER = "split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:diff_mode=rectangle"

fun ffmpegArgs(ffmpeg: Path, width: Int, height: Int, fps: Int, format: VideoFormat, output: Path): List<String> {
    // -y: stdin is our frame pipe, ffmpeg must never prompt on it. -loglevel error: keep stderr to lines worth showing.
    return buildList {
        addAll(
            listOf(
                ffmpeg.toString(), "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgr24",
                "-s", "${width}x$height", "-r", "$fps", "-i", "-",
            )
        )
        when (format) {
            VideoFormat.MP4 -> addAll(
                listOf("-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", "-preset", "medium", "-movflags", "+faststart")
            )
            // GIF delays are whole centiseconds and browsers clamp delays under 2cs, so cap the rate at 50.
            VideoFormat.GIF -> addAll(listOf("-vf", GIF_FILTER, "-r", "${minOf(fps, 50)}"))
        }
        add(output.toString())
    }
}

fun sizeError(width: Int, height: Int): String? = when {
    width < 2 || height < 2 -> "width and height must be positive"
    width % 2 != 0 || height % 2 != 0 -> "width and height must be even"
    else -> null
}
