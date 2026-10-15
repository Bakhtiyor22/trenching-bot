package com.trenching.bot.marketdata

import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.domain.MarketObservation
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Stable, path-safe identity derived from the exact mint, wallet anchor signature, and inclusive slot window. */
data class MarketCaseId private constructor(val value: String) {
    companion object {
        private val format = Regex("case_[0-9a-f]{64}")

        fun of(value: String): MarketCaseId {
            require(format.matches(value)) { "Invalid market case id" }
            return MarketCaseId(value)
        }

        fun from(window: MarketCaptureWindow): MarketCaseId {
            val identity = listOf(window.baseMint.value, window.untilSignature.value, window.fromSlot.value, window.toSlot.value)
                .joinToString("|")
            val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
            return MarketCaseId("case_$hash")
        }
    }
}

data class MarketCaseInventory(val directory: Path, val cases: List<MarketCaseProgress>)

/** [captured] and [unresolved] count only the selected transactions through [observedThrough]. */
data class MarketCaseProgress(
    val caseId: MarketCaseId,
    val window: MarketCaptureWindow,
    val coverage: MarketWindowCoverage,
    val stage: MarketCaseStage,
    val selected: Int,
    val captured: Int,
    val unresolved: Int,
    val blocker: String?,
    val observedThrough: Slot = window.toSlot,
)

enum class MarketCaseStage {
    DISCOVERY_PENDING,
    DISCOVERY_INCOMPLETE,
    DETAILS_PENDING,
    DETAILS_INCOMPLETE,
    READY_TO_VALIDATE,
}

data class MarketCaseDecodeResult(
    val progress: MarketCaseProgress,
    val windowParameters: MarketCaptureWindowParameters,
    val extractor: MarketObservationExtractorVersion,
    val observations: List<MarketObservation>,
    val exclusions: Map<MarketObservationExclusion, Int>,
    val detailGaps: Int,
    val ready: Boolean,
    val blocker: String?,
)
