package com.trenching.bot.console.domain

import com.trenching.bot.strategy.domain.SimulationConfig

/** Configuration read from the immutable report. Lifecycle facts come from their store ports. */
data class StoredReportFacts(val config: SimulationConfig)
