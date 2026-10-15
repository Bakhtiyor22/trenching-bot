package com.trenching.bot.console.domain

import com.trenching.bot.common.MintAddress
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.replay.domain.ReplayDataset

/**
 * What one chart is built from: the [observations] to draw, the pair's [quote], the [dataset] that supplies the rates and
 * tracked wallet (null when a run's dataset left the catalog) and, for a run, its [run] detail.
 */
data class ChartSource(
    val observations: List<MarketObservation>,
    val quote: MintAddress,
    val dataset: ReplayDataset?,
    val run: RunDetail?,
)
