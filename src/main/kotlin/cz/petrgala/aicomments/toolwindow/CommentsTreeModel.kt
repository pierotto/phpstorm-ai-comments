package cz.petrgala.aicomments.toolwindow

import cz.petrgala.aicomments.model.Comment
import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.model.CommentsFile
import javax.swing.tree.DefaultMutableTreeNode

data class FileNode(val path: String, val count: Int)

data class CommentNode(val path: String, val comment: Comment, val outOfRange: Boolean)

object CommentsTreeModel {

    fun build(file: CommentsFile, hideResolved: Boolean, lineCountOf: (String) -> Int?): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode()
        file.comments.toSortedMap().forEach { (path, comments) ->
            val visible = comments
                .filter { !hideResolved || it.status != CommentStatus.RESOLVED }
                .sortedBy { it.line }
            if (visible.isEmpty()) return@forEach
            val lineCount = lineCountOf(path)
            val fileNode = DefaultMutableTreeNode(FileNode(path, visible.size))
            visible.forEach { comment ->
                val outOfRange = lineCount != null && comment.line > lineCount
                fileNode.add(DefaultMutableTreeNode(CommentNode(path, comment, outOfRange)))
            }
            root.add(fileNode)
        }
        return root
    }
}
