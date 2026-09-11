package cz.petrgala.aicomments.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import cz.petrgala.aicomments.model.Comment
import cz.petrgala.aicomments.model.CommentStatus
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent

class ViewCommentDialog(project: Project, private val comment: Comment) : DialogWrapper(project) {

    enum class Outcome { CLOSE, RESOLVE, FOLLOW_UP }

    var outcome: Outcome = Outcome.CLOSE
        private set

    init {
        title = "AI Comment – Line ${comment.line}"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        group("Your comment") {
            row { cell(readOnlyArea(comment.text, rows = 4)).align(AlignX.FILL) }
        }
        val response = comment.claudeResponse
        if (response != null) {
            group("Claude's response") {
                row { cell(readOnlyArea(response, rows = 10)).align(AlignX.FILL) }
            }
        }
    }

    override fun createActions(): Array<Action> = when (comment.status) {
        CommentStatus.OPEN -> arrayOf(outcomeAction("Mark as Resolved", Outcome.RESOLVE), closeAction())
        CommentStatus.PROCESSED -> arrayOf(outcomeAction("OK", Outcome.RESOLVE), outcomeAction("Follow-up", Outcome.FOLLOW_UP), closeAction())
        CommentStatus.RESOLVED -> arrayOf(closeAction())
    }

    private fun outcomeAction(name: String, result: Outcome): Action = object : DialogWrapperAction(name) {
        override fun doAction(e: ActionEvent) {
            outcome = result
            close(OK_EXIT_CODE)
        }
    }

    private fun closeAction(): Action = cancelAction.also { it.putValue(Action.NAME, "Close") }

    private fun readOnlyArea(text: String, rows: Int): JComponent =
        JBScrollPane(JBTextArea(text, rows, 60).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        })
}
