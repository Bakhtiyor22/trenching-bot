package com.trenching.bot.chaincapture

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import org.slf4j.LoggerFactory
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** What identifies a cached `getTransaction` result: the signature and a hash of the request parameters that produced it. */
data class TransactionCacheKey(val signature: String, val requestHash: String) {
    internal val fileName: String get() = "$signature.${requestHash.take(FILE_HASH_LENGTH)}.json"

    private companion object {
        const val FILE_HASH_LENGTH = 16
    }
}

/**
 * Successful `getTransaction` results shared by every capture, so a signature already downloaded is never requested again.
 * Each entry records the SHA-256 of the exact bytes a capture would write for the transaction, and is served only when the
 * signature, request hash and those bytes all verify. Anything else is deleted and counts as a miss. Local data only.
 */
class SharedTransactionCache(directory: Path, private val mapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(SharedTransactionCache::class.java)
    private val files = CaptureArtifactStore(mapper)
    private val root: Path = files.root(directory, create = false)

    /** The key for a `getTransaction` request built as `[signature, options]`, or null when the request is not cacheable. */
    fun keyFor(params: List<Any>): TransactionCacheKey? {
        val signature = params.getOrNull(SIGNATURE_PARAM) as? String ?: return null
        val options = params.getOrNull(OPTIONS_PARAM) as? Map<*, *> ?: return null
        if (params.size != PARAM_COUNT || !SIGNATURE.matches(signature)) return null
        return TransactionCacheKey(signature, Sha256.hex(mapper.writeValueAsBytes(canonical(options))))
    }

    /** The cached transaction for [key], or null on a miss; an entry that fails verification is deleted and logged. */
    fun read(key: TransactionCacheKey): JsonNode? {
        val file = root.resolve(key.fileName)
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) return null
        val transaction = try { verified(key, file) } catch (exception: IOException) {
            log.warn("chain_capture transaction_cache_unreadable signature={} path={} category={}", key.signature, file, exception.javaClass.simpleName)
            return null
        }
        if (transaction == null) discard(key, file)
        return transaction
    }

    /** Stores [transaction]; a cache that cannot be written is logged and skipped, as it only ever saves work. */
    fun write(key: TransactionCacheKey, transaction: JsonNode) {
        val content = mapper.writeValueAsBytes(transaction)
        if (content.size.toLong() > ChainCaptureFormat.MAX_RESPONSE_BYTES) return
        val entry = mapper.createObjectNode().apply {
            put(VERSION_FIELD, VERSION)
            put(SIGNATURE_FIELD, key.signature)
            put(REQUEST_FIELD, key.requestHash)
            put(SHA256_FIELD, Sha256.hex(content))
            set(TRANSACTION_FIELD, transaction)
        }
        try {
            files.root(root, create = true)
            files.atomicWrite(root, key.fileName, mapper.writeValueAsBytes(entry))
        } catch (exception: IOException) { skipped(key, exception) } catch (exception: CaptureValidationException) { skipped(key, exception) }
    }

    private fun verified(key: TransactionCacheKey, file: Path): JsonNode? {
        if (Files.size(file) > MAX_ENTRY_BYTES) return null
        val entry = try { parse(Files.readAllBytes(file)) } catch (_: JacksonException) { return null }
        val transaction = entry.path(TRANSACTION_FIELD)
        val matches = entry.isObject && entry.path(VERSION_FIELD).asInt() == VERSION &&
            entry.path(SIGNATURE_FIELD).asString() == key.signature && entry.path(REQUEST_FIELD).asString() == key.requestHash &&
            transaction.isObject && Sha256.hex(mapper.writeValueAsBytes(transaction)) == entry.path(SHA256_FIELD).asString()
        return transaction.takeIf { matches }
    }

    private fun parse(bytes: ByteArray): JsonNode = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(bytes)

    private fun discard(key: TransactionCacheKey, file: Path) {
        log.warn("chain_capture transaction_cache_corrupt signature={} path={}", key.signature, file)
        try { Files.deleteIfExists(file) } catch (exception: IOException) {
            log.warn("chain_capture transaction_cache_delete_failed signature={} path={} category={}", key.signature, file, exception.javaClass.simpleName)
        }
    }

    private fun skipped(key: TransactionCacheKey, exception: Exception) {
        log.warn("chain_capture transaction_cache_write_skipped signature={} category={}", key.signature, exception.javaClass.simpleName)
    }

    private fun canonical(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.sortedBy { it.key.toString() }.associate { it.key.toString() to canonical(it.value) }
        is Iterable<*> -> value.map(::canonical)
        else -> value
    }

    private companion object {
        const val SIGNATURE_PARAM = 0
        const val OPTIONS_PARAM = 1
        const val PARAM_COUNT = 2
        const val VERSION = 1
        const val VERSION_FIELD = "version"
        const val SIGNATURE_FIELD = "signature"
        const val REQUEST_FIELD = "request"
        const val SHA256_FIELD = "sha256"
        const val TRANSACTION_FIELD = "transaction"
        const val MAX_ENTRY_BYTES = ChainCaptureFormat.MAX_RESPONSE_BYTES + 4096
        val SIGNATURE = Regex("[1-9A-HJ-NP-Za-km-z]{1,100}")
    }
}
