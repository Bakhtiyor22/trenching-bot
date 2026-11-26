package com.trenching.bot.console.solana.accounts.domain

/**
 * One configured account. [endpoint] embeds an API key and [projectId] is private too, so [toString] names the account only;
 * [host] is the only part ever shown.
 */
class RpcAccountConfig(val name: RpcAccountName, val endpoint: String, val host: String, val projectId: String?) {
    override fun toString(): String = "RpcAccountConfig(name=${name.label})"
}
