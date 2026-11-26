package com.trenching.bot.console.solana.accounts.infrastructure.helius

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Finds the API key inside an RPC URL. The key is a credential: the caller sends it in one header and keeps it out of every log. */
object HeliusApiKeys {
    private const val API_KEY_PARAMETER = "api-key"

    /** The decoded `api-key` query parameter, or null when the URL has none, an empty one, or cannot be read. */
    fun fromRpcUrl(rpcUrl: String): String? {
        val query = try {
            URI(rpcUrl.trim()).rawQuery
        } catch (_: URISyntaxException) {
            null
        } ?: return null
        return query.split('&')
            .map { it.substringBefore('=') to it.substringAfter('=', "") }
            .firstOrNull { (name, _) -> name == API_KEY_PARAMETER }
            ?.let { (_, value) -> URLDecoder.decode(value, StandardCharsets.UTF_8) }
            ?.takeIf { it.isNotBlank() }
    }
}
