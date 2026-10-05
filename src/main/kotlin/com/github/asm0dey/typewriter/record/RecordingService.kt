package com.github.asm0dey.typewriter.record

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
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportSequentialProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        val status = locateFfmpeg(settings.ffmpegPath)
        if (status !is FfmpegStatus.Found) {
            SnippetRunner.notify(
                project, html("cannot record: ffmpeg ${status.describe()}"), NotificationType.ERROR,
                NotificationAction.createSimpleExpiring("Open Settings") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, TypeWriterConfigurable::class.java)
                },
            )
            return
        }
        recordingWarnings(snippet.relativePath, prepared.steps).forEach {
            SnippetRunner.notify(project, html(it.message), NotificationType.WARNING)
        }

        val initial = RecordOptions(
            output = defaultOutput(settings.recordDir, snippet.file.nameWithoutExtension),
            width = settings.recordWidth,
            height = settings.recordHeight,
            fontSize = defaultFontSize(settings.recordFontSize),
            fps = settings.recordFps,
            holdBeforeMs = settings.recordHoldBeforeMs,
            holdAfterMs = settings.recordHoldAfterMs,
        )
        val dialog = RecordDialog(project, initial)
        if (!dialog.showAndGet()) return
        val options = dialog.options()
        settings.recordDir = options.output.parent.toString()
        settings.recordWidth = options.width
        settings.recordHeight = options.height
        settings.recordFontSize = options.fontSize
        settings.recordFps = options.fps
        settings.recordHoldBeforeMs = options.holdBeforeMs
        settings.recordHoldAfterMs = options.holdAfterMs

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
}
