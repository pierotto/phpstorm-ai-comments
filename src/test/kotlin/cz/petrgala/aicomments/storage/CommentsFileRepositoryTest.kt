package cz.petrgala.aicomments.storage

import cz.petrgala.aicomments.model.Comment
import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.model.find
import cz.petrgala.aicomments.model.withComment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CommentsFileRepositoryTest {

    @TempDir
    lateinit var dir: Path

    private val clock = Clock.fixed(Instant.parse("2026-09-11T10:00:00Z"), ZoneOffset.UTC)
    private val path get() = dir.resolve(".claude").resolve("comments.json")
    private fun repo() = CommentsFileRepository(path, "my-project", clock)
    private fun comment(id: String, line: Int) =
        Comment(id, line, Comment.HUMAN, "t", CommentStatus.OPEN, "2026-09-11T10:00:00Z", null, null)

    @Test
    fun `load reports a missing file`() {
        assertEquals(LoadResult.Missing, repo().load())
    }

    @Test
    fun `mutate creates directory and file with metadata`() {
        val result = repo().mutate { it.withComment("a.php", comment("c_1_0", 1)) }

        assertTrue(Files.exists(path))
        assertEquals("my-project", result.metadata.projectName)
        assertEquals("2026-09-11T10:00:00Z", result.metadata.created)
        assertEquals("2026-09-11T10:00:00Z", result.metadata.lastModified)
        val loaded = repo().load() as LoadResult.Loaded
        assertEquals(result, loaded.file)
        assertFalse(Files.exists(dir.resolve(".claude").resolve("comments.json.tmp")))
    }

    @Test
    fun `mutate reloads from disk before applying the change`() {
        val repo = repo()
        repo.mutate { it.withComment("a.php", comment("c_1_0", 1)) }
        val external = Files.readString(path).replace("\"status\": \"open\"", "\"status\": \"processed\"")
        Files.writeString(path, external)

        val result = repo.mutate { it.withComment("a.php", comment("c_1_1", 2)) }

        assertEquals(CommentStatus.PROCESSED, result.find("c_1_0")!!.comment.status)
        assertNotNull(result.find("c_1_1"))
    }

    @Test
    fun `malformed file is reported and blocks writes`() {
        Files.createDirectories(path.parent)
        Files.writeString(path, "{ oops")

        val load = repo().load()
        assertTrue(load is LoadResult.Malformed)
        assertThrows(CommentsFileUnavailableException::class.java) { repo().mutate { it } }
        assertEquals("{ oops", Files.readString(path))
    }

    @Test
    fun `unsupported version is reported and blocks writes`() {
        Files.createDirectories(path.parent)
        Files.writeString(path, """{"version": 2, "comments": {}}""")

        assertEquals(LoadResult.UnsupportedVersion(2), repo().load())
        assertThrows(CommentsFileUnavailableException::class.java) { repo().mutate { it } }
    }

    @Test
    fun `backupBroken renames the file with a timestamp`() {
        Files.createDirectories(path.parent)
        Files.writeString(path, "{ oops")

        val backup = repo().backupBroken()

        assertEquals("comments.json.broken-20260911-100000", backup!!.fileName.toString())
        assertFalse(Files.exists(path))
        assertEquals("{ oops", Files.readString(backup))
        assertEquals(null, repo().backupBroken())
    }
}
