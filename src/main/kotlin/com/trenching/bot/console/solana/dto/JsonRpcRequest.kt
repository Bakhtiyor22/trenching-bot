package com.trenching.bot.console.solana.dto

data class JsonRpcRequest(
    val method: String,
    val params: List<Any> = emptyList(),
    val jsonrpc: String = "2.0",
    val id: Long = 1,
)
