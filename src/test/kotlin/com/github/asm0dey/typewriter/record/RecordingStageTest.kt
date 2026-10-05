package com.github.asm0dey.typewriter.record

import com.github.asm0dey.typewriter.TypeWriterFixtureTestCase
import com.intellij.ide.DataManager
import com.intellij.ide.ui.UISettingsUtils
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.RunInEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Point
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.math.abs

@RunInEdt(writeIntent = true)
class RecordingStageTest : TypeWriterFixtureTestCase() {

    private fun withStage(width: Int = 1280, height: Int = 720, fontSize: Int = 14, block: (RecordingStage) -> Unit) {
        val parent = Disposer.newDisposable()
        try {
            block(RecordingStage(fixture.project, fixture.editor, width, height, fontSize, parent))
        } finally {
            Disposer.dispose(parent)
        }
    }

    private fun longFile(caretLine: Int) {
        val text = (0 until 200).joinToString("\n") { if (it == caretLine) "<caret>// line $it" else "// line $it" }
        fixture.configureByText("Long.java", text)
    }

    @Test
    fun testCopiesTextNameAndCaretWithoutTouchingSource() {
        fixture.configureByText("Foo.java", "class Foo {<caret>}")
        val source = fixture.editor
        withStage { stage ->
            assertEquals(source.document.text, stage.editor.document.text)
            assertEquals("Foo.java", FileDocumentManager.getInstance().getFile(stage.editor.document)!!.name)
            assertEquals(source.caretModel.offset, stage.editor.caretModel.offset)
            WriteCommandAction.runWriteCommandAction(fixture.project) {
                stage.editor.document.insertString(stage.editor.caretModel.offset, "int x;")
            }
            assertEquals("class Foo {}", source.document.text)
        }
    }

    @Test
    fun testDefaultFontSizeIsTheGlobalSchemeNotTheScaledEditor() {
        fixture.configureByText("Foo.java", "class Foo {}")
        val global = EditorColorsManager.getInstance().globalScheme.editorFontSize
        // What the live editor reports under Presentation Mode / IDE zoom: already scaled.
        (fixture.editor as EditorEx).setFontSize(global * 2)
        assertEquals(global, defaultFontSize(0))
        assertEquals(17, defaultFontSize(17))
    }

    @Test
    fun testFontSizeFollowsIdeScale() {
        fixture.configureByText("Foo.java", "class Foo {}")
        val globalSize = EditorColorsManager.getInstance().globalScheme.editorFontSize2D
        withStage(fontSize = 20) { stage ->
            assertEquals(20f * UISettingsUtils.getInstance().currentIdeScale, stage.editor.colorsScheme.editorFontSize2D)
            assertEquals(globalSize, EditorColorsManager.getInstance().globalScheme.editorFontSize2D)
        }
    }

    @Test
    fun testProjectIsInDataContext() {
        fixture.configureByText("Foo.java", "class Foo {}")
        withStage { stage ->
            val context = DataManager.getInstance().getDataContext(stage.editor.contentComponent)
            assertSame(fixture.project, context.getData(CommonDataKeys.PROJECT))
        }
    }

    @Test
    fun testCaretNearTopStartsAtLineOne() {
        longFile(caretLine = 1)
        withStage { stage -> assertEquals(0, stage.editor.scrollingModel.visibleArea.y) }
    }

    @Test
    fun testCaretDeepInFileSitsInUpperThird() {
        longFile(caretLine = 150)
        withStage { stage ->
            val editor = stage.editor
            val area = editor.scrollingModel.visibleArea
            val caretY = editor.visualPositionToXY(editor.caretModel.visualPosition).y
            assertTrue(area.y > 0, "expected a scrolled view, got $area")
            assertTrue(abs((caretY - area.y) - area.height / 3) <= editor.lineHeight, "caretY=$caretY area=$area")
        }
    }

    @Test
    fun testPaintFillsFrameAndDrawsCaret() {
        fixture.configureByText("Foo.java", "class Foo {<caret>}")
        withStage(1280, 720) { stage ->
            val img = BufferedImage(1280, 720, BufferedImage.TYPE_3BYTE_BGR)
            stage.paint(img)
            assertEquals(1280, img.width)
            assertEquals(720, img.height)
            val editor = stage.editor
            val p = SwingUtilities.convertPoint(
                editor.contentComponent, editor.visualPositionToXY(editor.caretModel.visualPosition), stage.component,
            )
            val caretColor = editor.colorsScheme.getColor(EditorColors.CARET_COLOR)!!
            val centre = Point(p.x + editor.settings.lineCursorWidth / 2, p.y + editor.lineHeight / 2)
            assertEquals(caretColor.rgb and 0xFFFFFF, img.getRGB(centre.x, centre.y) and 0xFFFFFF)
        }
    }

    @Test
    fun testGutterSurvivesGrowthPastOneHundredLines() {
        fixture.configureByText("Foo.java", "class Foo {\n<caret>\n}")
        withStage(1280, 720) { stage ->
            val editor = stage.editor
            val img = BufferedImage(1280, 720, BufferedImage.TYPE_3BYTE_BGR)
            stage.paint(img)
            val startWidth = editor.gutterComponentEx.width
            repeat(150) { i ->
                WriteCommandAction.runWriteCommandAction(fixture.project) {
                    editor.document.insertString(editor.caretModel.offset, "int f$i;\n")
                    editor.caretModel.moveToOffset(editor.caretModel.offset + "int f$i;\n".length)
                }
                editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                stage.paint(img)
            }
            val gutter = editor.gutterComponentEx
            assertEquals(editor.contentComponent.height, gutter.height)
            assertTrue(gutter.width > startWidth, "gutter width ${gutter.width} did not grow from $startWidth")
            val caretRow = SwingUtilities.convertPoint(
                editor.contentComponent, editor.visualPositionToXY(editor.caretModel.visualPosition), stage.component,
            ).y + editor.lineHeight / 2
            val gutterX = SwingUtilities.convertPoint(gutter, Point(0, 0), stage.component).x
            val background = editor.colorsScheme.defaultBackground.rgb and 0xFFFFFF
            assertTrue(
                (gutterX until gutterX + gutter.width).any { (img.getRGB(it, caretRow) and 0xFFFFFF) != background },
                "gutter row at y=$caretRow is blank",
            )
        }
    }
}
