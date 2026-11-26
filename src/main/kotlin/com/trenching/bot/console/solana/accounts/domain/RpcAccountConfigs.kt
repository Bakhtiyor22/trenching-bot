package com.trenching.bot.console.solana.accounts.domain

/** The accounts that have an RPC URL, primary first. A backup without a primary cannot exist: configuration refuses it at startup. */
class RpcAccountConfigs(configured: List<RpcAccountConfig>) {
    val configured: List<RpcAccountConfig> = configured.sortedBy { it.name }

    init {
        require(this.configured.map { it.name }.toSet().size == this.configured.size) { "An RPC account is configured once" }
        require(this.configured.none { it.name == RpcAccountName.BACKUP } || this.configured.any { it.name == RpcAccountName.PRIMARY }) {
            "A backup RPC account needs a primary"
        }
    }

    operator fun get(name: RpcAccountName): RpcAccountConfig? = configured.firstOrNull { it.name == name }

    companion object {
        val NONE = RpcAccountConfigs(emptyList())
    }
}
