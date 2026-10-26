package com.github.asm0dey.typewriter.record

import com.github.asm0dey.typewriter.library.SnippetRunner
import com.github.asm0dey.typewriter.model.Snippet
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

data class BatchItem(val snippet: Snippet, val output: Path)

sealed interface BatchOutcome {
    val item: BatchItem
    val warnings: List<String>

    data class Recorded(override val item: BatchItem, override val warnings: List<String>) : BatchOutcome
    data class Failed(override val item: BatchItem, val reason: String, override val warnings: List<String>) : BatchOutcome
}

/**
 * Records [items] one after another, each typed into an empty file named after its snippet
 * ([emptyStage]). A snippet that is blocked or fails becomes a [BatchOutcome.Failed] and the batch
 * goes on; cancellation propagates, with every finished item already passed to [onOutcome].
 * [options]' output is ignored: each item has its own. [progress] covers the whole batch.
 */
suspend fun recordBatch(
    project: Project, items: List<BatchItem>, options: RecordOptions,
    sinkFor: (RecordOptions) -> FrameSink,
    onStart: (index: Int, item: BatchItem) -> Unit = { _, _ -> },
    progress: (Double) -> Unit = {},
    onOutcome: (BatchOutcome) -> Unit,
) {
    items.forEachIndexed { index, item ->
        onStart(index, item)
        val itemOptions = options.copy(output = item.output)
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()
        // Anything thrown outside runRecording (a binary file with no document, say) fails this item only.
        val failure = try { withContext(Dispatchers.EDT) {
            val disposable = Disposer.newDisposable("TypeWriter batch recording")
            try {
                val stage = emptyStage(project, item.snippet, options.width, options.height, options.fontSize, disposable)
                val prepared = SnippetRunner.prepare(project, stage.editor, item.snippet) { message, type ->
                    if (type == NotificationType.ERROR) errors += message else warnings += message
                }
                    ?: return@withContext errors.joinToString("\n").ifEmpty { "could not prepare the snippet" }
                warnings += recordingWarnings(item.snippet.relativePath, prepared.steps).map { it.message }
                val sink = sinkFor(itemOptions)
                runRecording(sink) {
                    // FfmpegSink writes its .part file next to the output, so the folder must exist first.
                    withContext(Dispatchers.IO) { Files.createDirectories(item.output.parent) }
                    record(project, stage, prepared.steps, prepared.timing, itemOptions, sink) {
                        progress((index + it) / items.size)
                    }
                }
            } finally {
                Disposer.dispose(disposable)
            }
        } } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "recording failed: ${e.message ?: e}"
        }
        progress((index + 1.0) / items.size)
        onOutcome(failure?.let { BatchOutcome.Failed(item, it, warnings) } ?: BatchOutcome.Recorded(item, warnings))
    }
}

/** The end-of-batch notification text; [total] is the number of snippets the batch was asked to record. */
fun batchSummary(outcomes: List<BatchOutcome>, total: Int, cancelled: Boolean): String {
    val recorded = outcomes.filterIsInstance<BatchOutcome.Recorded>()
    val withWarnings = recorded.count { it.warnings.isNotEmpty() }
    return when {
        cancelled -> "Cancelled after ${recorded.size} of $total videos"
        recorded.size < total -> "Recorded ${recorded.size} of $total videos"
        withWarnings > 0 -> "Recorded $total videos, $withWarnings with warnings"
        else -> "Recorded $total videos"
    }
}
