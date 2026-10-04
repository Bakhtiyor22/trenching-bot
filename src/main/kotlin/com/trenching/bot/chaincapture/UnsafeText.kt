package com.trenching.bot.chaincapture

import tools.jackson.databind.JsonNode

/** Finds links or credential-like keywords in public on-chain text and keys, which a capture withholds instead of storing. */
object UnsafeText {
    private val UNSAFE_TEXT = Regex("(?i)https?://|api[-_]?key|authorization|bearer\\s")
    private val UNSAFE_KEY = Regex("(?i)api[-_]?key|authorization|endpoint|rpc[-_]?url|secret|password|private[-_]?key")

    fun foundIn(node: JsonNode): Boolean = when {
        node.isString -> UNSAFE_TEXT.containsMatchIn(node.asString())
        node.isObject -> node.properties().any { UNSAFE_KEY.containsMatchIn(it.key) || foundIn(it.value) }
        node.isArray -> node.any(::foundIn)
        else -> false
    }
}
