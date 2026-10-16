package com.trenching.bot.console.api.dto

/**
 * A run preset: [config] in the shape of `/api/config/defaults`, [fields] the status of each configuration field against
 * the spec, and [unsupportedRules] the spec rules the engine cannot apply.
 */
data class RunPresetResponse(
    val id: String,
    val source: PresetSourceResponse,
    val engine: String,
    val config: ConfigResponse,
    val fields: List<PresetFieldResponse>,
    val unsupportedRules: List<UnsupportedRuleResponse>,
)

/** [updated] is the spec's last-updated ISO date. */
data class PresetSourceResponse(
    val document: String,
    val strategy: String,
    val version: Int,
    val updated: String,
)

data class PresetFieldResponse(
    val field: String,
    val status: String,
    val specRef: String?,
    val noteCode: String?,
)

data class UnsupportedRuleResponse(
    val specRef: String,
    val code: String,
)
