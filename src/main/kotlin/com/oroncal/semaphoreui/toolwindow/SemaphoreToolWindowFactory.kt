package com.oroncal.semaphoreui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class SemaphoreToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SemaphoreToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(
            listOf(
                object : AnAction("Resync", "Resync Semaphore data", AllIcons.Actions.Refresh), DumbAware {
                    override fun actionPerformed(event: AnActionEvent) {
                        panel.refreshContent()
                    }
                },
                object : AnAction("Settings", "Open Semaphore settings", AllIcons.General.GearPlain), DumbAware {
                    override fun actionPerformed(event: AnActionEvent) {
                        panel.openSettingsDialog()
                    }
                },
            ),
        )
        Disposer.register(project as Disposable, panel)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
