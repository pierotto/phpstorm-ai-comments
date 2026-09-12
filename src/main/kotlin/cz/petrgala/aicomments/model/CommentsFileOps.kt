package cz.petrgala.aicomments.model

data class Located(val path: String, val comment: Comment)

fun CommentsFile.all(): List<Located> =
    comments.flatMap { (path, list) -> list.map { Located(path, it) } }

fun CommentsFile.find(id: String): Located? =
    all().firstOrNull { it.comment.id == id }

fun CommentsFile.threadsFor(path: String): List<CommentThread> =
    comments[path].orEmpty()
        .groupBy { it.threadId }
        .map { (_, records) -> CommentThread(path, records.sortedWith(compareBy({ it.created }, { it.id }))) }
        .sortedWith(compareBy({ it.first.created }, { it.id }))

fun CommentsFile.threads(): List<CommentThread> = comments.keys.flatMap { threadsFor(it) }

fun CommentsFile.thread(threadId: String): CommentThread? = threads().firstOrNull { it.id == threadId }

fun CommentsFile.countThreads(status: CommentStatus): Int = threads().count { it.status == status }

fun CommentsFile.withComment(path: String, comment: Comment): CommentsFile =
    copy(comments = comments + (path to (comments[path].orEmpty() + comment)))

fun CommentsFile.withStatus(id: String, status: CommentStatus): CommentsFile =
    copy(comments = comments.mapValues { (_, list) ->
        list.map { if (it.id == id) it.copy(status = status) else it }
    })

fun CommentsFile.withLines(path: String, idToLine: Map<String, Int>): CommentsFile {
    val list = comments[path] ?: return this
    val updated = list.map { idToLine[it.id]?.let { line -> it.copy(line = line) } ?: it }
    return copy(comments = comments + (path to updated))
}

fun CommentsFile.nextId(epochSeconds: Long): String {
    val prefix = "c_${epochSeconds}_"
    val taken = all().count { it.comment.id.startsWith(prefix) }
    return "$prefix$taken"
}

fun CommentsFile.withReply(reply: Comment): CommentsFile {
    val thread = thread(reply.threadId) ?: return this
    return withStatus(thread.newest.id, CommentStatus.RESOLVED).withComment(thread.path, reply)
}
