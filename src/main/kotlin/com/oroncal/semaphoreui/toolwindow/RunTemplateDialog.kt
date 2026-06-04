package com.oroncal.semaphoreui.toolwindow

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.oroncal.semaphoreui.api.SemaphoreRunTaskRequest
import com.oroncal.semaphoreui.api.SemaphoreTemplate
import java.awt.BorderLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

class RunTemplateDialog(
    private val template: SemaphoreTemplate,
) : DialogWrapper(true) {
    private val argumentsField = JBTextField(template.task_params?.arguments ?: template.arguments.orEmpty())
    private val limitField = JBTextField()
    private val debugCheck = JCheckBox("Debug", template.task_params?.params?.debug == true)
    private val dryRunCheck = JCheckBox("Dry run", template.task_params?.params?.dry_run == true)
    private val diffCheck = JCheckBox("Diff", template.task_params?.params?.diff == true)

    init {
        title = "Run Template"
        argumentsField.isEnabled = template.allow_override_args_in_task == true
        init()
    }

    override fun createCenterPanel(): JComponent {
        val infoLabel = JBLabel(
            "<html><b>${template.name}</b><br>${template.playbook.orEmpty()}</html>",
        ).apply {
            border = JBUI.Borders.emptyBottom(8)
        }

        val noteText = if (!template.survey_vars.isNullOrEmpty()) {
            "<html>This template defines survey vars. This first integration runs the task with the standard overrides supported by <code>POST /project/{project_id}/tasks</code>.</html>"
        } else {
            "<html>The template defaults will be used, and only non-empty overrides will be sent.</html>"
        }
        val noteLabel = JBLabel(noteText).apply {
            border = JBUI.Borders.emptyTop(8)
        }

        val panel = JPanel(BorderLayout())
        panel.add(infoLabel, BorderLayout.NORTH)
        panel.add(
            FormBuilder.createFormBuilder()
                .addLabeledComponent("Argumentos:", argumentsField)
                .addLabeledComponent("Limit:", limitField)
                .addComponent(debugCheck)
                .addComponent(dryRunCheck)
                .addComponent(diffCheck)
                .addComponent(noteLabel)
                .panel,
            BorderLayout.CENTER,
        )
        return panel
    }

    fun toRequest(): SemaphoreRunTaskRequest {
        return SemaphoreRunTaskRequest(
            template_id = template.id,
            debug = debugCheck.takeIf { it.isSelected }?.isSelected,
            dry_run = dryRunCheck.takeIf { it.isSelected }?.isSelected,
            diff = diffCheck.takeIf { it.isSelected }?.isSelected,
            limit = limitField.text.trim().ifBlank { null },
            arguments = argumentsField.text.trim().ifBlank { null },
        )
    }
}
