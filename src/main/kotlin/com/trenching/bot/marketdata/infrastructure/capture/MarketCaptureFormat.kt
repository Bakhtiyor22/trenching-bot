package com.trenching.bot.marketdata.infrastructure.capture

import com.trenching.bot.marketdata.MarketCaptureWindowParameters
import com.trenching.bot.marketdata.MarketHistoryCaptureLimits
import com.trenching.bot.marketdata.MarketTransactionOutcome
import com.trenching.bot.marketdata.MarketTransactionRecord
import com.trenching.bot.marketdata.MarketWindowCoverage
import com.trenching.bot.marketdata.MarketWindowRecord
import java.time.Duration

internal object MarketCaptureFormat {
    /** Version 2: windows follow a horizon after each mint's first buy, not entry offsets and a holding period. */
    const val VERSION = 2

    /** Version 3: version 2 plus a window cutoff and the `NOT_NEEDED` outcome; only a manifest holding a cutoff uses it. */
    const val CUTOFF_VERSION = 3

    /**
     * Version 4: windows start `windowParameters.leadSlots` before each first buy and list through it, with or without a
     * cutoff. Only a capture planned with a lead uses it; versions 2 and 3 carry no lead and mean the legacy `S + 1` start.
     */
    const val LEAD_VERSION = 4
    val SUPPORTED_VERSIONS = setOf(VERSION, CUTOFF_VERSION, LEAD_VERSION)

    /** Bounds a cutoff's outcome, engine version and configuration hash. */
    const val MAX_CUTOFF_TEXT = 128

    /** Transactions fetched between manifest checkpoints; a resume refetches any newer, unreferenced artifact. */
    const val DETAIL_CHECKPOINT_INTERVAL = 100
    const val MANIFEST = "market-manifest.json"
    const val MAX_WINDOWS = MarketHistoryCaptureLimits.MAX_WINDOWS
    const val MAX_TRANSACTIONS = MarketHistoryCaptureLimits.MAX_TRANSACTIONS
    const val MAX_SCAN_PAGES = MarketHistoryCaptureLimits.MAX_SCAN_PAGES
    const val MAX_REQUESTS = MarketHistoryCaptureLimits.MAX_REQUESTS
    const val MAX_CAPTURE_BYTES = MarketHistoryCaptureLimits.MAX_BYTES
    val UNRESOLVED_OUTCOMES = setOf(MarketTransactionOutcome.PENDING, MarketTransactionOutcome.PROVIDER_UNAVAILABLE, MarketTransactionOutcome.INVALID_TRANSACTION)

    /** Coverages whose discovery still needs provider requests: a capture holding one has not finished its acquisition. */
    val OPEN_COVERAGES = setOf(MarketWindowCoverage.PENDING, MarketWindowCoverage.HORIZON_NOT_ELAPSED)

    /** Mainnet's target slot time, for estimating a held window's end when the recent slot duration cannot be measured. */
    val NOMINAL_SLOT_DURATION: Duration = Duration.ofMillis(400)

    /** Added to a held window's estimated wait, so a retry rarely arrives just before its end and is held again. */
    const val WAIT_MARGIN_PERCENT = 10L

    /** Whether no window or record of a manifest still needs a provider request. */
    fun isAcquired(windows: List<MarketWindowRecord>, records: List<MarketTransactionRecord>): Boolean =
        windows.none { it.coverage in OPEN_COVERAGES } && records.none { it.outcome in UNRESOLVED_OUTCOMES }

    /**
     * A capture with a lead is version 4. Without one, a manifest without a cutoff keeps version 2, so a full capture, and a
     * fully extended one, stays readable as before; an old capture is never rewritten to a newer version.
     */
    fun versionFor(parameters: MarketCaptureWindowParameters, windows: List<MarketWindowRecord>): Int = when {
        hasLead(parameters) -> LEAD_VERSION
        windows.any { it.cutoff != null } -> CUTOFF_VERSION
        else -> VERSION
    }

    /** Whether windows start before their first buy and are therefore listed through it rather than only newer than it. */
    fun hasLead(parameters: MarketCaptureWindowParameters): Boolean = parameters.leadSlots != MarketCaptureWindowParameters.LEGACY_LEAD_SLOTS

    fun isCutoffText(value: String): Boolean = value.isNotBlank() && value.length <= MAX_CUTOFF_TEXT
}
