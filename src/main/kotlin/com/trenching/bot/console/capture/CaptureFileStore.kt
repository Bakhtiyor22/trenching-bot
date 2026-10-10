package com.trenching.bot.console.capture

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
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
}
