package com.trenching.bot.console.solana.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class SolanaVersion(
    @param:JsonProperty("solana-core") val solanaCore: String,
)
