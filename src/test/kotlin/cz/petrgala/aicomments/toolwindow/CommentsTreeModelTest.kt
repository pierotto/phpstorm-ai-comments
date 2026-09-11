package cz.petrgala.aicomments.toolwindow

import cz.petrgala.aicomments.model.Comment
import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.model.CommentsFile
import cz.petrgala.aicomments.model.withComment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.tree.DefaultMutableTreeNode

class CommentsTreeModelTest {

    private val now = "2026-09-11T10:00:00Z"
    private fun comment(id: String, line: Int, status: CommentStatus = CommentStatus.OPEN) =
        Comment(id, line, Comment.HUMAN, "text", status, now, null, null)

    private val file = CommentsFile.empty("p", now)
        .withComment("b.php", comment("c_1_0", 9))
        .withComment("a.php", comment("c_1_1", 20))
        .withComment("a.php", comment("c_1_2", 3, CommentStatus.RESOLVED))

    private fun children(node: DefaultMutableTreeNode) = node.children().toList().map { it as DefaultMutableTreeNode }

    @Test
    fun `files are sorted and comments ordered by line`() {
        val root = CommentsTreeModel.build(file, hideResolved = false) { 100 }

        val files = children(root).map { (it.userObject as FileNode).path }
        assertEquals(listOf("a.php", "b.php"), files)
        val aLines = children(children(root)[0]).map { (it.userObject as CommentNode).comment.line }
        assertEquals(listOf(3, 20), aLines)
    }

    @Test
    fun `hideResolved drops resolved comments and empty files`() {
        val onlyResolved = CommentsFile.empty("p", now).withComment("z.php", comment("c_2_0", 1, CommentStatus.RESOLVED))

        val root = CommentsTreeModel.build(file, hideResolved = true) { 100 }
        val emptyRoot = CommentsTreeModel.build(onlyResolved, hideResolved = true) { 100 }

        assertEquals(listOf(20), children(children(root)[0]).map { (it.userObject as CommentNode).comment.line })
        assertEquals(0, emptyRoot.childCount)
    }

    @Test
    fun `line beyond the file is flagged, unknown file length is not`() {
        val root = CommentsTreeModel.build(file, hideResolved = false) { path -> if (path == "a.php") 10 else null }

        val a = children(children(root)[0]).map { it.userObject as CommentNode }
        assertFalse(a[0].outOfRange)
        assertTrue(a[1].outOfRange)
        val b = children(children(root)[1]).map { it.userObject as CommentNode }
        assertFalse(b[0].outOfRange)
    }
}
