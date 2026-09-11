package cz.petrgala.aicomments.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import cz.petrgala.aicomments.model.Comment
import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.settings.AiCommentsSettings
import cz.petrgala.aicomments.storage.CommentStore

object CommentWorkflow {

    fun addComment(project: Project, relativePath: String, line: Int) {
        val maxLength = AiCommentsSettings.getInstance(project).state.maxCommentLength
        val text = AddCommentDialog(project, line, maxLength).showAndGetText() ?: return
        project.service<CommentStore>().add(relativePath, line, text)
    }

    fun openComment(project: Project, comment: Comment) {
        if (comment.status == CommentStatus.RESOLVED) return
        val dialog = ViewCommentDialog(project, comment)
        dialog.show()
        val store = project.service<CommentStore>()
        when (dialog.outcome) {
            ViewCommentDialog.Outcome.CLOSE -> Unit
            ViewCommentDialog.Outcome.RESOLVE -> store.resolve(comment.id)
            ViewCommentDialog.Outcome.FOLLOW_UP -> {
                val maxLength = AiCommentsSettings.getInstance(project).state.maxCommentLength
                val text = AddCommentDialog(project, comment.line, maxLength, followUp = true).showAndGetText() ?: return
                store.followUp(comment.id, text)
            }
        }
    }
}
