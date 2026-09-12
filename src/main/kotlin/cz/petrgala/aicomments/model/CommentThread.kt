package cz.petrgala.aicomments.model

data class CommentThread(val path: String, val records: List<Comment>) {
    val id: String get() = first.threadId
    val first: Comment get() = records.first()
    val newest: Comment get() = records.last()
    val line: Int get() = newest.line
    val status: CommentStatus get() = newest.status
}
