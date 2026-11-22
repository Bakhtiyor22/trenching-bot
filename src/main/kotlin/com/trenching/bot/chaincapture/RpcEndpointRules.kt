package com.trenching.bot.chaincapture

import java.net.URI
import java.net.URISyntaxException

/**
 * Which RPC addresses the transport sends to: https, or http only to this machine, with a host, no user-info and no fragment. The
 * address embeds an API key, so no result quotes it. Startup checks use the same rule, so a bad address is found there and not at a switch.
 */
object RpcEndpointRules {
    const val INVALID = "ENDPOINT"
    const val NOT_TLS = "ENDPOINT_TLS"
    private val SCHEMES = setOf("http", "https")
    private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")

    /** The code of the rule [value] breaks, or null when the transport would use it. */
    fun violation(value: String): String? {
        val uri = try { URI(value) } catch (_: URISyntaxException) { return INVALID }
        val scheme = uri.scheme?.lowercase()
        if (scheme !in SCHEMES || uri.host == null || uri.userInfo != null || uri.fragment != null) return INVALID
        if (scheme == "http" && uri.host !in LOCAL_HOSTS) return NOT_TLS
        return null
    }

    /** [value] as a URI; a refused address fails with the code of its violation. */
    fun uri(value: String): URI {
        violation(value)?.let { throw CaptureValidationException(it) }
        return URI(value)
    }
}
