package com.github.asm0dey.typewriter.record

import com.github.asm0dey.typewriter.model.Snippet
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CheckBoxList
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import kotlin.io.path.exists

/** Pick snippets by pattern and checkbox, a folder and the shared recording settings. */
class BatchRecordDialog(
    private val project: Project,
    private val snippets: List<Snippet>,
    initialPattern: String,
    initialFolder: Path,
    initialFormat: VideoFormat,
    initial: RecordOptions,
) : DialogWrapper(project) {
    private val pattern = JBTextField(initialPattern)
    private val list = CheckBoxList<Snippet>()
    private val selected = JBLabel()
    private val folder = TextFieldWithBrowseButton().apply {
        text = initialFolder.toString()
        addActionListener { chooseFolder() }
    }
    private val format = ComboBox(VideoFormat.entries.toTypedArray()).apply { selectedItem = initialFormat }
    private val fields = RecordSettingsFields(initial)

    init {
        title = "Record Snippets to Videos"
        snippets.forEach { list.addItem(it, "${it.relativePath}  →  ${cleanClassName(it.file.name)}", false) }
        list.setCheckBoxListListener { _, _ -> updateSelected() }
        // Editing the pattern re-applies it, discarding manual ticks.
        pattern.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applyPattern()
        })
        applyPattern()
        init()
    }

    private fun applyPattern() {
        snippets.forEach { list.setItemSelected(it, globMatches(pattern.text, it.relativePath)) }
        list.repaint()
        updateSelected()
    }

    private fun checked(): List<Snippet> = snippets.filter { list.isItemSelected(it) }

    private fun updateSelected() {
        val n = checked().size
        selected.text = "$n selected"
        isOKActionEnabled = n > 0
    }

    private fun folderPath(): Path? = runCatching { Path.of(folder.text.trim()) }.getOrNull()?.takeIf { it.isAbsolute }

    private fun chooseFolder() {
        val current = folderPath()?.let { LocalFileSystem.getInstance().findFileByNioFile(it) }
        FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor(), project, current)
            ?.let { folder.text = it.toNioPath().toString() }
    }

    override fun createCenterPanel(): JComponent {
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("Pattern:", pattern)
            .addComponent(JBScrollPane(list).apply { preferredSize = JBUI.size(520, 240) })
            .addComponent(selected)
            .addLabeledComponent("Output folder:", folder)
            .addLabeledComponent("Format:", format)
        return fields.addTo(form).panel
    }

    override fun getPreferredFocusedComponent(): JComponent = pattern

    override fun doValidate(): ValidationInfo? {
        fields.validate()?.let { return it }
        folderPath() ?: return ValidationInfo("output folder must be an absolute path", folder)
        return null
    }

    override fun doOKAction() {
        while (true) {
            val existing = items().map { it.output }.filter { it.exists() }
            if (existing.isEmpty()) break
            val shown = existing.take(20).joinToString("\n") +
                if (existing.size > 20) "\nand ${existing.size - 20} more" else ""
            when (Messages.showDialog(
                project, "These videos already exist:\n$shown", title,
                arrayOf("Overwrite", "Choose Another Folder", Messages.getCancelButton()), 0, Messages.getWarningIcon(),
            )) {
                0 -> break
                1 -> {
                    val before = folder.text
                    chooseFolder()
                    if (folder.text == before) return
                }
                else -> return
            }
        }
        super.doOKAction()
    }

    fun pattern(): String = pattern.text
    fun folder(): Path = folderPath()!!
    fun format(): VideoFormat = format.selectedItem as VideoFormat

    fun items(): List<BatchItem> {
        val chosen = checked()
        val targets = batchTargets(chosen.map { it.relativePath }, folder(), format())
        return chosen.map { BatchItem(it, targets.getValue(it.relativePath)) }
    }

    /** Output is the folder; [recordBatch] ignores it in favour of each item's own. */
    fun options(): RecordOptions = fields.options(folder())
}
