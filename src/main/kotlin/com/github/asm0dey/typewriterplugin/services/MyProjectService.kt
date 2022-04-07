package com.github.asm0dey.typewriterplugin.services

import com.intellij.openapi.project.Project
import com.github.asm0dey.typewriterplugin.MyBundle

class MyProjectService(project: Project) {

    init {
        println(MyBundle.message("projectService", project.name))
    }
}
