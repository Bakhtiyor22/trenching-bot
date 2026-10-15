package com.trenching.bot.marketdata.domain

import com.trenching.bot.marketdata.MarketHistoryManifest
import com.trenching.bot.marketdata.MarketListedSignature
import com.trenching.bot.marketdata.MarketTransactionOutcome
import com.trenching.bot.marketdata.MarketTransactionRecord
import com.trenching.bot.marketdata.MarketWindowCoverage
import com.trenching.bot.marketdata.MarketWindowCutoff
import com.trenching.bot.marketdata.MarketWindowRecord

/**
 * How a window's cutoff bounds what a market capture keeps. A decided manifest is a function of each window's cutoff slot
 * only: a record is `NOT_NEEDED` exactly when every complete window that selects it was decided before its slot.
 */
internal object MarketCutoffRules {
    fun observedThrough(window: MarketWindowRecord): Long = window.cutoff?.throughSlot ?: window.toSlot

    fun observedSelection(window: MarketWindowRecord): List<MarketListedSignature> =
        observedThrough(window).let { through -> window.selection.filter { it.slot <= through } }

    /**
     * The last slot through which every selected record is captured. Within-slot order is not recorded, so a record that
     * is not captured ends the prefix before its slot; a slot without a selected record needs no fetch.
     */
    fun prefixEnd(window: MarketWindowRecord, outcomes: Map<String, MarketTransactionOutcome>): Long {
        val firstMissing = window.selection.filter { outcomes[it.signature] != MarketTransactionOutcome.CAPTURED }.minOfOrNull { it.slot }
            ?: return window.toSlot
        return minOf(window.toSlot, firstMissing - 1)
    }

    fun decided(manifest: MarketHistoryManifest, index: Int, cutoff: MarketWindowCutoff): MarketHistoryManifest =
        reconciled(withCutoff(manifest, index, cutoff))

    fun reopened(manifest: MarketHistoryManifest, index: Int): MarketHistoryManifest = reconciled(withCutoff(manifest, index, null))

    /** Marks the records no window needs as `NOT_NEEDED` without an artifact, and makes a needed `NOT_NEEDED` record pending. */
    fun reconciled(manifest: MarketHistoryManifest): MarketHistoryManifest {
        val notNeeded = notNeeded(manifest.windows)
        val records = manifest.records.map { record ->
            when {
                record.signature in notNeeded -> MarketTransactionRecord(record.signature, record.slot, MarketTransactionOutcome.NOT_NEEDED)
                record.outcome == MarketTransactionOutcome.NOT_NEEDED ->
                    MarketTransactionRecord(record.signature, record.slot, MarketTransactionOutcome.PENDING)
                else -> record
            }
        }
        return manifest.copy(records = records)
    }

    /** The signatures every selecting complete window was decided before. */
    fun notNeeded(windows: List<MarketWindowRecord>): Set<String> {
        val needed = HashMap<String, Boolean>()
        windows.filter { it.coverage == MarketWindowCoverage.COMPLETE }.forEach { window ->
            window.selection.forEach { listed ->
                val decidedBefore = window.cutoff?.let { it.throughSlot < listed.slot } == true
                needed.merge(listed.signature, !decidedBefore, Boolean::or)
            }
        }
        return needed.filterValues { !it }.keys
    }

    private fun withCutoff(manifest: MarketHistoryManifest, index: Int, cutoff: MarketWindowCutoff?): MarketHistoryManifest =
        manifest.copy(windows = manifest.windows.toMutableList().apply { set(index, get(index).copy(cutoff = cutoff)) })
}
