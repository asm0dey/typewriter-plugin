package com.github.asm0dey.typewriter.record

import com.github.asm0dey.typewriter.model.Snippet
import com.intellij.ide.ui.UISettingsUtils
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.command.undo.UndoUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** What a stage starts from: its file and caret, plus the display settings to copy when there is a source editor. */
internal class StageStart(val file: LightVirtualFile, val caret: Int, val from: Editor?)

/** A stage over an empty file named after [snippet] ([cleanClassName]), for a batch video. */
fun emptyStage(project: Project, snippet: Snippet, width: Int, height: Int, fontSize: Int, parent: Disposable) =
    RecordingStage(
        project, StageStart(LightVirtualFile(cleanClassName(snippet.file.name), snippet.fileType, ""), 0, null),
        width, height, fontSize, parent,
    )

/**
 * An editor tab that lives in no window: a copy of a source editor's text (or an empty file) in a
 * fresh editor, wrapped in JBTabs so the frame looks like the IDE, painted into images by the
 * recorder. EDT only.
 */
class RecordingStage internal constructor(
    project: Project, start: StageStart, width: Int, height: Int, fontSize: Int, parent: Disposable,
) {
    constructor(project: Project, source: Editor, width: Int, height: Int, fontSize: Int, parent: Disposable) :
        this(project, copyOf(source), width, height, fontSize, parent)

    companion object {
        /** Set on a stage's editor, so a live run's [com.github.asm0dey.typewriter.run.AbortWatcher] can ignore it. */
        val STAGE: Key<Boolean> = Key.create("typewriter.recordingStage")

        private fun copyOf(source: Editor): StageStart {
            val sourceFile = FileDocumentManager.getInstance().getFile(source.document)
            val name = sourceFile?.name ?: "untitled"
            val fileType = sourceFile?.fileType ?: PlainTextFileType.INSTANCE
            // A live run replaces the selection before typing (RunService.replaceSelection), so the copy starts that way.
            val selection = source.selectionModel
            val text = source.document.text
            val (initialText, caret) =
                if (selection.hasSelection()) text.removeRange(selection.selectionStart, selection.selectionEnd) to selection.selectionStart
                else text to source.caretModel.offset
            return StageStart(LightVirtualFile(name, fileType, initialText), caret, source)
        }
    }

    val editor: EditorEx
    val component: JComponent
    private val tabs: JComponent

    init {
        val file = start.file
        val name = file.name
        val fileType = file.fileType
        val caret = start.caret
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        UndoUtil.disableUndoFor(document)
        editor = EditorFactory.getInstance().createEditor(document, project, file, false) as EditorEx
        editor.putUserData(STAGE, true)
        Disposer.register(parent) { EditorFactory.getInstance().releaseEditor(editor) }

        editor.settings.isLineNumbersShown = true
        start.from?.let {
            editor.settings.isUseSoftWraps = it.settings.isUseSoftWraps
            editor.settings.isWhitespacesShown = it.settings.isWhitespacesShown
        }
        editor.scrollingModel.disableAnimation()
        // EditorEx.setFontSize writes the editor's own scheme delegate, never the global scheme.
        editor.setFontSize(fontSize * UISettingsUtils.getInstance().currentIdeScale)
        editor.caretModel.moveToOffset(caret)

        val jbTabs = JBTabsFactory.createEditorTabs(project, parent)
        jbTabs.addTab(TabInfo(editor.component).setText(name).setIcon(fileType.icon))
        tabs = jbTabs.component
        // A detached component has no PROJECT in its DataContext, and ReformatCode needs it.
        component = object : JPanel(BorderLayout()), UiDataProvider {
            override fun uiDataSnapshot(sink: DataSink) {
                sink[CommonDataKeys.PROJECT] = project
            }
        }
        component.add(tabs, BorderLayout.CENTER)
        component.setSize(width, height)
        // Neither revalidate() nor validate() does anything without a peer (no window), so lay the
        // tree out by hand, parents first so each child already has its final size.
        layOut(component)

        val y = editor.visualPositionToXY(editor.caretModel.visualPosition).y
        val h = editor.scrollingModel.visibleArea.height
        editor.scrollingModel.scrollVertically(if (y < h / 3) 0 else y - h / 3)
    }

    /**
     * Paints the frame into [into], then the caret when [caret] (the editor only paints it for the focus owner).
     * Re-lays out first: typing resizes the editor and gutter, and their revalidate() is a no-op here.
     * The gutter's width update is queued on the EDT, so the caller must let the EDT run between frames.
     */
    fun paint(into: BufferedImage, caret: Boolean = true) {
        layOut(component)
        val g = into.createGraphics()
        try {
            component.paint(g)
            if (!caret) return
            val p = SwingUtilities.convertPoint(
                editor.contentComponent, editor.visualPositionToXY(editor.caretModel.visualPosition), component,
            )
            val width = if (editor.settings.isBlockCursor) EditorUtil.getPlainSpaceWidth(editor) else editor.settings.lineCursorWidth
            g.color = editor.colorsScheme.getColor(EditorColors.CARET_COLOR)
            g.fillRect(p.x, p.y, width, editor.lineHeight)
        } finally {
            g.dispose()
        }
    }

    /**
     * The frame that shows the whole document unscrolled: this stage's chrome (tab strip, gutter,
     * scroll bars) around every line in full, plus the margins the editor keeps around the caret
     * when it scrolls to it, so a caret at the end never scrolls the frame. Never narrower than the
     * tab strip wants.
     */
    fun fullSize(): Dimension {
        layOut(component)
        val area = editor.scrollingModel.visibleArea
        val document = editor.document
        val textWidth = (0 until document.lineCount).maxOfOrNull { editor.offsetToXY(document.getLineEndOffset(it)).x } ?: 0
        val textHeight = editor.offsetToXY(document.textLength).y + editor.lineHeight
        val columns = editor.settings.horizontalScrollOffset + 1
        val lines = editor.settings.verticalScrollOffset
        return Dimension(
            maxOf(component.width - area.width + textWidth + columns * EditorUtil.getPlainSpaceWidth(editor), tabs.preferredSize.width),
            component.height - area.height + textHeight + lines * editor.lineHeight + editor.lineHeight / 2,
        )
    }

    private fun layOut(c: Component) {
        c.doLayout()
        if (c is Container) c.components.forEach(::layOut)
    }
}
