package com.trenching.bot.console.solana.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import tools.jackson.databind.JsonNode

/** One `getSignaturesForAddress` entry. [err] is a JSON null (or absent) for a successful transaction. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SignatureInfo(
    val signature: String,
    val slot: Long,
    val err: JsonNode? = null,
)
