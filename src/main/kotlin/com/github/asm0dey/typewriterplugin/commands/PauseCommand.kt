package com.github.asm0dey.typewriterplugin.commands

class PauseCommand(private val delay: Long) : Command {

    override fun run() = Thread.sleep(delay)
}
