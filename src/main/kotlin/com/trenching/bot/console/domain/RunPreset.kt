package com.trenching.bot.console.domain

import com.trenching.bot.strategy.domain.SimulationConfig
import java.time.LocalDate

/** How far a preset value is backed by the strategy spec it was recovered from. */
enum class PresetFieldStatus { AGREED, AGREED_MEASURED_DIFFERENTLY, ASSUMPTION, ENGINE_DEFAULT, MISSING }

data class PresetSource(val document: String, val strategy: String, val version: Int, val updated: LocalDate)

/** [field] is the configuration's JSON property; [specRef] is the spec parameter it comes from, if any. */
data class PresetField(val field: String, val status: PresetFieldStatus, val specRef: String?, val noteCode: String?)

/** A spec rule the preset's engine cannot apply. */
data class UnsupportedSpecRule(val specRef: String, val code: String)

/** A named starting configuration for a run, with the honest gaps between the spec and the engine that runs it. */
data class RunPreset(
    val id: String,
    val source: PresetSource,
    val engine: String,
    val config: SimulationConfig,
    val fields: List<PresetField>,
    val unsupportedRules: List<UnsupportedSpecRule>,
)
