package cz.petrgala.aicomments.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import cz.petrgala.aicomments.model.CommentThread
import cz.petrgala.aicomments.settings.AiCommentsSettings
import cz.petrgala.aicomments.storage.CommentStore

object CommentWorkflow {

    fun addComment(project: Project, relativePath: String, line: Int) {
        val text = AddCommentDialog(project, line, maxLength(project)).showAndGetText() ?: return
        project.service<CommentStore>().add(relativePath, line, text)
    }

    fun openThread(project: Project, thread: CommentThread) {
        val dialog = ThreadDialog(project, thread, maxLength(project))
        dialog.show()
        val store = project.service<CommentStore>()
        when (val outcome = dialog.outcome) {
            ThreadDialog.Outcome.Close -> Unit
            ThreadDialog.Outcome.Resolve -> store.resolve(thread.id)
            is ThreadDialog.Outcome.Reply -> store.reply(thread.id, outcome.text)
        }
    }

    private fun maxLength(project: Project): Int = AiCommentsSettings.getInstance(project).state.maxCommentLength
}
