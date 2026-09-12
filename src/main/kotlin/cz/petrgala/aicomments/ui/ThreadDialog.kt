package cz.petrgala.aicomments.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.model.CommentThread
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent

class ThreadDialog(project: Project, private val thread: CommentThread, maxLength: Int) : DialogWrapper(project) {

    sealed interface Outcome {
        data object Close : Outcome
        data object Resolve : Outcome
        data class Reply(val text: String) : Outcome
    }

    private val input = CommentTextInput(maxLength, rows = 4)

    val outcome: Outcome
        get() = when (exitCode) {
            OK_EXIT_CODE -> Outcome.Reply(input.text)
            RESOLVE_EXIT_CODE -> Outcome.Resolve
            else -> Outcome.Close
        }

    init {
        title = "AI Comment Thread – Line ${thread.line}"
        setOKButtonText("Reply")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            cell(JBScrollPane(messagesPanel()).apply { preferredSize = JBUI.size(600, 320) }).align(AlignX.FILL)
        }
        separator()
        row { label("Reply") }
        input.addTo(this)
    }

    private fun messagesPanel(): JComponent = panel {
        thread.records.forEach { record ->
            message("You", record.created, record.text)
            record.claudeResponse?.let { message("Claude", record.processedAt ?: record.created, it) }
        }
    }

    private fun Panel.message(author: String, timestamp: String, text: String) {
        row { label("$author · ${timestamp.take(16).replace('T', ' ')}").bold() }
        row {
            cell(JBTextArea(text).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                border = JBUI.Borders.empty(2, 8, 8, 0)
            }).align(AlignX.FILL)
        }
    }

    override fun createActions(): Array<Action> {
        val actions = mutableListOf(okAction)
        if (thread.status != CommentStatus.RESOLVED) actions += resolveAction()
        actions += cancelAction.also { it.putValue(Action.NAME, "Close") }
        return actions.toTypedArray()
    }

    private fun resolveAction(): Action = object : DialogWrapperAction("Resolve") {
        override fun doAction(e: ActionEvent) = close(RESOLVE_EXIT_CODE)
    }

    override fun getPreferredFocusedComponent(): JComponent = input.textArea

    override fun doValidate(): ValidationInfo? = input.validate("Reply text is required")

    private companion object {
        const val RESOLVE_EXIT_CODE = NEXT_USER_EXIT_CODE
    }
}
