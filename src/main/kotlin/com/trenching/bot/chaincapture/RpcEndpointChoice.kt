package com.trenching.bot.chaincapture

/** What an [RpcEndpointSource] offers for the next request. */
sealed interface RpcEndpointChoice {
    data class Use(val target: RpcEndpointTarget) : RpcEndpointChoice

    /** No endpoint is configured. */
    data object NotConfigured : RpcEndpointChoice

    /** Every configured account has used up its credits. */
    data object OutOfCredits : RpcEndpointChoice
}
