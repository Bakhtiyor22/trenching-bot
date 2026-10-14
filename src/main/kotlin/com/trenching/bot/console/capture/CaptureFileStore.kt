package com.trenching.bot.console.capture

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import tools.jackson.databind.ObjectMapper

class CaptureFileStore(val paths: CapturePathPolicy, private val json: ObjectMapper) : AutoCloseable {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    @Synchronized fun initialize() {
        paths.initialize()
        Files.createDirectories(paths.resolve("console"))
        val candidate = FileChannel.open(paths.resolve("console/worker.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val acquired = try { candidate.tryLock() } catch (_: OverlappingFileLockException) { null }
        if (acquired == null) { candidate.close(); throw CaptureRequestException(409, "CAPTURE_BUSY", "Another console process owns the capture worker") }
        channel = candidate
        lock = acquired
        purgeDeleted()
    }

    /**
     * Removes a session directory and every managed file in it: first an atomic rename to a `.deleted-` name the session
     * listing never shows, then a recursive delete that removes links as links and never follows them. Returns the
     * number of files removed. Attached evidence lives outside the session directory and is never touched.
     */
    @Synchronized fun remove(session: String, suffix: String): Int {
        val directory = paths.resolve("console/${id(session)}")
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw CaptureRequestException(404, "CAPTURE_NOT_FOUND", "Capture record was not found")
        val trash = paths.resolve("console/$DELETED_PREFIX$session-${id(suffix)}")
        try { Files.move(directory, trash, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: IOException) { throw CaptureRequestException(409, "CAPTURE_DELETE_FAILED", "The capture directory is in use; close anything reading it and retry") }
        return deleteTree(trash)
    }

    /** Finishes deletions a crash interrupted after their rename. */
    private fun purgeDeleted() {
        Files.list(paths.resolve("console")).use { stream -> stream.filter { it.fileName.toString().startsWith(DELETED_PREFIX) }.toList() }
            .forEach(::deleteTree)
    }

    private fun deleteTree(root: Path): Int {
        var files = 0
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            // A Windows junction reads as a directory that is also a reparse point: remove the junction, never its target.
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (directory == root || !(attributes.isOther || attributes.isSymbolicLink)) return FileVisitResult.CONTINUE
                Files.delete(directory); files++; return FileVisitResult.SKIP_SUBTREE
            }
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult { Files.delete(file); files++; return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(directory: Path, exception: IOException?): FileVisitResult {
                if (exception != null) throw exception
                Files.delete(directory); return FileVisitResult.CONTINUE
            }
        })
        return files
    }
    fun id(value: String): String {
        if (!value.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}"))) throw CaptureRequestException(400, "INVALID_REQUEST", "Invalid identifier")
        return value
    }
    fun session(id: String): CaptureSessionRecord {
        val record = read("console/${id(id)}/session.json", CaptureSessionRecord::class.java)
        if (record.version != 1) throw CaptureRequestException(409, "UNSUPPORTED_VERSION", "Capture metadata version is unsupported")
        return record
    }
    fun sessionOrNull(id: String): CaptureSessionRecord? = if (Files.exists(paths.resolve("console/${id(id)}/session.json"))) session(id) else null
    fun sessions(): List<String> = Files.list(paths.resolve("console")).use { stream -> stream.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.filter { it.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}")) }.sorted().toList() }
    fun save(session: CaptureSessionRecord, create: Boolean = false) = write("console/${id(session.id)}/session.json", session, create)
    fun attempt(session: String, id: String): CaptureAttemptRecord = read("console/${id(session)}/attempt-${id(id)}.json", CaptureAttemptRecord::class.java)
    fun attemptOrNull(session: String, id: String): CaptureAttemptRecord? = if (Files.exists(paths.resolve("console/${id(session)}/attempt-${id(id)}.json"))) attempt(session,id) else null
    fun save(session: String, attempt: CaptureAttemptRecord, create: Boolean = false) = write("console/${id(session)}/attempt-${id(attempt.id)}.json", attempt, create)
    fun attempts(session: String): List<CaptureAttemptRecord> = names(session, "attempt-").map { read(it, CaptureAttemptRecord::class.java) }
    fun operation(session: String, id: String): CaptureOperationRecord = read("console/${id(session)}/operation-${id(id)}.json", CaptureOperationRecord::class.java)
    fun operationOrNull(session: String, id: String): CaptureOperationRecord? = if (Files.exists(paths.resolve("console/${id(session)}/operation-${id(id)}.json"))) operation(session,id) else null
    fun save(session: String, operation: CaptureOperationRecord, create: Boolean = false) = write("console/${id(session)}/operation-${id(operation.id)}.json", operation, create)
    fun operations(session: String): List<CaptureOperationRecord> = names(session, "operation-").map { read(it, CaptureOperationRecord::class.java) }
    fun captureCase(session: String, caseId: String): CaptureCaseRecord = read("console/${id(session)}/case-${id(caseId)}.json", CaptureCaseRecord::class.java)
    fun captureCaseOrNull(session: String, caseId: String): CaptureCaseRecord? = if (Files.exists(paths.resolve("console/${id(session)}/case-${id(caseId)}.json"))) captureCase(session, caseId) else null
    fun save(session: String, record: CaptureCaseRecord) = write("console/${id(session)}/case-${id(record.caseId)}.json", record, false)
    fun saveCaseProvenance(path: String, record: CaptureCaseExportRecord) = write(path, record, false)
    fun fingerprint(value: Any): String = java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)).joinToString("") { "%02x".format(it) }
    private fun names(session: String, prefix: String): List<String> = Files.list(paths.resolve("console/${id(session)}")).use { stream -> stream.map { it.fileName.toString() }.filter { it.startsWith(prefix) && it.endsWith(".json") }.sorted().map { "console/$session/$it" }.toList() }
    private fun <T> read(path: String, type: Class<T>): T {
        val file = paths.resolve(path)
        if (!Files.isRegularFile(file)) throw CaptureRequestException(404, "CAPTURE_NOT_FOUND", "Capture record was not found")
        return json.readValue(file.toFile(), type)
    }
    private fun write(path: String, value: Any, create: Boolean) {
        val file = paths.resolve(path)
        Files.createDirectories(file.parent)
        val bytes = json.writeValueAsBytes(value)
        if (create) {
            Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } else {
            val temporary = paths.resolve("$path.tmp")
            Files.write(temporary, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
    @Synchronized override fun close() { lock?.close(); channel?.close(); lock = null; channel = null }

    private companion object {
        const val DELETED_PREFIX = ".deleted-"
    }
}
