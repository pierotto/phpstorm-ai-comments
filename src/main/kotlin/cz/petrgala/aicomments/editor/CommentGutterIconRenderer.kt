package cz.petrgala.aicomments.editor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.model.CommentThread
import cz.petrgala.aicomments.model.priority
import cz.petrgala.aicomments.ui.CommentWorkflow
import javax.swing.Icon

class CommentGutterIconRenderer(
    private val project: Project,
    val threads: List<CommentThread>,
) : GutterIconRenderer(), DumbAware {

    val primary: CommentThread = threads.sortedWith(compareBy({ it.status.priority() }, { it.first.created })).first()
    val status: CommentStatus = primary.status

    override fun getIcon(): Icon = AiCommentsIcons.forStatus(status)

    override fun getTooltipText(): String {
        val text = primary.first.text
        val preview = text.take(TOOLTIP_TEXT_LENGTH) + if (text.length > TOOLTIP_TEXT_LENGTH) "…" else ""
        return when (status) {
            CommentStatus.OPEN -> "AI comment (open): $preview"
            CommentStatus.PROCESSED -> "AI comment (processed): response available"
            CommentStatus.RESOLVED -> "AI comment (resolved): $preview"
        }
    }

    override fun getClickAction(): AnAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            CommentWorkflow.openThread(project, primary)
        }
    }

    override fun isNavigateAction(): Boolean = true

    override fun getAlignment(): Alignment = Alignment.LEFT

    override fun equals(other: Any?): Boolean =
        other is CommentGutterIconRenderer && other.threads == threads

    override fun hashCode(): Int = threads.hashCode()

    private companion object {
        const val TOOLTIP_TEXT_LENGTH = 80
    }
}
