package cz.petrgala.aicomments.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.openapi.project.DumbAware
import cz.petrgala.aicomments.storage.ProjectPaths
import cz.petrgala.aicomments.ui.CommentWorkflow

class AddCommentAction : AnAction(), DumbAware {

    private data class Target(val relativePath: String, val line: Int)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = targetOf(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = targetOf(e) ?: return
        CommentWorkflow.addComment(project, target.relativePath, target.line)
    }

    private fun targetOf(e: AnActionEvent): Target? {
        val project = e.project ?: return null
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        val relativePath = ProjectPaths.relativePath(project, file) ?: return null
        val gutterLine = e.getData(EditorGutterComponentEx.LOGICAL_LINE_AT_CURSOR)
        val line = (gutterLine ?: editor.caretModel.logicalPosition.line) + 1
        return Target(relativePath, line)
    }
}
