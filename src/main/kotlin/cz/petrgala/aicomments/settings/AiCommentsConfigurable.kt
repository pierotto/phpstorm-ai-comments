package cz.petrgala.aicomments.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

class AiCommentsConfigurable(private val project: Project) : BoundConfigurable("AI Comments") {

    private val state = AiCommentsSettings.getInstance(project).state

    override fun createPanel(): DialogPanel = panel {
        row { checkBox("Show gutter icons").bindSelected(state::showGutterIcons) }
        row { checkBox("Refresh automatically when .claude/comments.json changes").bindSelected(state::autoRefresh) }
        row("Refresh debounce (ms):") { intTextField(0..5000).bindIntText(state::debounceMs) }
        row("Maximum comment length:") { intTextField(1..5000).bindIntText(state::maxCommentLength) }
        row { checkBox("Show the AI Comments tool window").bindSelected(state::showToolWindow) }
        row { checkBox("Hide resolved comments in the tool window").bindSelected(state::hideResolved) }
    }

    override fun apply() {
        super.apply()
        ToolWindowManager.getInstance(project)
            .getToolWindow(AiCommentsSettings.TOOL_WINDOW_ID)
            ?.isAvailable = state.showToolWindow
        AiCommentsSettings.getInstance(project).publishChanged(project)
    }
}
