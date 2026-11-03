package com.trenching.bot.replay

import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.walletactivity.domain.WalletTransaction

/**
 * The market observations and wallet transactions a stored run replayed, whatever its storage, in canonical order. A
 * dataset-referenced run returns exactly what a copy run of the same dataset returns. Unknown runs throw
 * `SimulationRunNotFoundException`.
 */
interface RunInputs {
    fun observations(runId: String): List<MarketObservation>
    fun transactions(runId: String): List<WalletTransaction>
}
