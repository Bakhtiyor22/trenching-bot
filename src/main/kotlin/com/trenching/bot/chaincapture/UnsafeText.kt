package com.trenching.bot.chaincapture

import com.trenching.bot.protocol.solana.SolanaPrograms
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * Finds links or credential-like keywords in public on-chain text and keys, which a capture withholds instead of storing.
 * The one link exempt is the metadata `uri` of a parsed Token-2022 `initializeTokenMetadata`: every Token-2022 mint created
 * through Pump.fun `create_v2` writes one, so withholding it would withhold every such creation. It is still checked for
 * credential keywords.
 */
object UnsafeText {
    private val LINK = Regex("(?i)https?://")
    private val CREDENTIAL_TEXT = Regex("(?i)api[-_]?key|authorization|bearer\\s")
    private val UNSAFE_KEY = Regex("(?i)api[-_]?key|authorization|endpoint|rpc[-_]?url|secret|password|private[-_]?key")
    private const val TOKEN_METADATA_INITIALIZATION = "initializeTokenMetadata"

    fun foundIn(node: JsonNode): Boolean = when {
        node.isString -> LINK.containsMatchIn(node.asString()) || CREDENTIAL_TEXT.containsMatchIn(node.asString())
        node.isObject && initializesTokenMetadata(node) -> metadataUriUnsafe(node) || fieldsUnsafe(withoutMetadataUri(node))
        node.isObject -> fieldsUnsafe(node)
        node.isArray -> node.any(::foundIn)
        else -> false
    }

    private fun fieldsUnsafe(node: JsonNode): Boolean = node.properties().any { UNSAFE_KEY.containsMatchIn(it.key) || foundIn(it.value) }

    private fun initializesTokenMetadata(instruction: JsonNode): Boolean =
        instruction.path("programId").let { it.isString && it.asString() == SolanaPrograms.TOKEN_2022 } &&
            instruction.path("parsed").path("type").let { it.isString && it.asString() == TOKEN_METADATA_INITIALIZATION }

    private fun metadataUriUnsafe(instruction: JsonNode): Boolean {
        val uri = instruction.path("parsed").path("info").path("uri")
        return uri.isString && CREDENTIAL_TEXT.containsMatchIn(uri.asString())
    }

    private fun withoutMetadataUri(instruction: JsonNode): JsonNode = instruction.deepCopy().also { copy ->
        (copy.path("parsed").path("info") as? ObjectNode)?.takeIf { it.path("uri").isString }?.remove("uri")
    }
}
