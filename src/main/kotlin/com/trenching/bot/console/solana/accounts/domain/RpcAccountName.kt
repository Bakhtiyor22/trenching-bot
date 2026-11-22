package com.trenching.bot.console.solana.accounts.domain

/** The two RPC accounts, in the order they are preferred: the first one that has credits is the active one. */
enum class RpcAccountName(val label: String) {
    PRIMARY("primary"),
    BACKUP("backup");

    companion object {
        fun ofLabel(label: String): RpcAccountName? = entries.firstOrNull { it.label == label }
    }
}
