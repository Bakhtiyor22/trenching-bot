package com.trenching.bot.chaincapture

/**
 * Where an RPC client sends its next request. A client asks [choose] before every attempt and never keeps the answer, so
 * the source may change account between two requests. [choose] runs on the request path: it must answer from memory and never block.
 */
interface RpcEndpointSource {
    fun choose(): RpcEndpointChoice

    /** The provider said the credits of [account] are used up. Several callers may report the same account. */
    fun exhausted(account: String)

    companion object {
        const val FIXED_ACCOUNT = "primary"

        /** One account at [endpoint], for command-line tools and tests: [RpcEndpointChoice.NotConfigured] when it is blank, and it never runs out. */
        fun fixed(endpoint: String?): RpcEndpointSource {
            val choice = if (endpoint.isNullOrBlank()) RpcEndpointChoice.NotConfigured else RpcEndpointChoice.Use(RpcEndpointTarget(FIXED_ACCOUNT, endpoint))
            return object : RpcEndpointSource {
                override fun choose() = choice
                override fun exhausted(account: String) = Unit
            }
        }
    }
}
