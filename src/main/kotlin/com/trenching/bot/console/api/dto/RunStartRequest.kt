package com.trenching.bot.console.api.dto

/** A missing [config] runs with the `SimulationConfig.mvp()` defaults; [presetId] names the preset the form started from. */
data class RunStartRequest(
    val datasetId: String? = null,
    val config: ConsoleConfigRequest? = null,
    val presetId: String? = null,
)
