package com.github.asm0dey.typewriter.record

import java.nio.file.Path

/**
 * The file a batch video types into, named like a class: `03-user-service.kt` -> `UserService.kt`.
 * Leading numbers and separators go, the rest is PascalCased, the extension stays.
 */
fun cleanClassName(fileName: String): String {
    val dot = fileName.lastIndexOf('.')
    val stem = if (dot < 0) fileName else fileName.substring(0, dot)
    val extension = if (dot < 0) "" else fileName.substring(dot)
    val name = stem.trimStart { it.isDigit() || it in "-_. " }
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    return name.ifEmpty { "Snippet" } + extension
}

/** Case-insensitive glob over a snippet's relative path: `*` crosses `/`, `?` doesn't; nothing else is special. */
fun globMatches(pattern: String, relativePath: String): Boolean {
    if (pattern.isEmpty()) return false
    val regex = Regex("[*?]|[^*?]+").findAll(pattern).joinToString("") {
        when (it.value) {
            "*" -> ".*"
            "?" -> "[^/]"
            else -> Regex.escape(it.value)
        }
    }
    return Regex(regex, RegexOption.IGNORE_CASE).matches(relativePath)
}

/**
 * Each snippet's video under [folder], mirroring its subdirectories: `java/01-hello.java` ->
 * `java/01-hello.mp4`. Snippets whose videos would land on one file, compared case-insensitively
 * for macOS and Windows, keep their source extension instead: `01-hello.java.mp4`, `01-hello.kt.mp4`.
 */
fun batchTargets(relativePaths: List<String>, folder: Path, format: VideoFormat): Map<String, Path> {
    val ext = format.name.lowercase()
    // Only the file name's extension: a dot in a directory name is not one.
    fun short(path: String) = if ('.' in path.substringAfterLast('/')) path.substringBeforeLast('.') else path
    val clashing = relativePaths.groupBy { short(it).lowercase() }.values.filter { it.size > 1 }.flatten().toSet()
    return relativePaths.associateWith { folder.resolve("${if (it in clashing) it else short(it)}.$ext") }
}
