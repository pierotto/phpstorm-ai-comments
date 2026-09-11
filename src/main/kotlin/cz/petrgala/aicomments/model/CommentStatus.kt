package cz.petrgala.aicomments.model

enum class CommentStatus(val json: String) {
    OPEN("open"),
    PROCESSED("processed"),
    RESOLVED("resolved");

    companion object {
        fun fromJson(value: String): CommentStatus? = entries.firstOrNull { it.json == value }
    }
}

fun CommentStatus.priority(): Int = when (this) {
    CommentStatus.OPEN -> 0
    CommentStatus.PROCESSED -> 1
    CommentStatus.RESOLVED -> 2
}
