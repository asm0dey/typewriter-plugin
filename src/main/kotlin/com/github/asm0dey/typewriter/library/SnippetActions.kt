package com.github.asm0dey.typewriter.library

import com.github.asm0dey.typewriter.format.SnippetFormatter
import com.github.asm0dey.typewriter.model.Snippet
import com.github.asm0dey.typewriter.model.Step
import com.github.asm0dey.typewriter.parse.CommentSyntax
import com.github.asm0dey.typewriter.parse.MarkerParser
import com.github.asm0dey.typewriter.parse.MarkerScanner
import com.github.asm0dey.typewriter.run.BaseIndent
import com.github.asm0dey.typewriter.run.Check
import com.github.asm0dey.typewriter.run.PreFlight
import com.github.asm0dey.typewriter.run.RunService
import com.github.asm0dey.typewriter.run.blocked
import com.github.asm0dey.typewriter.ui.TypeWriterProjectSettings
import com.github.asm0dey.typewriter.ui.TypeWriterSettings
import com.intellij.lang.Language
import com.intellij.lang.LanguageUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import java.io.IOException
import java.nio.file.Paths

/**
 * Resolves the two layered snippet directories (spec section 8, "Directories") from settings.
 * The single place that knows how to turn [TypeWriterSettings]/[TypeWriterProjectSettings]
 * paths into [VirtualFile]s -- Tasks 13, 14 and 17 all consume this instead of re-resolving
 * settings themselves.
 */
object SnippetDirs {

    /**
     * The configured global directory's path, independent of whether it currently exists or
     * resolves in the VFS. [SnippetWatcher] needs this distinction: the directory a VFS event
     * moved or renamed *away from* no longer resolves via [global], but the event must still be
     * recognised as touching the configured location.
     */
    fun globalPath(): String = ApplicationManager.getApplication()
        .getService(TypeWriterSettings::class.java).state.globalDir

    fun global(): VirtualFile? = LocalFileSystem.getInstance().findFileByNioFile(Paths.get(globalPath()))

    /**
     * Same distinction as [globalPath], for the given project's own directory.
     *
     * Null for a blank setting, deliberately: `Path.resolve("")` yields the project's own base
     * directory, which would silently make the WHOLE project the snippet directory -- every file
     * in it a registered snippet action, and every save inside it a snippet-library resync.
     */
    fun projectPath(project: Project): String? {
        val relative = project.getService(TypeWriterProjectSettings::class.java).state.projectDir
        if (relative.isBlank()) return null
        val base = project.basePath ?: return null
        return Paths.get(base).resolve(relative).toString()
    }

    fun project(project: Project): VirtualFile? =
        projectPath(project)?.let { LocalFileSystem.getInstance().findFileByNioFile(Paths.get(it)) }

    fun all(project: Project): List<Snippet> = SnippetLibrary.collect(global(), project(project))

    /**
     * The directory a newly created snippet belongs in, creating the configured directory when
     * nothing exists there yet.
     *
     * Both configured paths default to a non-blank value (`~/.typewriter`, and the
     * project-relative `.typewriter`), while [global] and [project] resolve through the VFS and
     * return null for a path with no directory behind it. So on a fresh install the normal state
     * is *configured but absent*, not unconfigured -- and reporting that as "no snippet directory,
     * set one in Settings" told the user to set a directory that was already set. Creating it is
     * what they meant by asking for a new snippet.
     *
     * An existing directory always wins over creating one, and the project's wins over the global
     * one (a snippet created during talk prep almost always belongs to the talk). The refresh
     * before creating catches a directory made outside the IDE; [global]/[project] deliberately
     * do NOT refresh, because [com.github.asm0dey.typewriter.ide.SnippetHighlighting] calls them
     * per file, where a synchronous VFS refresh would be far too expensive.
     *
     * Returns null only when there is genuinely nothing to use -- a blank project setting (or no
     * project base) AND a blank global path -- which is the only case that warrants telling the
     * user to configure something. Throws [IOException] if creation itself fails; the caller
     * reports that distinctly, since "could not create" is a different problem from "not set".
     */
    @Throws(IOException::class)
    fun forNewSnippet(project: Project): VirtualFile? {
        project(project)?.let { return it }
        global()?.let { return it }
        val target = projectPath(project) ?: globalPath().takeIf { it.isNotBlank() } ?: return null
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Paths.get(target))?.let { return it }
        return WriteAction.compute<VirtualFile, IOException> { VfsUtil.createDirectoryIfMissing(target) }
    }
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

        // A comment-less snippet's markers -- MarkerScanner.scan returns none for exactly this
        // case -- so MarkerParser never builds directives for it; probe.directives/program.directives
        // are always Directives() below. Its real directives, when it has any, live in its
        // .twmeta sidecar instead (spec section 9, resolved design question 22) -- null here means
        // "this snippet has comments; trust the marker-parsed directives as usual".
        val syntax = CommentSyntax.of(LanguageUtil.getFileTypeLanguage(snippet.fileType) ?: Language.ANY)
        val sidecarDirectives = if (syntax.hasAny) null else DirectiveSidecar.read(snippet.file)

        var formatWarning: String? = null
        var source = text
        val psiForDirectives = PsiFileFactory.getInstance(project)
            .createFileFromText(snippet.file.name, snippet.fileType, text, 0L, true)
        val probe = MarkerParser.parse(
            text, MarkerScanner.scan(psiForDirectives, settings.state.sentinel), settings.state.sentinel,
        )
        if (settings.state.formatOnPlay && !(sidecarDirectives ?: probe.directives).raw) {
            val result = SnippetFormatter.format(project, snippet.fileType, snippet.file.name, text)
            source = result.text
            formatWarning = result.warning
        }

        // Formatting left the text unchanged (raw, formatOnPlay off, or SnippetFormatter itself
        // degraded and returned the original text -- see its three fallback branches, which all
        // hand back the same `text` reference) -- probe already parsed exactly this text, so
        // reuse it instead of scanning and parsing an identical source a second time.
        val program = if (source === text) {
            probe
        } else {
            val psi = PsiFileFactory.getInstance(project)
                .createFileFromText(snippet.file.name, snippet.fileType, source, 0L, true)
            MarkerParser.parse(source, MarkerScanner.scan(psi, settings.state.sentinel), settings.state.sentinel)
        }

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
        // PreFlight.check returns a blocking Check.Error when editor is null (its very first
        // check), and the blocked() branch above already returned in that case -- this documents
        // that invariant rather than handling a case reachable from here.
        val target = editor!!

        val psiTarget = PsiDocumentManager.getInstance(project).getPsiFile(target.document)
        val offset = target.caretModel.offset
        val indent = if (psiTarget == null) "" else
            BaseIndent.compute(project, psiTarget, target.document, offset)
        val column = BaseIndent.caretColumn(target.document, offset)

        val steps = program.steps.map { step ->
            if (step is Step.Type) Step.Type(BaseIndent.apply(step.text, indent, column)) else step
        }
        val timing = (sidecarDirectives ?: program.directives).timing(settings.defaultTiming())
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
 * played (cursor - 2, since cursor already points one past it) and re-advances by one.
 *
 * Both ends clamp rather than wrap or error. Wrapping would silently restart the demo from step 1
 * in front of an audience -- the same class of surprise the spec explicitly rejects when it
 * refuses to persist the cursor across a restart ("reopening the IDE mid-talk silently resumes at
 * step 7"). Erroring is worse still. Clamping means nothing happens at either end, which the
 * speaker notices immediately and can recover from -- e.g. via the picker's "start sequence here"
 * (spec section 8). An empty sequence is reported and nothing plays.
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
