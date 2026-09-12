package cz.petrgala.aicomments.storage

import cz.petrgala.aicomments.model.CommentStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommentsFileCodecTest {

    private val sample = javaClass.getResource("/comments.sample.json")!!.readText()

    @Test
    fun `decodes the sample file`() {
        val file = CommentsFileCodec().decode(sample)

        assertEquals(1, file.version)
        assertEquals("my-project", file.metadata.projectName)
        assertEquals(setOf("src/Service/UserService.php", "config/services.neon"), file.comments.keys)
        val first = file.comments.getValue("src/Service/UserService.php")[0]
        assertEquals("c_1726061700_0", first.id)
        assertEquals(45, first.line)
        assertEquals(CommentStatus.RESOLVED, first.status)
        assertTrue(first.processed)
        assertEquals("Replaced the short class name with the fully qualified one.", first.claudeResponse)
        val processed = file.comments.getValue("src/Service/UserService.php")[2]
        assertEquals(CommentStatus.PROCESSED, processed.status)
        assertTrue(processed.processed)
        assertEquals("2026-09-11T10:25:00Z", processed.processedAt)
    }

    @Test
    fun `round trip keeps every field`() {
        val codec = CommentsFileCodec()
        val decoded = codec.decode(sample)

        val again = codec.decode(codec.encode(decoded))

        assertEquals(decoded, again)
    }

    @Test
    fun `encoded output is pretty printed with lowercase status and derived processed flag`() {
        val encoded = CommentsFileCodec().encode(CommentsFileCodec().decode(sample))

        assertTrue(encoded.contains("\"status\": \"processed\""))
        assertTrue(encoded.contains("\"processed\": true"))
        assertTrue(encoded.contains("\"claudeResponse\": null"))
        assertTrue(encoded.startsWith("{\n  \"version\": 1,"))
    }

    @Test
    fun `skips an invalid record and reports it`() {
        val skipped = mutableListOf<String>()
        val json = """
            {"version":1,"metadata":{"projectName":"p","created":"2026-09-11T10:00:00Z","lastModified":"2026-09-11T10:00:00Z"},
             "comments":{"a.php":[
                {"id":"c_1_0","line":1,"author":"human","text":"ok","status":"open","created":"2026-09-11T10:00:00Z","processed":false,"processedAt":null,"claudeResponse":null},
                {"id":"c_1_1","line":2,"author":"human","text":"bad status","status":"weird","created":"2026-09-11T10:00:00Z"},
                {"id":"c_1_2","author":"human","text":"missing line","status":"open","created":"2026-09-11T10:00:00Z"}
             ]}}
        """.trimIndent()

        val file = CommentsFileCodec(onSkippedRecord = { skipped += it }).decode(json)

        assertEquals(listOf("c_1_0"), file.comments.getValue("a.php").map { it.id })
        assertEquals(2, skipped.size)
    }

    @Test
    fun `missing optional fields get defaults`() {
        val json = """
            {"version":1,"comments":{"a.php":[
                {"id":"c_1_0","line":1,"text":"ok","status":"open","created":"2026-09-11T10:00:00Z"}
            ]}}
        """.trimIndent()

        val file = CommentsFileCodec().decode(json)

        assertEquals("human", file.comments.getValue("a.php")[0].author)
        assertEquals("", file.metadata.projectName)
    }

    @Test
    fun `malformed json throws`() {
        assertThrows(MalformedCommentsFileException::class.java) { CommentsFileCodec().decode("{ not json") }
        assertThrows(MalformedCommentsFileException::class.java) { CommentsFileCodec().decode("[]") }
    }

    @Test
    fun `unsupported version throws`() {
        val e = assertThrows(UnsupportedCommentsVersionException::class.java) {
            CommentsFileCodec().decode("""{"version":2,"comments":{}}""")
        }
        assertEquals(2, e.version)
    }

    @Test
    fun `threadId round trips and defaults to id when missing`() {
        val json = """
            {"version":1,"comments":{"a.php":[
                {"id":"c_1_0","line":1,"text":"first","status":"resolved","created":"2026-09-11T10:00:00Z"},
                {"id":"c_1_1","threadId":"c_1_0","line":1,"text":"again","status":"open","created":"2026-09-11T10:05:00Z"}
            ]}}
        """.trimIndent()
        val codec = CommentsFileCodec()

        val file = codec.decode(json)
        val encoded = codec.encode(file)

        val records = file.comments.getValue("a.php")
        assertEquals("c_1_0", records[0].threadId)
        assertEquals("c_1_0", records[1].threadId)
        assertTrue(encoded.contains("\"threadId\": \"c_1_0\""))
        assertEquals(file, codec.decode(encoded))
    }
}
