package com.trenching.bot.console.api.dto

import com.fasterxml.jackson.annotation.JsonAnySetter
import tools.jackson.databind.JsonNode

/**
 * Raw JSON nodes so every field is checked for its JSON type: Jackson would otherwise coerce a
 * number into a String notional, or truncate 10.5 into an Int. [unknownFields] collects every other
 * property, such as the removed `feeBps` and `slippageBps`, so each is reported instead of ignored.
 */
data class ConsoleConfigRequest(
    val notional: JsonNode? = null,
    val maxAgeSlots: JsonNode? = null,
    val dipBps: JsonNode? = null,
    val takeProfitBps: JsonNode? = null,
    val stopLossBps: JsonNode? = null,
    val minMarketCapUsd: JsonNode? = null,
    val horizonSlots: JsonNode? = null,
    val latencySlots: JsonNode? = null,
    val transactionCostSol: JsonNode? = null,
    val quoteMint: JsonNode? = null,
    @param:JsonAnySetter
    val unknownFields: Map<String, JsonNode> = emptyMap(),
)
