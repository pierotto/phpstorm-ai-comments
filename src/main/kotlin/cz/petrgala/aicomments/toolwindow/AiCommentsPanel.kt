package cz.petrgala.aicomments.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import cz.petrgala.aicomments.editor.AiCommentsIcons
import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.model.CommentsFile
import cz.petrgala.aicomments.model.countThreads
import cz.petrgala.aicomments.settings.AiCommentsSettings
import cz.petrgala.aicomments.settings.AiCommentsSettingsListener
import cz.petrgala.aicomments.storage.CommentStore
import cz.petrgala.aicomments.storage.CommentsChangedListener
import cz.petrgala.aicomments.storage.ProjectPaths
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class AiCommentsPanel(private val project: Project, parentDisposable: Disposable) : SimpleToolWindowPanel(true, true) {

    private val summary = JBLabel().apply { border = JBUI.Borders.empty(4, 8) }
    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = NodeRenderer()
    }

    init {
        toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_TOOLBAR_BAR, toolbarActions(), true)
            .also { it.targetComponent = this }
            .component
        setContent(JPanel(BorderLayout()).apply {
            add(summary, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
        })

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = navigateToSelection()
        }.installOn(tree)
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && navigateToSelection()) e.consume()
            }
        })

        val connection = project.messageBus.connect(parentDisposable)
        connection.subscribe(CommentsChangedListener.TOPIC, object : CommentsChangedListener {
            override fun commentsChanged(snapshot: CommentsFile) = render(snapshot)
        })
        connection.subscribe(AiCommentsSettingsListener.TOPIC, object : AiCommentsSettingsListener {
            override fun settingsChanged() = render(project.service<CommentStore>().snapshot())
        })
        render(project.service<CommentStore>().snapshot())
    }

    private fun toolbarActions() = DefaultActionGroup(
        ActionManager.getInstance().getAction("cz.petrgala.aicomments.Reload"),
        object : ToggleAction("Hide Resolved", "Hide resolved comments", AllIcons.Actions.Checked), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent): Boolean = AiCommentsSettings.getInstance(project).state.hideResolved
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                val settings = AiCommentsSettings.getInstance(project)
                settings.state.hideResolved = state
                settings.publishChanged(project)
            }
        },
    )

    private fun render(snapshot: CommentsFile) {
        summary.text = "Open ${snapshot.countThreads(CommentStatus.OPEN)} · Processed ${snapshot.countThreads(CommentStatus.PROCESSED)} · Resolved ${snapshot.countThreads(CommentStatus.RESOLVED)}"
        val hideResolved = AiCommentsSettings.getInstance(project).state.hideResolved
        treeModel.setRoot(CommentsTreeModel.build(snapshot, hideResolved, ::lineCountOf))
        TreeUtil.expandAll(tree)
    }

    private fun lineCountOf(relativePath: String): Int? {
        val file = ProjectPaths.findFile(project, relativePath) ?: return null
        return FileDocumentManager.getInstance().getCachedDocument(file)?.lineCount
    }

    private fun navigateToSelection(): Boolean {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return false
        val target = node.userObject as? ThreadNode ?: return false
        val file = ProjectPaths.findFile(project, target.thread.path) ?: return false
        OpenFileDescriptor(project, file, target.thread.line - 1, 0).navigate(true)
        return true
    }

    private class NodeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val data = (value as? DefaultMutableTreeNode)?.userObject) {
                is FileNode -> {
                    append(data.path)
                    append("  ${data.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is ThreadNode -> {
                    val thread = data.thread
                    val text = thread.first.text
                    icon = AiCommentsIcons.forStatus(thread.status)
                    append("L${thread.line}", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(" · ${thread.status.json}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (thread.records.size > 1) append(" · ${thread.records.size} messages", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(" · ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(text.take(TEXT_PREVIEW_LENGTH) + if (text.length > TEXT_PREVIEW_LENGTH) "…" else "")
                    if (data.outOfRange) append("  (line out of range)", SimpleTextAttributes.ERROR_ATTRIBUTES)
                }
            }
        }
    }

    private companion object {
        const val TEXT_PREVIEW_LENGTH = 60
    }
}
