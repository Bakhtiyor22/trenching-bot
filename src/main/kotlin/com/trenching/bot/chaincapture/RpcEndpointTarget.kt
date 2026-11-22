package com.trenching.bot.chaincapture

/** The endpoint to send one request to and the account it belongs to. The endpoint embeds an API key, so [toString] names the account only. */
class RpcEndpointTarget(val account: String, val endpoint: String) {
    override fun toString(): String = "RpcEndpointTarget(account=$account)"
}
