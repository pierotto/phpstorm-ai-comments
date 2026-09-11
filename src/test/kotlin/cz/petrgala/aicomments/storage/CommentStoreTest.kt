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

    fun testResolveAndFollowUp() {
        val first = store.add("src/Foo.php", 3, "first")!!
        store.resolve(first.id)
        assertEquals(CommentStatus.RESOLVED, store.snapshot().find(first.id)!!.comment.status)

        val second = store.add("src/Foo.php", 3, "second")!!
        val followUp = store.followUp(second.id, "not good enough")!!

        assertEquals(CommentStatus.RESOLVED, store.snapshot().find(second.id)!!.comment.status)
        assertEquals(CommentStatus.OPEN, followUp.status)
        assertEquals(3, followUp.line)
        assertEquals(3, store.commentsFor("src/Foo.php").size)
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
}
