package cz.petrgala.aicomments.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import cz.petrgala.aicomments.settings.AiCommentsSettings

class AiCommentsToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean =
        AiCommentsSettings.getInstance(project).state.showToolWindow

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = AiCommentsPanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
    }
}
