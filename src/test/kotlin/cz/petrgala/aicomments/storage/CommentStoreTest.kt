package cz.petrgala.aicomments.storage

import cz.petrgala.aicomments.AiCommentsPlatformTestCase
import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.model.CommentsFile
import cz.petrgala.aicomments.model.find
import java.nio.file.Files

class CommentStoreTest : AiCommentsPlatformTestCase() {

    fun testAddCreatesFileAndPublishes() {
        val received = mutableListOf<CommentsFile>()
        project.messageBus.connect(testRootDisposable).subscribe(CommentsChangedListener.TOPIC, object : CommentsChangedListener {
            override fun commentsChanged(snapshot: CommentsFile) { received += snapshot }
        })

        val added = store.add("src/Foo.php", 3, "use FQN")!!

        assertTrue(Files.exists(ProjectPaths.commentsFile(project)))
        assertEquals(CommentStatus.OPEN, added.status)
        assertEquals(listOf(added), store.commentsFor("src/Foo.php"))
        assertEquals(1, received.size)
    }

    fun testResolveAndReplyWorkOnTheThread() {
        val first = store.add("src/Foo.php", 3, "first")!!
        store.resolve(first.threadId)
        assertEquals(CommentStatus.RESOLVED, store.snapshot().find(first.id)!!.comment.status)

        val reply = store.reply(first.threadId, "not good enough")!!

        assertEquals(first.id, reply.threadId)
        assertEquals(CommentStatus.OPEN, reply.status)
        assertEquals(3, reply.line)
        val thread = store.threadsFor("src/Foo.php").single()
        assertEquals(listOf(first.id, reply.id), thread.records.map { it.id })
        assertEquals(CommentStatus.OPEN, thread.status)

        store.resolve(first.threadId)

        assertEquals(CommentStatus.RESOLVED, store.threadsFor("src/Foo.php").single().status)
        assertNull(store.reply("nope", "text"))
    }

    fun testUpdateLines() {
        val c = store.add("src/Foo.php", 3, "text")!!

        store.updateLines("src/Foo.php", mapOf(c.id to 5))

        assertEquals(5, store.commentsFor("src/Foo.php")[0].line)
    }

    fun testMalformedFileMakesStoreReadOnlyAndResetRecovers() {
        val path = ProjectPaths.commentsFile(project)
        Files.createDirectories(path.parent)
        Files.writeString(path, "{ oops")

        store.reload()

        assertTrue(store.isReadOnly)
        assertNull(store.add("src/Foo.php", 1, "text"))

        store.reset()

        assertFalse(store.isReadOnly)
        assertNotNull(store.add("src/Foo.php", 1, "text"))
        val backups = Files.list(path.parent).use { s -> s.filter { it.fileName.toString().startsWith("comments.json.broken-") }.toList() }
        assertEquals(1, backups.size)
        backups.forEach { Files.delete(it) }
    }

    fun testInvalidRecordsMakeStoreReadOnlyButKeepValidComments() {
        val path = ProjectPaths.commentsFile(project)
        Files.createDirectories(path.parent)
        Files.writeString(path, """{"version": 1, "comments": {"src/Foo.php": [
            {"id": "c_1_0", "line": 1, "text": "t", "status": "open", "created": "2026-09-11T10:00:00Z"},
            {"id": "c_1_1", "line": 2}
        ]}}""")

        store.reload()

        assertTrue(store.isReadOnly)
        assertNull(store.add("src/Foo.php", 1, "text"))
        assertEquals(listOf("c_1_0"), store.commentsFor("src/Foo.php").map { it.id })
    }
}
