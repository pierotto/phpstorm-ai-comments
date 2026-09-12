package cz.petrgala.aicomments.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class AddCommentDialog(project: Project, line: Int, maxLength: Int) : DialogWrapper(project) {

    private val input = CommentTextInput(maxLength, rows = 6)

    init {
        title = "Add AI Comment – Line $line"
        setOKButtonText("Add Comment")
        init()
    }

    fun showAndGetText(): String? = if (showAndGet()) input.text else null

    override fun createCenterPanel(): JComponent = panel {
        input.addTo(this)
        row {
            comment("Be concise. Claude will see this line, 5 lines before and after, and the file path.")
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = input.textArea

    override fun doValidate(): ValidationInfo? = input.validate("Comment text is required")
}
