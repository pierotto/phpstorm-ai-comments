package cz.petrgala.aicomments.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

class AddCommentDialog(
    project: Project,
    private val line: Int,
    private val maxLength: Int,
    followUp: Boolean = false,
) : DialogWrapper(project) {

    private val textArea = JBTextArea(6, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val counter = JBLabel("0 / $maxLength").apply { foreground = UIUtil.getContextHelpForeground() }

    val text: String get() = textArea.text.trim()

    init {
        title = if (followUp) "Follow-up AI Comment – Line $line" else "Add AI Comment – Line $line"
        setOKButtonText(if (followUp) "Add Follow-up" else "Add Comment")
        textArea.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                counter.text = "${text.length} / $maxLength"
            }
        })
        init()
    }

    fun showAndGetText(): String? = if (showAndGet()) text else null

    override fun createCenterPanel(): JComponent = panel {
        row { cell(JBScrollPane(textArea)).align(AlignX.FILL) }
        row { cell(counter).align(AlignX.RIGHT) }
        row {
            comment("Be concise. Claude will see this line, 5 lines before and after, and the file path.")
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = textArea

    override fun doValidate(): ValidationInfo? {
        val length = text.length
        return when {
            length == 0 -> ValidationInfo("Comment text is required", textArea)
            length > maxLength -> ValidationInfo("Comment is longer than $maxLength characters ($length)", textArea)
            length >= maxLength * WARN_RATIO -> ValidationInfo("Long comment ($length of $maxLength characters)", textArea).asWarning().withOKEnabled()
            else -> null
        }
    }

    private companion object {
        const val WARN_RATIO = 0.8
    }
}
