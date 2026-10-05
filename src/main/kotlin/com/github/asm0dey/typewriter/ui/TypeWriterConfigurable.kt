package com.github.asm0dey.typewriter.ui

import com.github.asm0dey.typewriter.record.describe
import com.github.asm0dey.typewriter.record.locateFfmpeg
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * `Settings | Tools | TypeWriter`. Edits the app-level [TypeWriterSettings] service (spec
 * section 10): snippet directory, base delay, jitter, newline pause, marker sentinel, and
 * format-on-play. The project-scoped snippet directory override (spec section 8, "Directories":
 * `<project>/.typewriter` is a PROJECT setting) is a separate, nested page --
 * [TypeWriterProjectConfigurable] -- since an application-level `Configurable` has no `Project`
 * to read a project service from.
 *
 * The Swing fields below are `internal`, not `private`. A [Configurable] cannot be shown
 * headlessly -- there is no display to render a panel into during a test run -- so
 * `isModified`/`apply`/`reset`, the real substance of this class, are exercised in
 * `TypeWriterConfigurableTest` directly against these fields without ever calling
 * [createComponent] or showing a window. Setting `.text` / `.value` / `.isSelected` on a Swing
 * component works fine headless; only painting one needs a real display.
 */
class TypeWriterConfigurable : Configurable {

    private val settings = ApplicationManager.getApplication().getService(TypeWriterSettings::class.java)

    internal val globalDir = TextFieldWithBrowseButton().apply {
        // Title on the DESCRIPTOR, not as a separate argument: the (title, description, project,
        // descriptor) overload is deprecated and marked for removal, and it is also what logged
        // "BrowseFolderRunnable - multiple selection not supported" on every CI run -- it builds a
        // chooser whose selection mode does not match the single-folder descriptor it was handed.
        addBrowseFolderListener(
            null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Snippet Directory"),
        )
    }
    internal val ffmpegPath = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            null,
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withTitle("ffmpeg Executable"),
        )
    }
    internal val ffmpegStatus = JBLabel()
    internal val speed = JSpinner(SpinnerNumberModel(100, 0, 5000, 10))
    internal val jitter = JSpinner(SpinnerNumberModel(20, 0, 5000, 5))
    internal val newline = JSpinner(SpinnerNumberModel(300, 0, 5000, 50))
    internal val sentinel = JBTextField()
    internal val formatOnPlay = JBCheckBox("Format snippets before typing")

    override fun getDisplayName() = "TypeWriter"

    override fun createComponent(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent("Snippet directory:", globalDir)
            .addLabeledComponent("Base delay (ms):", speed)
            .addLabeledComponent("Jitter (ms):", jitter)
            .addLabeledComponent("Newline pause (ms):", newline)
            .addLabeledComponent("Marker sentinel:", sentinel)
            .addComponent(formatOnPlay)
            .addLabeledComponent("ffmpeg path:", ffmpegPath)
            .addComponentToRightColumn(ffmpegStatus)
            .panel

    // `with(settings.state)` brings State's globalDir/sentinel/formatOnPlay properties into
    // scope, which SHADOWS this class's same-named Swing fields for an unqualified reference --
    // Kotlin resolves the implicit receiver's members before the enclosing class's. Every field
    // whose name collides with a State property name must be qualified with
    // `this@TypeWriterConfigurable.`; speed/jitter/newline don't collide (their State
    // counterparts carry an "Ms" suffix) so they read unqualified.
    override fun isModified(): Boolean = with(settings.state) {
        this@TypeWriterConfigurable.globalDir.text != this.globalDir ||
            speed.value != speedMs ||
            jitter.value != jitterMs ||
            newline.value != newlineMs ||
            this@TypeWriterConfigurable.sentinel.text != this.sentinel ||
            this@TypeWriterConfigurable.formatOnPlay.isSelected != this.formatOnPlay ||
            this@TypeWriterConfigurable.ffmpegPath.text != this.ffmpegPath
    }

    // A blank sentinel makes `trimmed.startsWith(sentinel)` (MarkerParser) true for every
    // comment in every snippet -- every comment becomes a marker, is consumed at parse time, and
    // never reaches the typed output. That's a silent, live-demo-breaking failure with no error
    // anywhere, so it is rejected outright here rather than substituted with a default: a
    // substitution would hide the mistake instead of surfacing it. Thrown from apply(), the
    // platform way to fail a Configurable -- the Settings dialog shows the message and does not
    // close or persist.
    override fun apply() {
        if (sentinel.text.isBlank()) {
            throw ConfigurationException("Marker sentinel must not be blank.")
        }
        settings.loadState(
            settings.state.copy(
                globalDir = globalDir.text,
                speedMs = speed.value as Int,
                jitterMs = jitter.value as Int,
                newlineMs = newline.value as Int,
                sentinel = sentinel.text,
                formatOnPlay = formatOnPlay.isSelected,
                ffmpegPath = ffmpegPath.text,
            )
        )
        // Saved even if it doesn't resolve: the user may be configuring a machine ffmpeg isn't on yet.
        ffmpegStatus.text = locateFfmpeg(ffmpegPath.text).describe()
    }

    override fun reset() = with(settings.state) {
        this@TypeWriterConfigurable.globalDir.text = this.globalDir
        speed.value = speedMs
        jitter.value = jitterMs
        newline.value = newlineMs
        this@TypeWriterConfigurable.sentinel.text = this.sentinel
        this@TypeWriterConfigurable.formatOnPlay.isSelected = this.formatOnPlay
        this@TypeWriterConfigurable.ffmpegPath.text = this.ffmpegPath
        ffmpegStatus.text = ""
    }
}
