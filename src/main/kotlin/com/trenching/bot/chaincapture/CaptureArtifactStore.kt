package com.trenching.bot.chaincapture

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** Capture directory primitives: symlink-safe paths, atomic writes, bounded hashed reads and a hash-enveloped manifest. */
class CaptureArtifactStore(private val mapper: ObjectMapper) {
    fun root(directory: Path, create: Boolean): Path {
        val root = directory.toAbsolutePath().normalize()
        var current = root.root
        for (component in root) {
            current = current.resolve(component)
            if (Files.exists(current, NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, NOFOLLOW_LINKS)) fail("UNSAFE_DIRECTORY")
            } else if (create) Files.createDirectory(current)
        }
        return root
    }

    /** Returns the manifest after verifying its envelope hash, or null when [name] does not exist under [root]. */
    fun readManifest(root: Path, name: String): JsonNode? {
        if (!Files.exists(root.resolve(name), NOFOLLOW_LINKS)) return null
        val envelope = read(root, name, ChainCaptureFormat.MAX_RESPONSE_BYTES)
        val inner = envelope.path(MANIFEST_FIELD)
        if (!inner.isObject || envelope.path(SHA256_FIELD).asString() != hash(mapper.writeValueAsBytes(inner))) fail("MANIFEST_HASH")
        return inner
    }

    fun writeManifest(root: Path, name: String, manifest: JsonNode) {
        val envelope = mapper.createObjectNode().apply {
            put(SHA256_FIELD, hash(mapper.writeValueAsBytes(manifest)))
            set(MANIFEST_FIELD, manifest)
        }
        atomicWrite(root, name, mapper.writeValueAsBytes(envelope))
    }

    fun lock(directory: Path): AutoCloseable {
        val file = root(directory, create = true).resolve(ChainCaptureFormat.CAPTURE_LOCK)
        if (Files.isSymbolicLink(file)) fail("UNSAFE_PATH")
        val channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, NOFOLLOW_LINKS)
        val acquired = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null } catch (exception: IOException) {
            channel.close()
            throw exception
        }
        if (acquired == null) {
            channel.close()
            fail("CAPTURE_IN_PROGRESS")
        }
        return channel
    }

    /** Parses an artifact only after its size and SHA-256 match what the manifest recorded. */
    fun readArtifact(root: Path, relative: String, bytes: Long, sha256: String): JsonNode {
        val file = artifactPath(root, relative)
        if (bytes !in 1..ChainCaptureFormat.MAX_RESPONSE_BYTES || Files.size(file) != bytes) fail("ARTIFACT_BYTES")
        val content = boundedBytes(file, ChainCaptureFormat.MAX_RESPONSE_BYTES)
        if (hash(content) != sha256) fail("ARTIFACT_HASH")
        return parseJson(content)
    }

    fun writeTransaction(directory: Path, signature: String, raw: JsonNode): CaptureArtifact {
        val root = root(directory, create = true)
        val transactions = root.resolve(TRANSACTIONS_DIRECTORY)
        if (Files.exists(transactions, NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(transactions) || !Files.isDirectory(transactions, NOFOLLOW_LINKS)) fail("UNSAFE_PATH")
        } else Files.createDirectory(transactions)
        val bytes = mapper.writeValueAsBytes(raw)
        if (bytes.size.toLong() > ChainCaptureFormat.MAX_RESPONSE_BYTES) fail("ARTIFACT_SIZE")
        val relative = transactionPath(signature)
        // Callers only write artifacts their manifest does not reference yet, so an existing file is an unreferenced crash orphan.
        atomicWrite(root, relative, bytes)
        return CaptureArtifact(relative, hash(bytes), bytes.size.toLong())
    }

    fun transactionPath(signature: String): String = "$TRANSACTIONS_DIRECTORY/$signature.json"

    private fun artifactPath(root: Path, relative: String): Path {
        val path = root.resolve(relative).normalize()
        if (!path.startsWith(root) || relative.contains("..") || Path.of(relative).isAbsolute) fail("UNSAFE_PATH")
        var current = root
        for (component in root.relativize(path)) {
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) fail("SYMLINK")
        }
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) fail("ARTIFACT_MISSING")
        return path
    }

    private fun read(root: Path, relative: String, maximum: Long): JsonNode {
        val file = artifactPath(root, relative)
        if (Files.size(file) > maximum) fail("ARTIFACT_SIZE")
        return parseJson(boundedBytes(file, maximum))
    }

    private fun boundedBytes(file: Path, maximum: Long): ByteArray = Files.newInputStream(file, NOFOLLOW_LINKS).use { input ->
        val bytes = input.readNBytes(maximum.toInt() + 1)
        if (bytes.size > maximum) fail("ARTIFACT_SIZE")
        bytes
    }

    private fun parseJson(bytes: ByteArray): JsonNode = try { mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(bytes) } catch (_: JacksonException) { fail("JSON") }

    private fun atomicWrite(root: Path, relative: String, bytes: ByteArray) {
        val target = root.resolve(relative).normalize()
        if (!target.startsWith(root) || Files.isSymbolicLink(target)) fail("UNSAFE_PATH")
        val pending = Files.createTempFile(target.parent, ".capture-", ".pending")
        try {
            Files.write(pending, bytes)
            Files.move(pending, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(pending) }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fail(category: String): Nothing = throw CaptureValidationException(category)

    private companion object {
        const val MANIFEST_FIELD = "manifest"
        const val SHA256_FIELD = "sha256"
        const val TRANSACTIONS_DIRECTORY = "transactions"
    }
}
