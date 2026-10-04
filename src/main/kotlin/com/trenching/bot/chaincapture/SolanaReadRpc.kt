package com.trenching.bot.chaincapture

import tools.jackson.databind.JsonNode

fun interface SolanaReadRpc {
    fun call(method: String, params: List<Any>, budget: CaptureBudget): JsonNode
}
