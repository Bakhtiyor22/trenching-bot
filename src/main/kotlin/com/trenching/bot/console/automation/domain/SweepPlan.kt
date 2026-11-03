package com.trenching.bot.console.automation.domain

import com.trenching.bot.strategy.domain.SimulationConfig
import java.math.BigDecimal

/** One configuration of a sweep, named by a short [label] that is unique in its plan. */
data class SweepEntry(val label: String, val config: SimulationConfig)

/** A fixed list of configurations replayed on every captured buy. A changed list is a new [id], never an edit. */
data class SweepPlan(val id: String, val entries: List<SweepEntry>) {
    init {
        require(id.length in 1..MAX_ID_LENGTH) { "Sweep plan id must have 1 to $MAX_ID_LENGTH characters id=$id" }
        require(entries.isNotEmpty()) { "Sweep plan has no entries id=$id" }
        require(entries.all { it.label.length in 1..MAX_LABEL_LENGTH }) { "Sweep entry labels must have 1 to $MAX_LABEL_LENGTH characters id=$id" }
        require(entries.distinctBy { it.label }.size == entries.size) { "Sweep entry labels must be distinct id=$id" }
    }

    /**
     * Plan Rule 6: a buy the capture filter skips must fail every entry's own minimum signal size, so [captureMinSignalSol]
     * may not exceed the smallest one, and an entry with no minimum (which would count every buy) is refused.
     */
    fun requireCaptureMinimumWithin(captureMinSignalSol: BigDecimal) {
        val unbounded = entries.filter { it.config.minSignalSol == null }.map { it.label }
        require(unbounded.isEmpty()) { "Sweep plan $id has entries without a minimum signal size labels=$unbounded" }
        val smallest = entries.mapNotNull { it.config.minSignalSol }.min()
        require(captureMinSignalSol <= smallest) {
            "trenching.automation.capture-min-signal-sol=${captureMinSignalSol.toPlainString()} must be at or below the smallest " +
                "minSignalSol=${smallest.stripTrailingZeros().toPlainString()} of sweep plan $id"
        }
    }

    private companion object {
        const val MAX_ID_LENGTH = 32
        const val MAX_LABEL_LENGTH = 16
    }
}
