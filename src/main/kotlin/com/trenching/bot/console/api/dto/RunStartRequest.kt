package com.trenching.bot.console.api.dto

/** A missing [config] runs with the `SimulationConfig.mvp()` defaults. */
data class RunStartRequest(
    val datasetId: String? = null,
    val config: ConsoleConfigRequest? = null,
)
