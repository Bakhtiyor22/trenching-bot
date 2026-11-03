package com.trenching.bot.replay.domain

open class ReplayException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class SimulationRunConflictException(runId: String) :
    ReplayException("Stored simulation result conflicts with replay runId=$runId")

class SimulationRunNotFoundException(runId: String) :
    ReplayException("Simulation run not found runId=$runId")

class DatasetNotFoundException(id: String) :
    ReplayException("Replay dataset not found datasetId=$id")

/** Dataset-referenced runs read the dataset from the catalog, so it must stay while [runCount] of them exist. */
class DatasetInUseException(datasetId: String, val runCount: Long) :
    ReplayException("Replay dataset is read by dataset-referenced runs datasetId=$datasetId referenceRuns=$runCount")

/** [message] is the loader's validation message, unchanged. */
class DatasetInvalidException(message: String, cause: Throwable? = null) : ReplayException(message, cause)

/**
 * A run asking for a rule that reads the wallet's trades from the market data, on a dataset whose observations record none.
 * [code] is the refusal's API error code and qualification failure category.
 */
sealed class TradesRequiredException(val schemaVersion: Int, val code: String, rule: String) : ReplayException(
    "$rule needs a dataset whose observations record trades schemaVersion=$schemaVersion " +
        "minimumSchemaVersion=${ReplayDataset.SCHEMA_V4} ($code)")

/** The wallet-sell rule could not see the wallet's sells and would read their absence as none. */
class SellCheckNeedsTradesException(schemaVersion: Int) : TradesRequiredException(schemaVersion, CODE, "The wallet-sell rule") {
    companion object {
        const val CODE = "SELL_CHECK_NEEDS_TRADES"
    }
}

/** The minimum signal size could not read the curve payment of the wallet's buy, so no cycle could be sized. */
class SignalSizeNeedsTradesException(schemaVersion: Int) : TradesRequiredException(schemaVersion, CODE, "The minimum signal size") {
    companion object {
        const val CODE = "SIGNAL_SIZE_NEEDS_TRADES"
    }
}

/** A run would read slots its dataset never observed, so it is refused instead of reading the gap as no trades. */
class MarketCoverageEndedException(val shortfall: CoverageShortfall) : ReplayException(
    "Market coverage ends before the outcome is decided mint=${shortfall.pair.base.value} decidedAt=${shortfall.decidedAt.value} " +
        "throughSlot=${shortfall.throughSlot?.value ?: "none"} (${VerdictReasons.MARKET_COVERAGE_ENDED})")
