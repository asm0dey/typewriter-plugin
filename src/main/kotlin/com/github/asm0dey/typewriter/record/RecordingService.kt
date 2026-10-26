package com.github.asm0dey.typewriter.record

import com.github.asm0dey.typewriter.library.SnippetDirs
import com.github.asm0dey.typewriter.library.SnippetRunner
import com.github.asm0dey.typewriter.model.Snippet
import com.github.asm0dey.typewriter.ui.TypeWriterConfigurable
import com.github.asm0dey.typewriter.ui.TypeWriterSettings
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportSequentialProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/** Notification content is HTML: escape [text] and keep its line breaks. */
internal fun html(text: String): String = StringUtil.escapeXmlEntities(text).replace("\n", "<br>")

/** The dialog's default font size: Settings > Editor > Font, which the stage scales by the IDE zoom itself. */
internal fun defaultFontSize(saved: Int): Int =
    saved.takeIf { it > 0 } ?: EditorColorsManager.getInstance().globalScheme.editorFontSize

/**
 * Runs [block], aborting [sink] unless it completes -- even on an [Error]. Returns the failure to
 * notify, or null on success; a cancellation is rethrown without one.
 */
internal suspend fun runRecording(sink: FrameSink, block: suspend () -> Unit): String? {
    var done = false
    try {
        block()
        done = true
        return null
    } catch (e: CancellationException) {
        throw e
    } catch (e: FfmpegFailed) {
        return "recording failed:\n${e.stderrTail}"
    } catch (e: Exception) {
        return "recording failed: ${e.message ?: e}"
    } finally {
        if (!done) sink.abort()
    }
}

/**
 * Records a snippet to a video file. A recording is not a run: it types into its own offscreen
 * stage, so it never touches [com.github.asm0dey.typewriter.run.RunService] state.
 *
 * `@Service(PROJECT)` is itself the registration -- see [com.github.asm0dey.typewriter.run.RunService].
 */
@Service(Service.Level.PROJECT)
class RecordingService(private val project: Project, private val scope: CoroutineScope) {

    /** EDT only: shows the dialog, then records in the background. */
    fun start(editor: Editor?, snippet: Snippet) {
        val prepared = SnippetRunner.prepare(project, editor, snippet) ?: return
        val settings = ApplicationManager.getApplication().getService(TypeWriterSettings::class.java).state

        val status = ffmpegOrNotify(settings) ?: return
        recordingWarnings(snippet.relativePath, prepared.steps).forEach {
            SnippetRunner.notify(project, html(it.message), NotificationType.WARNING)
        }

        val dialog = RecordDialog(project, savedOptions(settings, defaultOutput(settings.recordDir, snippet.file.nameWithoutExtension)))
        if (!dialog.showAndGet()) return
        val options = dialog.options()
        settings.recordDir = options.output.parent.toString()
        save(settings, options)

        scope.launch {
            withBackgroundProgress(project, "Recording ${snippet.relativePath}", cancellable = true) {
                reportSequentialProgress { reporter ->
                    // reportRawProgress would be the direct fit, but it inlines an @ApiStatus.Internal
                    // handle into this class and the plugin verifier rejects it. Whole percents, and
                    // only ever forward: nextStep needs a strictly increasing end fraction.
                    var percent = 0
                    val sink = FfmpegSink(status.path, options)
                    val failure = runRecording(sink) {
                        withContext(Dispatchers.EDT) {
                            val disposable = Disposer.newDisposable("TypeWriter recording")
                            try {
                                val stage = RecordingStage(
                                    project, prepared.editor, options.width, options.height, options.fontSize, disposable,
                                )
                                record(project, stage, prepared.steps, prepared.timing, options, sink) {
                                    val next = (it * 100).toInt()
                                    if (next > percent) {
                                        percent = next
                                        reporter.nextStep(next)
                                    }
                                }
                            } finally {
                                Disposer.dispose(disposable)
                            }
                        }
                    }
                    if (failure != null) {
                        SnippetRunner.notify(project, html(failure), NotificationType.ERROR)
                        return@reportSequentialProgress
                    }
                    SnippetRunner.notify(
                        project, html("Recorded ${options.output.fileName}"), NotificationType.INFORMATION,
                        NotificationAction.createSimple("Show in Folder") { RevealFileAction.openFile(options.output) },
                    )
                }
            }
        }
    }

    /** EDT only: pick snippets and a folder, then record them one after another in the background. */
    fun startBatch() {
        val settings = ApplicationManager.getApplication().getService(TypeWriterSettings::class.java).state
        val status = ffmpegOrNotify(settings) ?: return
        val snippets = SnippetDirs.all(project)
        if (snippets.isEmpty()) {
            SnippetRunner.notify(project, "no snippets found", NotificationType.WARNING)
            return
        }
        val folder = if (settings.recordBatchDir.isBlank()) defaultVideoDir() else Path.of(settings.recordBatchDir)
        val format = VideoFormat.entries.firstOrNull { it.name.equals(settings.recordBatchFormat, true) } ?: VideoFormat.MP4
        val dialog = BatchRecordDialog(project, snippets, settings.recordBatchPattern, folder, format, savedOptions(settings, folder))
        if (!dialog.showAndGet()) return
        val items = dialog.items()
        val options = dialog.options()
        settings.recordBatchPattern = dialog.pattern()
        settings.recordBatchDir = dialog.folder().toString()
        settings.recordBatchFormat = dialog.format().name.lowercase()
        save(settings, options)

        scope.launch {
            val outcomes = mutableListOf<BatchOutcome>()
            try {
                withBackgroundProgress(project, "Recording snippets", cancellable = true) {
                    reportSequentialProgress { reporter ->
                        // Whole percents, only ever forward: see start().
                        var percent = 0
                        var text = ""
                        recordBatch(
                            project, items, options, { FfmpegSink(status.path, it) },
                            onStart = { i, item ->
                                // Shown from the next percent: nextStep can't repeat one.
                                text = "Recording ${i + 1} of ${items.size}: ${item.snippet.relativePath}"
                            },
                            progress = {
                                val next = (it * 100).toInt()
                                if (next > percent) {
                                    percent = next
                                    reporter.nextStep(next, text)
                                }
                            },
                            onOutcome = { outcomes += it },
                        )
                    }
                }
                notifyBatch(outcomes, items.size, cancelled = false, dialog.folder())
            } catch (e: CancellationException) {
                notifyBatch(outcomes, items.size, cancelled = true, dialog.folder())
                throw e
            }
        }
    }

    private fun notifyBatch(outcomes: List<BatchOutcome>, total: Int, cancelled: Boolean, folder: Path) {
        val details = outcomes.flatMap { o ->
            (if (o is BatchOutcome.Failed) listOf("${o.item.snippet.relativePath}: ${o.reason}") else emptyList()) +
                o.warnings.map { "${o.item.snippet.relativePath}: $it" }
        }
        val failed = outcomes.any { it is BatchOutcome.Failed }
        val actions = listOfNotNull(
            NotificationAction.createSimple("Show in Folder") { RevealFileAction.openDirectory(folder) },
            if (details.isEmpty()) null else NotificationAction.createSimple("Show Details") {
                Messages.showInfoMessage(project, details.joinToString("\n"), "Batch Recording Details")
            },
        )
        SnippetRunner.notify(
            project, html(batchSummary(outcomes, total, cancelled)),
            if (failed) NotificationType.WARNING else NotificationType.INFORMATION, *actions.toTypedArray(),
        )
    }

    /** The resolved ffmpeg, or null after telling the user it is missing, with a link to the setting. */
    private fun ffmpegOrNotify(settings: TypeWriterSettings.State): FfmpegStatus.Found? {
        val status = locateFfmpeg(settings.ffmpegPath)
        if (status is FfmpegStatus.Found) return status
        SnippetRunner.notify(
            project, html("cannot record: ffmpeg ${status.describe()}"), NotificationType.ERROR,
            NotificationAction.createSimpleExpiring("Open Settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, TypeWriterConfigurable::class.java)
            },
        )
        return null
    }

    private fun savedOptions(settings: TypeWriterSettings.State, output: Path) = RecordOptions(
        output = output,
        width = settings.recordWidth,
        height = settings.recordHeight,
        fontSize = defaultFontSize(settings.recordFontSize),
        fps = settings.recordFps,
        holdBeforeMs = settings.recordHoldBeforeMs,
        holdAfterMs = settings.recordHoldAfterMs,
    )

    private fun save(settings: TypeWriterSettings.State, options: RecordOptions) {
        settings.recordWidth = options.width
        settings.recordHeight = options.height
        settings.recordFontSize = options.fontSize
        settings.recordFps = options.fps
        settings.recordHoldBeforeMs = options.holdBeforeMs
        settings.recordHoldAfterMs = options.holdAfterMs
    }
}
