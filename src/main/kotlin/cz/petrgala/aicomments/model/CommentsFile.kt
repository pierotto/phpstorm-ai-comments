package cz.petrgala.aicomments.model

data class FileMetadata(
    val projectName: String,
    val created: String,
    val lastModified: String,
)

data class CommentsFile(
    val version: Int,
    val metadata: FileMetadata,
    val comments: Map<String, List<Comment>>,
) {
    companion object {
        const val CURRENT_VERSION = 1

        fun empty(projectName: String, now: String): CommentsFile =
            CommentsFile(CURRENT_VERSION, FileMetadata(projectName, now, now), emptyMap())
    }
}
