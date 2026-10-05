package com.github.asm0dey.typewriter.record

import com.intellij.ide.ui.UISettingsUtils
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
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
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * An editor tab that lives in no window: a copy of [source]'s text in a fresh editor, wrapped in
 * JBTabs so the frame looks like the IDE, painted into images by the recorder. EDT only.
 */
class RecordingStage(project: Project, source: Editor, width: Int, height: Int, fontSize: Int, parent: Disposable) {
    companion object {
        /** Set on a stage's editor, so a live run's [com.github.asm0dey.typewriter.run.AbortWatcher] can ignore it. */
        val STAGE: Key<Boolean> = Key.create("typewriter.recordingStage")
    }

    val editor: EditorEx
    val component: JComponent

    init {
        val sourceFile = FileDocumentManager.getInstance().getFile(source.document)
        val name = sourceFile?.name ?: "untitled"
        val fileType = sourceFile?.fileType ?: PlainTextFileType.INSTANCE
        // A live run replaces the selection before typing (RunService.replaceSelection), so the copy starts that way.
        val selection = source.selectionModel
        val text = source.document.text
        val (initialText, caret) =
            if (selection.hasSelection()) text.removeRange(selection.selectionStart, selection.selectionEnd) to selection.selectionStart
            else text to source.caretModel.offset
        val file = LightVirtualFile(name, fileType, initialText)
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        editor = EditorFactory.getInstance().createEditor(document, project, file, false) as EditorEx
        editor.putUserData(STAGE, true)
        Disposer.register(parent) { EditorFactory.getInstance().releaseEditor(editor) }

        editor.settings.isLineNumbersShown = true
        editor.settings.isUseSoftWraps = source.settings.isUseSoftWraps
        editor.settings.isWhitespacesShown = source.settings.isWhitespacesShown
        editor.scrollingModel.disableAnimation()
        // EditorEx.setFontSize writes the editor's own scheme delegate, never the global scheme.
        editor.setFontSize(fontSize * UISettingsUtils.getInstance().currentIdeScale)
        editor.caretModel.moveToOffset(caret)

        val tabs = JBTabsFactory.createEditorTabs(project, parent)
        tabs.addTab(TabInfo(editor.component).setText(name).setIcon(fileType.icon))
        // A detached component has no PROJECT in its DataContext, and ReformatCode needs it.
        component = object : JPanel(BorderLayout()), UiDataProvider {
            override fun uiDataSnapshot(sink: DataSink) {
                sink[CommonDataKeys.PROJECT] = project
            }
        }
        component.add(tabs.component, BorderLayout.CENTER)
        component.setSize(width, height)
        // Neither revalidate() nor validate() does anything without a peer (no window), so lay the
        // tree out by hand, parents first so each child already has its final size.
        layOut(component)

        val y = editor.visualPositionToXY(editor.caretModel.visualPosition).y
        val h = editor.scrollingModel.visibleArea.height
        editor.scrollingModel.scrollVertically(if (y < h / 3) 0 else y - h / 3)
    }

    /**
     * Paints the frame into [into], then the caret, which the editor only paints for the focus owner.
     * Re-lays out first: typing resizes the editor and gutter, and their revalidate() is a no-op here.
     * The gutter's width update is queued on the EDT, so the caller must let the EDT run between frames.
     */
    fun paint(into: BufferedImage) {
        layOut(component)
        val g = into.createGraphics()
        try {
            component.paint(g)
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

    private fun layOut(c: Component) {
        c.doLayout()
        if (c is Container) c.components.forEach(::layOut)
    }
}
