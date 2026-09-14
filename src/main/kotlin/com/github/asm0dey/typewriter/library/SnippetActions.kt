package com.github.asm0dey.typewriter.library

import com.github.asm0dey.typewriter.format.SnippetFormatter
import com.github.asm0dey.typewriter.model.Snippet
import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.parse.MarkerParser
import com.github.asm0dey.typewriter.parse.MarkerScanner
import com.github.asm0dey.typewriter.run.BaseIndent
import com.github.asm0dey.typewriter.run.Check
import com.github.asm0dey.typewriter.run.PreFlight
import com.github.asm0dey.typewriter.run.RunService
import com.github.asm0dey.typewriter.run.blocked
import com.github.asm0dey.typewriter.ui.TypeWriterProjectSettings
import com.github.asm0dey.typewriter.ui.TypeWriterSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import java.nio.file.Paths

/**
 * Resolves the two layered snippet directories (spec section 8, "Directories") from settings.
 * The single place that knows how to turn [TypeWriterSettings]/[TypeWriterProjectSettings]
 * paths into [VirtualFile]s -- Tasks 13, 14 and 17 all consume this instead of re-resolving
 * settings themselves.
 */
object SnippetDirs {

    fun global(): VirtualFile? {
        val path = ApplicationManager.getApplication()
            .getService(TypeWriterSettings::class.java).state.globalDir
        return LocalFileSystem.getInstance().findFileByNioFile(Paths.get(path))
    }

    fun project(project: Project): VirtualFile? {
        val relative = project.getService(TypeWriterProjectSettings::class.java).state.projectDir
        val base = project.basePath ?: return null
        return LocalFileSystem.getInstance().findFileByNioFile(Paths.get(base).resolve(relative))
    }

    fun all(project: Project): List<Snippet> = SnippetLibrary.collect(global(), project(project))
}

/**
 * Runs the whole pipeline for one snippet: read the [Snippet]'s live [com.intellij.openapi.editor.Document]
 * (never its bytes), format it, scan and parse its markers, compute the base indent at the
 * target caret, pre-flight, then hand the resulting steps to [RunService]. Shared by
 * [TypeSnippetAction] and the sequence actions ([TypeNextAction]/[TypePreviousAction]) so both
 * entry points go through exactly one path.
 */
object SnippetRunner {

    private const val GROUP = "TypeWriter"

    fun run(project: Project, editor: Editor?, snippet: Snippet) {
        val settings = ApplicationManager.getApplication().getService(TypeWriterSettings::class.java)
        val text = SnippetLibrary.textOf(snippet)
        if (text == null) {
            notify(project, "${snippet.relativePath} has no readable text", NotificationType.ERROR)
            return
        }

        var formatWarning: String? = null
        var source = text
        val psiForDirectives = PsiFileFactory.getInstance(project)
            .createFileFromText(snippet.file.name, snippet.fileType, text, 0L, true)
        val probe = MarkerParser.parse(
            text, MarkerScanner.scan(psiForDirectives, settings.state.sentinel), settings.state.sentinel,
        )
        if (settings.state.formatOnPlay && !probe.directives.raw) {
            val result = SnippetFormatter.format(project, snippet.fileType, snippet.file.name, text)
            source = result.text
            formatWarning = result.warning
        }

        val psi = PsiFileFactory.getInstance(project)
            .createFileFromText(snippet.file.name, snippet.fileType, source, 0L, true)
        val program = MarkerParser.parse(
            source, MarkerScanner.scan(psi, settings.state.sentinel), settings.state.sentinel,
        )

        val checks = PreFlight.check(editor, snippet, program, formatWarning)
        checks.filterIsInstance<Check.Warning>().forEach {
            notify(project, it.message, NotificationType.WARNING)
        }
        if (checks.blocked()) {
            checks.filterIsInstance<Check.Error>().forEach {
                notify(project, it.message, NotificationType.ERROR)
            }
            return
        }
        val target = editor ?: return

        val psiTarget = PsiDocumentManager.getInstance(project).getPsiFile(target.document)
        val offset = target.caretModel.offset
        val indent = if (psiTarget == null) "" else
            BaseIndent.compute(project, psiTarget, target.document, offset)
        val column = BaseIndent.caretColumn(target.document, offset)

        val steps = program.steps.map { step ->
            if (step is Step.Type) Step.Type(BaseIndent.apply(step.text, indent, column)) else step
        }
        val timing = program.directives.timing(settings.defaultTiming())
        project.getService(RunService::class.java).launch(target, steps, timing)
    }

    fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP)
            .createNotification(message, type)
            .notify(project)
    }
}

/**
 * One per snippet, id [SnippetLibrary.actionId] of [relativePath]. Resolves the actual [Snippet]
 * at invoke time against the focused project (spec section 8, "Actions"), so a single keymap
 * binding drives the corresponding snippet in every demo project -- the action never captures a
 * specific [VirtualFile] at registration time.
 */
class TypeSnippetAction(private val relativePath: String) : AnAction("Type: $relativePath"), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val snippet = SnippetDirs.all(project).firstOrNull { it.relativePath == relativePath } ?: return
        SnippetRunner.run(project, e.getData(CommonDataKeys.EDITOR), snippet)
    }
}

/**
 * Drives [RunService.cursor] over the talk's ordered sequence (spec section 8, "Sequence" --
 * project directory only). `cursor` is the index of the snippet that will be typed NEXT: Type
 * Next types it and advances by one; Type Previous steps back to the snippet before the last one
 * played (cursor - 2, since cursor already points one past it) and re-advances by one. Both ends
 * clamp rather than wrap or error: an empty sequence is reported and nothing plays.
 */
private object SequenceRunner {
    fun playAt(e: AnActionEvent, index: Int) {
        val project = e.project ?: return
        val sequence = SnippetLibrary.sequence(SnippetDirs.project(project))
        if (sequence.isEmpty()) {
            SnippetRunner.notify(project, "no snippets in the project directory", NotificationType.WARNING)
            return
        }
        val clamped = index.coerceIn(0, sequence.size - 1)
        val service = project.getService(RunService::class.java)
        SnippetRunner.run(project, e.getData(CommonDataKeys.EDITOR), sequence[clamped])
        service.cursor = (clamped + 1).coerceAtMost(sequence.size - 1)
    }
}

class TypeNextAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        SequenceRunner.playAt(e, project.getService(RunService::class.java).cursor)
    }
}

class TypePreviousAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        SequenceRunner.playAt(e, project.getService(RunService::class.java).cursor - 2)
    }
}

class UndoRunAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
            project.getService(RunService::class.java).canUndoLastRun()
    }
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.getService(RunService::class.java)?.undoLastRun()
    }
}
