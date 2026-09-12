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
    private fun comment(id: String, line: Int, status: CommentStatus = CommentStatus.OPEN, threadId: String = id, created: String = now) =
        Comment(id, line, Comment.HUMAN, "text", status, created, null, null, threadId)

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
        val aLines = children(children(root)[0]).map { (it.userObject as ThreadNode).thread.line }
        assertEquals(listOf(3, 20), aLines)
    }

    @Test
    fun `hideResolved drops resolved comments and empty files`() {
        val onlyResolved = CommentsFile.empty("p", now).withComment("z.php", comment("c_2_0", 1, CommentStatus.RESOLVED))

        val root = CommentsTreeModel.build(file, hideResolved = true) { 100 }
        val emptyRoot = CommentsTreeModel.build(onlyResolved, hideResolved = true) { 100 }

        assertEquals(listOf(20), children(children(root)[0]).map { (it.userObject as ThreadNode).thread.line })
        assertEquals(0, emptyRoot.childCount)
    }

    @Test
    fun `line beyond the file is flagged, unknown file length is not`() {
        val root = CommentsTreeModel.build(file, hideResolved = false) { path -> if (path == "a.php") 10 else null }

        val a = children(children(root)[0]).map { it.userObject as ThreadNode }
        assertFalse(a[0].outOfRange)
        assertTrue(a[1].outOfRange)
        val b = children(children(root)[1]).map { it.userObject as ThreadNode }
        assertFalse(b[0].outOfRange)
    }

    @Test
    fun `a thread is one node with the status of its newest record`() {
        val file = CommentsFile.empty("p", now)
            .withComment("a.php", comment("c_1_0", 5, CommentStatus.RESOLVED))
            .withComment("a.php", comment("c_1_1", 5, CommentStatus.PROCESSED, threadId = "c_1_0", created = "2026-09-11T10:01:00Z"))

        val root = CommentsTreeModel.build(file, hideResolved = true) { 100 }

        val nodes = children(children(root)[0]).map { it.userObject as ThreadNode }
        assertEquals(1, nodes.size)
        assertEquals(CommentStatus.PROCESSED, nodes[0].thread.status)
        assertEquals(1, (children(root)[0].userObject as FileNode).count)
    }
}
