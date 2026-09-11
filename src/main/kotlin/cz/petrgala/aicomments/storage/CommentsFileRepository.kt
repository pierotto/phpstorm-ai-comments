package cz.petrgala.aicomments.storage

import cz.petrgala.aicomments.model.CommentsFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

sealed interface LoadResult {
    data class Loaded(val file: CommentsFile) : LoadResult
    object Missing : LoadResult
    data class Malformed(val reason: String) : LoadResult
    data class UnsupportedVersion(val version: Int) : LoadResult
}

class CommentsFileUnavailableException(message: String) : RuntimeException(message)

class CommentsFileRepository(
    val path: Path,
    private val projectName: String,
    private val clock: Clock = Clock.systemUTC(),
    onSkippedRecord: (String) -> Unit = {},
) {
    private val lock = ReentrantLock()
    private val codec = CommentsFileCodec(onSkippedRecord)

    fun load(): LoadResult = lock.withLock { read() }

    fun mutate(change: (CommentsFile) -> CommentsFile): CommentsFile = lock.withLock {
        val current = when (val result = read()) {
            is LoadResult.Loaded -> result.file
            LoadResult.Missing -> CommentsFile.empty(projectName, now())
            is LoadResult.Malformed -> throw CommentsFileUnavailableException("comments.json is malformed: ${result.reason}")
            is LoadResult.UnsupportedVersion -> throw CommentsFileUnavailableException("comments.json version ${result.version} is not supported")
        }
        val updated = change(current).let { it.copy(metadata = it.metadata.copy(lastModified = now())) }
        write(updated)
        updated
    }

    fun backupBroken(): Path? = lock.withLock {
        if (!Files.exists(path)) return null
        val stamp = BACKUP_STAMP.format(clock.instant().atOffset(ZoneOffset.UTC))
        val target = path.resolveSibling("${path.fileName}.broken-$stamp")
        Files.move(path, target, StandardCopyOption.REPLACE_EXISTING)
        target
    }

    fun now(): String = clock.instant().truncatedTo(ChronoUnit.SECONDS).toString()

    fun epochSeconds(): Long = clock.instant().epochSecond

    private fun read(): LoadResult {
        if (!Files.exists(path)) return LoadResult.Missing
        return try {
            LoadResult.Loaded(codec.decode(Files.readString(path)))
        } catch (e: MalformedCommentsFileException) {
            LoadResult.Malformed(e.message ?: "invalid JSON")
        } catch (e: UnsupportedCommentsVersionException) {
            LoadResult.UnsupportedVersion(e.version)
        }
    }

    private fun write(file: CommentsFile) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(tmp, codec.encode(file))
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            Files.deleteIfExists(tmp)
            throw e
        }
    }

    private companion object {
        val BACKUP_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
