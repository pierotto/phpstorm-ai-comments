package cz.petrgala.aicomments.model

data class Comment(
    val id: String,
    val line: Int,
    val author: String,
    val text: String,
    val status: CommentStatus,
    val created: String,
    val processedAt: String?,
    val claudeResponse: String?,
) {
    val processed: Boolean get() = processedAt != null

    companion object {
        const val HUMAN = "human"
    }
}
