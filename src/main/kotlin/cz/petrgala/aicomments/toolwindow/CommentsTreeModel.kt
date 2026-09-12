package cz.petrgala.aicomments.toolwindow

import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.model.CommentThread
import cz.petrgala.aicomments.model.CommentsFile
import cz.petrgala.aicomments.model.threadsFor
import javax.swing.tree.DefaultMutableTreeNode

data class FileNode(val path: String, val count: Int)

data class ThreadNode(val thread: CommentThread, val outOfRange: Boolean)

object CommentsTreeModel {

    fun build(file: CommentsFile, hideResolved: Boolean, lineCountOf: (String) -> Int?): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode()
        file.comments.keys.sorted().forEach { path ->
            val visible = file.threadsFor(path)
                .filter { !hideResolved || it.status != CommentStatus.RESOLVED }
                .sortedBy { it.line }
            if (visible.isEmpty()) return@forEach
            val lineCount = lineCountOf(path)
            val fileNode = DefaultMutableTreeNode(FileNode(path, visible.size))
            visible.forEach { thread ->
                val outOfRange = lineCount != null && thread.line > lineCount
                fileNode.add(DefaultMutableTreeNode(ThreadNode(thread, outOfRange)))
            }
            root.add(fileNode)
        }
        return root
    }
}
