package com.github.asm0dey.typewriter.model

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile

data class Snippet(
    val id: String,
    val relativePath: String,
    val file: VirtualFile,
    val fileType: FileType,
    val fromProject: Boolean,
)
