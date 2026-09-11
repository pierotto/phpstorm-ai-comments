package cz.petrgala.aicomments.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CommentsFileOpsTest {

    private val now = "2026-09-11T10:00:00Z"
    private fun comment(id: String, line: Int, status: CommentStatus = CommentStatus.OPEN) =
        Comment(id, line, Comment.HUMAN, "text $id", status, now, null, null)

    @Test
    fun `withComment appends to the file's list`() {
        val file = CommentsFile.empty("p", now)
            .withComment("a.php", comment("c_1_0", 1))
            .withComment("a.php", comment("c_1_1", 2))
            .withComment("b.php", comment("c_1_2", 3))

        assertEquals(listOf("c_1_0", "c_1_1"), file.comments.getValue("a.php").map { it.id })
        assertEquals(listOf("c_1_2"), file.comments.getValue("b.php").map { it.id })
    }

    @Test
    fun `withStatus changes only the matching comment`() {
        val file = CommentsFile.empty("p", now)
            .withComment("a.php", comment("c_1_0", 1))
            .withComment("a.php", comment("c_1_1", 2))

        val updated = file.withStatus("c_1_1", CommentStatus.RESOLVED)

        assertEquals(CommentStatus.OPEN, updated.find("c_1_0")!!.comment.status)
        assertEquals(CommentStatus.RESOLVED, updated.find("c_1_1")!!.comment.status)
    }

    @Test
    fun `withLines updates only listed ids of the given file`() {
        val file = CommentsFile.empty("p", now)
            .withComment("a.php", comment("c_1_0", 1))
            .withComment("a.php", comment("c_1_1", 2))

        val updated = file.withLines("a.php", mapOf("c_1_1" to 7))

        assertEquals(1, updated.find("c_1_0")!!.comment.line)
        assertEquals(7, updated.find("c_1_1")!!.comment.line)
        assertEquals(file, file.withLines("missing.php", mapOf("c_1_0" to 9)))
    }

    @Test
    fun `nextId counts ids created in the same second`() {
        val file = CommentsFile.empty("p", now)
            .withComment("a.php", comment("c_100_0", 1))
            .withComment("b.php", comment("c_100_1", 1))

        assertEquals("c_100_2", file.nextId(100))
        assertEquals("c_101_0", file.nextId(101))
    }

    @Test
    fun `find and count`() {
        val file = CommentsFile.empty("p", now)
            .withComment("a.php", comment("c_1_0", 1))
            .withComment("a.php", comment("c_1_1", 2, CommentStatus.PROCESSED))

        assertEquals("a.php", file.find("c_1_1")!!.path)
        assertNull(file.find("nope"))
        assertEquals(1, file.count(CommentStatus.OPEN))
        assertEquals(1, file.count(CommentStatus.PROCESSED))
        assertEquals(0, file.count(CommentStatus.RESOLVED))
    }
}
