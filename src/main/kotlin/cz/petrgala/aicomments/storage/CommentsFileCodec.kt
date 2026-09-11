package cz.petrgala.aicomments.storage

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import cz.petrgala.aicomments.model.Comment
import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.model.CommentsFile
import cz.petrgala.aicomments.model.FileMetadata

class MalformedCommentsFileException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class UnsupportedCommentsVersionException(val version: Int) :
    RuntimeException("comments.json version $version is not supported")

class CommentsFileCodec(private val onSkippedRecord: (String) -> Unit = {}) {

    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    fun decode(json: String): CommentsFile {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: JsonParseException) {
            throw MalformedCommentsFileException(e.message ?: "invalid JSON", e)
        }
        if (!root.isJsonObject) throw MalformedCommentsFileException("root must be an object")
        val obj = root.asJsonObject

        val version = obj.optInt("version") ?: throw MalformedCommentsFileException("missing version")
        if (version > CommentsFile.CURRENT_VERSION) throw UnsupportedCommentsVersionException(version)

        val metadata = obj.getAsJsonObjectOrNull("metadata").let {
            FileMetadata(
                projectName = it?.optString("projectName") ?: "",
                created = it?.optString("created") ?: "",
                lastModified = it?.optString("lastModified") ?: "",
            )
        }

        val commentsObj = obj.getAsJsonObjectOrNull("comments")
            ?: throw MalformedCommentsFileException("missing comments object")
        val comments = commentsObj.entrySet().associate { (path, value) ->
            val list = if (value.isJsonArray) value.asJsonArray else {
                onSkippedRecord("comments for '$path' are not an array")
                emptyList<JsonElement>()
            }
            path to list.mapNotNull { decodeComment(path, it) }
        }

        return CommentsFile(version, metadata, comments)
    }

    fun encode(file: CommentsFile): String {
        val root = JsonObject()
        root.addProperty("version", file.version)
        root.add("metadata", JsonObject().apply {
            addProperty("projectName", file.metadata.projectName)
            addProperty("created", file.metadata.created)
            addProperty("lastModified", file.metadata.lastModified)
        })
        root.add("comments", JsonObject().apply {
            file.comments.forEach { (path, list) ->
                add(path, gson.toJsonTree(list.map(::encodeComment)))
            }
        })
        return gson.toJson(root) + "\n"
    }

    private fun decodeComment(path: String, element: JsonElement): Comment? {
        if (!element.isJsonObject) return skip(path, "record is not an object")
        val o = element.asJsonObject
        val id = o.optString("id") ?: return skip(path, "missing id")
        val line = o.optInt("line") ?: return skip(path, "$id: missing line")
        if (line < 1) return skip(path, "$id: line must be >= 1")
        val text = o.optString("text") ?: return skip(path, "$id: missing text")
        val statusJson = o.optString("status") ?: return skip(path, "$id: missing status")
        val status = CommentStatus.fromJson(statusJson) ?: return skip(path, "$id: unknown status '$statusJson'")
        val created = o.optString("created") ?: return skip(path, "$id: missing created")
        return Comment(
            id = id,
            line = line,
            author = o.optString("author") ?: Comment.HUMAN,
            text = text,
            status = status,
            created = created,
            processedAt = o.optString("processedAt"),
            claudeResponse = o.optString("claudeResponse"),
        )
    }

    private fun encodeComment(c: Comment): JsonObject = JsonObject().apply {
        addProperty("id", c.id)
        addProperty("line", c.line)
        addProperty("author", c.author)
        addProperty("text", c.text)
        addProperty("status", c.status.json)
        addProperty("created", c.created)
        addProperty("processed", c.processed)
        add("processedAt", c.processedAt?.let { gson.toJsonTree(it) } ?: JsonNull.INSTANCE)
        add("claudeResponse", c.claudeResponse?.let { gson.toJsonTree(it) } ?: JsonNull.INSTANCE)
    }

    private fun skip(path: String, reason: String): Comment? {
        onSkippedRecord("$path: $reason")
        return null
    }

    private fun JsonObject.optString(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.optInt(name: String): Int? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun JsonObject.getAsJsonObjectOrNull(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject
}
