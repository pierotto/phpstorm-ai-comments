package cz.petrgala.aicomments.editor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import cz.petrgala.aicomments.model.Comment
import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.model.priority
import cz.petrgala.aicomments.ui.CommentWorkflow
import javax.swing.Icon

class CommentGutterIconRenderer(
    private val project: Project,
    private val comments: List<Comment>,
) : GutterIconRenderer(), DumbAware {

    val primary: Comment = comments.sortedWith(compareBy({ it.status.priority() }, { it.created })).first()
    val status: CommentStatus = primary.status

    override fun getIcon(): Icon = AiCommentsIcons.forStatus(status)

    override fun getTooltipText(): String = when (status) {
        CommentStatus.OPEN -> "AI comment (open): ${primary.text.take(TOOLTIP_TEXT_LENGTH)}${if (primary.text.length > TOOLTIP_TEXT_LENGTH) "…" else ""}"
        CommentStatus.PROCESSED -> "AI comment (processed): response available"
        CommentStatus.RESOLVED -> "AI comment (resolved, ${(primary.processedAt ?: primary.created).take(10)})"
    }

    override fun getClickAction(): AnAction? = if (status == CommentStatus.RESOLVED) null else object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            CommentWorkflow.openComment(project, primary)
        }
    }

    override fun isNavigateAction(): Boolean = status != CommentStatus.RESOLVED

    override fun getAlignment(): Alignment = Alignment.LEFT

    override fun equals(other: Any?): Boolean =
        other is CommentGutterIconRenderer && other.comments == comments

    override fun hashCode(): Int = comments.hashCode()

    private companion object {
        const val TOOLTIP_TEXT_LENGTH = 80
    }
}
