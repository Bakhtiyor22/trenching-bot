package com.trenching.bot.console.api.dto

import tools.jackson.databind.JsonNode

/**
 * Raw JSON nodes so every field is checked for its JSON type: Jackson would otherwise coerce a
 * number into a String notional, or truncate 10.5 into an Int.
 */
data class ConsoleConfigRequest(
    val notional: JsonNode? = null,
    val feeBps: JsonNode? = null,
    val slippageBps: JsonNode? = null,
    val maxWaitSlots: JsonNode? = null,
    val maxAgeSlots: JsonNode? = null,
    val holdingSlots: JsonNode? = null,
    val quoteMint: JsonNode? = null,
)
