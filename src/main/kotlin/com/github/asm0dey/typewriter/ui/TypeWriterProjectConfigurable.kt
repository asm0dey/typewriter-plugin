package com.github.asm0dey.typewriter.ui

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

/**
 * `Settings | Tools | TypeWriter | Project`, nested under [TypeWriterConfigurable]. Spec section
 * 10 describes the snippet directory as "app level, overridable per project", and spec section 8
 * ("Directories") is explicit that `<project>/.typewriter` is a PROJECT setting, distinct from
 * the app-level `~/.typewriter` toolkit -- a project snippet shadows a global one with the same
 * relative path. That per-project scope is exactly what a `projectConfigurable` extension point
 * exists for: unlike [TypeWriterConfigurable] (`applicationConfigurable`, one instance for the
 * whole IDE), this class is instantiated once per open project, constructor-injected with that
 * [Project], so it can read and write [TypeWriterProjectSettings] -- an application-level
 * `Configurable` has no `Project` to resolve that service through.
 *
 * The Swing field is `internal`, not `private`, for the same reason as
 * [TypeWriterConfigurable]'s fields: a `Configurable` cannot be shown headlessly, so
 * `isModified`/`apply`/`reset` are exercised directly against it in
 * `TypeWriterProjectConfigurableTest` without ever calling [createComponent] or showing a window.
 */
class TypeWriterProjectConfigurable(private val project: Project) : Configurable {

    private val settings = project.getService(TypeWriterProjectSettings::class.java)

    internal val projectDir = TextFieldWithBrowseButton().apply {
        // See TypeWriterConfigurable's browse button for why the title moves onto the descriptor.
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Project Snippet Directory"),
        )
    }

    override fun getDisplayName() = "Project"

    override fun createComponent(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent("Project snippet directory:", projectDir)
            .panel

    override fun isModified(): Boolean = projectDir.text != settings.state.projectDir

    override fun apply() {
        settings.loadState(TypeWriterProjectSettings.State(projectDir = projectDir.text))
    }

    override fun reset() {
        projectDir.text = settings.state.projectDir
    }
}
