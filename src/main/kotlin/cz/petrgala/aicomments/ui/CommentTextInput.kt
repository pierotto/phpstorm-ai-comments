package cz.petrgala.aicomments.ui

import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.util.ui.UIUtil
import javax.swing.event.DocumentEvent

class CommentTextInput(private val maxLength: Int, rows: Int) {

    val textArea = JBTextArea(rows, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val counter = JBLabel("0 / $maxLength").apply { foreground = UIUtil.getContextHelpForeground() }

    val text: String get() = textArea.text.trim()

    init {
        textArea.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                counter.text = "${text.length} / $maxLength"
            }
        })
    }

    fun addTo(panel: Panel) {
        panel.row { cell(JBScrollPane(textArea)).align(AlignX.FILL) }
        panel.row { cell(counter).align(AlignX.RIGHT) }
    }

    fun validate(requiredMessage: String): ValidationInfo? {
        val length = text.length
        return when {
            length == 0 -> ValidationInfo(requiredMessage, textArea)
            length > maxLength -> ValidationInfo("Text is longer than $maxLength characters ($length)", textArea)
            length >= maxLength * WARN_RATIO -> ValidationInfo("Long text ($length of $maxLength characters)", textArea).asWarning().withOKEnabled()
            else -> null
        }
    }

    private companion object {
        const val WARN_RATIO = 0.8
    }
}
