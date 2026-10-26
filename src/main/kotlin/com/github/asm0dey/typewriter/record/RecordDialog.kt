package com.github.asm0dey.typewriter.record

import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.ui.FormBuilder
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import kotlin.io.path.exists

/** `~/Movies` on macOS, `~/Videos` elsewhere; the home directory when that doesn't exist. */
fun defaultVideoDir(): Path {
    val home = Path.of(System.getProperty("user.home"))
    val videos = home.resolve(if (SystemInfo.isMac) "Movies" else "Videos")
    return if (Files.isDirectory(videos)) videos else home
}

fun defaultOutput(recordDir: String, snippetName: String): Path =
    (if (recordDir.isBlank()) defaultVideoDir() else Path.of(recordDir)).resolve("$snippetName.mp4")

private const val CUSTOM = "Custom"
private const val FIT = "Fit to code"
private val PRESETS = mapOf("3840×2160" to (3840 to 2160), "1920×1080" to (1920 to 1080), "1280×720" to (1280 to 720))

/** Fit to code's max ratios, width / height; 0 = no limit. */
internal val RATIOS = mapOf("16:9" to 16.0 / 9, "4:3" to 4.0 / 3, "1:1" to 1.0, "9:16" to 9.0 / 16, "No limit" to 0.0)

internal fun ratioLabel(ratio: Double): String = RATIOS.entries.firstOrNull { it.value == ratio }?.key ?: "16:9"

/** Size, font, FPS and holds: the settings a single recording and a batch share. */
class RecordSettingsFields(initial: RecordOptions) {
    private val width = JSpinner(SpinnerNumberModel(initial.width, 2, MAX_WIDTH, 2))
    private val height = JSpinner(SpinnerNumberModel(initial.height, 2, MAX_HEIGHT, 2))
    private val size = ComboBox((PRESETS.keys + FIT + CUSTOM).toTypedArray()).apply {
        selectedItem = if (initial.fit) FIT else PRESETS.entries.firstOrNull { it.value == initial.width to initial.height }?.key ?: CUSTOM
        addActionListener { syncSize() }
    }
    private val ratio = ComboBox(RATIOS.keys.toTypedArray()).apply { selectedItem = ratioLabel(initial.maxRatio) }
    private val fontSize = JSpinner(SpinnerNumberModel(initial.fontSize, 1, 200, 1))
    private val fps = JSpinner(SpinnerNumberModel(initial.fps, 1, 120, 1))
    private val holdBefore = JSpinner(SpinnerNumberModel(initial.holdBeforeMs, 0, 60_000, 100))
    private val holdAfter = JSpinner(SpinnerNumberModel(initial.holdAfterMs, 0, 60_000, 100))

    init {
        syncSize()
    }

    /** A preset shows its size in the spinners and locks them; Fit to code locks them too and unlocks its ratio; Custom unlocks them. */
    private fun syncSize() {
        val preset = PRESETS[size.selectedItem]
        if (preset != null) {
            width.value = preset.first
            height.value = preset.second
        }
        width.isEnabled = size.selectedItem == CUSTOM
        height.isEnabled = size.selectedItem == CUSTOM
        ratio.isEnabled = size.selectedItem == FIT
    }

    fun addTo(form: FormBuilder): FormBuilder = form
        .addLabeledComponent("Size:", size)
        .addLabeledComponent("Width:", width)
        .addLabeledComponent("Height:", height)
        .addLabeledComponent("Max ratio:", ratio)
        .addLabeledComponent("Font size:", fontSize)
        .addLabeledComponent("FPS:", fps)
        .addLabeledComponent("Hold before (ms):", holdBefore)
        .addLabeledComponent("Hold after (ms):", holdAfter)

    private fun fit() = size.selectedItem == FIT

    fun validate(): ValidationInfo? = if (fit()) null else sizeError(width.value as Int, height.value as Int)?.let { ValidationInfo(it, width) }

    fun options(output: Path): RecordOptions = RecordOptions(
        output = output,
        width = width.value as Int,
        height = height.value as Int,
        fontSize = fontSize.value as Int,
        fps = fps.value as Int,
        holdBeforeMs = holdBefore.value as Int,
        holdAfterMs = holdAfter.value as Int,
        fit = fit(),
        maxRatio = RATIOS.getValue(ratio.selectedItem as String),
    )
}

class RecordDialog(private val project: Project, initial: RecordOptions) : DialogWrapper(project) {
    private val output = TextFieldWithBrowseButton().apply {
        text = initial.output.toString()
        addActionListener {
            val current = outputPath()
            FileChooserFactory.getInstance()
                .createSaveFileDialog(
                    FileSaverDescriptor("Record Snippet to Video", "").apply { withExtensionFilter("Video", "mp4", "gif") },
                    project,
                )
                .save(current?.parent, current?.fileName?.toString())
                ?.let { text = it.file.path }
        }
    }
    private val fields = RecordSettingsFields(initial)

    init {
        title = "Record Snippet to Video"
        init()
    }

    private fun outputPath(): Path? = runCatching { Path.of(output.text.trim()) }.getOrNull()?.takeIf { it.isAbsolute }

    override fun createCenterPanel(): JComponent =
        fields.addTo(FormBuilder.createFormBuilder().addLabeledComponent("Output:", output)).panel

    override fun doValidate(): ValidationInfo? {
        fields.validate()?.let { return it }
        val out = outputPath() ?: return ValidationInfo("output must be an absolute path", output)
        if (VideoFormat.of(out) == null) return ValidationInfo("output must end in .mp4 or .gif", output)
        return null
    }

    override fun doOKAction() {
        val out = outputPath() ?: return
        if (out.exists() &&
            Messages.showYesNoDialog(project, "Replace ${out.fileName}?", title, null) != Messages.YES
        ) return
        super.doOKAction()
    }

    fun options(): RecordOptions = fields.options(outputPath()!!)
}
