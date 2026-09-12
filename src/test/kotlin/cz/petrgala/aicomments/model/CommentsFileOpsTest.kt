package cz.petrgala.aicomments.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CommentsFileOpsTest {

    private val now = "2026-09-11T10:00:00Z"
    private fun comment(id: String, line: Int, status: CommentStatus = CommentStatus.OPEN, threadId: String = id, created: String = now) =
        Comment(id, line, Comment.HUMAN, "text $id", status, created, null, null, threadId)

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
    fun `find and countThreads`() {
        val file = CommentsFile.empty("p", now)
            .withComment("a.php", comment("c_1_0", 1, CommentStatus.RESOLVED))
            .withComment("a.php", comment("c_1_1", 1, CommentStatus.PROCESSED, threadId = "c_1_0", created = "2026-09-11T10:01:00Z"))
            .withComment("a.php", comment("c_1_2", 2))

        assertEquals("a.php", file.find("c_1_1")!!.path)
        assertNull(file.find("nope"))
        assertEquals(1, file.countThreads(CommentStatus.OPEN))
        assertEquals(1, file.countThreads(CommentStatus.PROCESSED))
        assertEquals(0, file.countThreads(CommentStatus.RESOLVED))
    }

    @Test
    fun `threadsFor groups by threadId and orders records by created`() {
        val file = CommentsFile.empty("p", now)
            .withComment("a.php", comment("c_1_1", 1, CommentStatus.OPEN, threadId = "c_1_0", created = "2026-09-11T10:01:00Z"))
            .withComment("a.php", comment("c_1_0", 1, CommentStatus.RESOLVED))
            .withComment("a.php", comment("c_1_2", 5))

        val threads = file.threadsFor("a.php")

        assertEquals(listOf("c_1_0", "c_1_2"), threads.map { it.id })
        val first = threads[0]
        assertEquals(listOf("c_1_0", "c_1_1"), first.records.map { it.id })
        assertEquals("c_1_1", first.newest.id)
        assertEquals("c_1_0", first.first.id)
        assertEquals(CommentStatus.OPEN, first.status)
        assertEquals(1, first.line)
        assertEquals("a.php", first.path)
        assertEquals(emptyList<CommentThread>(), file.threadsFor("missing.php"))
    }

    @Test
    fun `threads and thread cover every file`() {
        val file = CommentsFile.empty("p", now)
            .withComment("a.php", comment("c_1_0", 1))
            .withComment("b.php", comment("c_1_1", 2))

        assertEquals(listOf("c_1_0", "c_1_1"), file.threads().map { it.id }.sorted())
        assertEquals("b.php", file.thread("c_1_1")!!.path)
        assertNull(file.thread("nope"))
    }

    @Test
    fun `withReply resolves the newest record and appends the reply to the same file`() {
        val file = CommentsFile.empty("p", now)
            .withComment("a.php", comment("c_1_0", 4, CommentStatus.RESOLVED))
            .withComment("a.php", comment("c_1_1", 4, CommentStatus.PROCESSED, threadId = "c_1_0", created = "2026-09-11T10:01:00Z"))
        val reply = comment("c_1_2", 4, CommentStatus.OPEN, threadId = "c_1_0", created = "2026-09-11T10:02:00Z")

        val updated = file.withReply(reply)

        val thread = updated.thread("c_1_0")!!
        assertEquals(listOf("c_1_0", "c_1_1", "c_1_2"), thread.records.map { it.id })
        assertEquals(CommentStatus.RESOLVED, updated.find("c_1_1")!!.comment.status)
        assertEquals(CommentStatus.OPEN, thread.status)
        assertEquals(file, file.withReply(comment("c_9_0", 1, threadId = "nope")))
    }
}
