package com.github.asm0dey.typewriterplugin

import com.github.asm0dey.typewriterplugin.commands.Command
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level.APP
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

@Service(APP)
class TypewriterExecutorService : Disposable {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    override fun dispose() {
        scheduler.shutdown()
        if (!scheduler.awaitTermination(5, SECONDS)) {
            scheduler.shutdownNow()
        }
    }

    fun enqueue(command: Command, pauseAfterMs: Long = 0L) {
        scheduler.schedule(command, 0, SECONDS)
        if (pauseAfterMs > 0)
            scheduler.schedule({}, pauseAfterMs, MILLISECONDS)
    }
}