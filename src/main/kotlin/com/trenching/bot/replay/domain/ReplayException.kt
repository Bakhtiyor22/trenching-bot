package com.trenching.bot.replay.domain

open class ReplayException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class SimulationRunConflictException(runId: String) :
    ReplayException("Stored simulation result conflicts with replay runId=$runId")

class SimulationRunNotFoundException(runId: String) :
    ReplayException("Simulation run not found runId=$runId")

class DatasetNotFoundException(id: String) :
    ReplayException("Replay dataset not found datasetId=$id")

/** [message] is the loader's validation message, unchanged. */
class DatasetInvalidException(message: String, cause: Throwable? = null) : ReplayException(message, cause)

/**
 * A run asking for the wallet-sell rule on a dataset whose observations record no trades is refused, since it could not see the
 * wallet's sells and would read their absence as none.
 */
class SellCheckNeedsTradesException(val schemaVersion: Int) : ReplayException(
    "The wallet-sell rule needs a dataset whose observations record trades schemaVersion=$schemaVersion " +
        "minimumSchemaVersion=${ReplayDataset.SCHEMA_V4} ($CODE)") {
    companion object {
        const val CODE = "SELL_CHECK_NEEDS_TRADES"
    }
}

/** A run would read slots its dataset never observed, so it is refused instead of reading the gap as no trades. */
class MarketCoverageEndedException(val shortfall: CoverageShortfall) : ReplayException(
    "Market coverage ends before the outcome is decided mint=${shortfall.pair.base.value} decidedAt=${shortfall.decidedAt.value} " +
        "throughSlot=${shortfall.throughSlot?.value ?: "none"} (${VerdictReasons.MARKET_COVERAGE_ENDED})")
