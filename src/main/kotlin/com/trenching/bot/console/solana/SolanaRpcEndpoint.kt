package com.trenching.bot.console.solana

import java.net.URI
import java.net.URISyntaxException

/**
 * The configured RPC URL. Providers embed API keys in the query, path or user info, so the URL is
 * treated as a secret: only [host] is ever exposed, and [toString] returns the host.
 */
class SolanaRpcEndpoint private constructor(val uri: URI, val host: String, private val redactions: Regex) {

    /** Providers may echo raw or decoded credentials, including short values. */
    fun redact(text: String): String = redactions.replace(text) { host }

    override fun toString(): String = host

    companion object {
        private val SCHEMES = setOf("http", "https")

        /** Null for an absent or blank URL, which means "not configured". */
        fun from(rpcUrl: String?): SolanaRpcEndpoint? {
            if (rpcUrl.isNullOrBlank()) return null
            val uri = parse(rpcUrl.trim())
            val host = uri.host
            if (uri.scheme?.lowercase() !in SCHEMES || host.isNullOrBlank()) throw InvalidSolanaRpcUrlException()
            val redactions = Regex(sensitiveParts(uri).joinToString("|", transform = Regex::escape))
            return SolanaRpcEndpoint(uri, host, redactions)
        }

        private fun sensitiveParts(uri: URI): List<String> = buildList {
            add(uri.toString())
            listOfNotNull(uri.rawUserInfo, uri.userInfo).forEach { add(it); addAll(it.split(':')) }
            listOfNotNull(uri.rawQuery, uri.query).forEach { query ->
                add(query)
                query.split('&').forEach { field -> add(field.substringAfter('=', field)) }
            }
            listOfNotNull(uri.rawPath, uri.path).forEach { path -> add(path); addAll(path.split('/')) }
        }.filter { it.isNotEmpty() && it != "/" }.distinct().sortedByDescending { it.length }

        private fun parse(rpcUrl: String): URI =
            try {
                URI(rpcUrl)
            } catch (_: URISyntaxException) {
                // The cause is dropped on purpose: its message quotes the whole URL, API key included.
                throw InvalidSolanaRpcUrlException()
            }
    }
}
