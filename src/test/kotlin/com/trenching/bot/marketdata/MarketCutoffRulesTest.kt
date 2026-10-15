package com.trenching.bot.marketdata

import com.trenching.bot.common.Base58
import com.trenching.bot.common.MintAddress
import com.trenching.bot.marketdata.MarketTransactionOutcome.CAPTURED
import com.trenching.bot.marketdata.MarketTransactionOutcome.NOT_NEEDED
import com.trenching.bot.marketdata.MarketTransactionOutcome.PENDING
import com.trenching.bot.marketdata.MarketTransactionOutcome.UNAVAILABLE
import com.trenching.bot.marketdata.domain.MarketCutoffRules
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class MarketCutoffRulesTest {
    @Test
    fun `the prefix of a window whose every selected record is captured, or that selects nothing, ends at the window end`() {
        val window = window(MINT, 1001, 1100, 1005, 1010)

        assertEquals(1100, MarketCutoffRules.prefixEnd(window, outcomes(1005 to CAPTURED, 1010 to CAPTURED)))
        assertEquals(1100, MarketCutoffRules.prefixEnd(window(MINT, 1001, 1100), emptyMap()))
    }

    @Test
    fun `the prefix ends one slot before the oldest selected record that is not captured, whatever its outcome`() {
        val window = window(MINT, 1001, 1100, 1001, 1005, 1010, 1020)

        assertEquals(1004, MarketCutoffRules.prefixEnd(window, outcomes(1001 to CAPTURED, 1005 to UNAVAILABLE, 1010 to CAPTURED, 1020 to PENDING)))
        assertEquals(1019, MarketCutoffRules.prefixEnd(window, outcomes(1001 to CAPTURED, 1005 to CAPTURED, 1010 to CAPTURED, 1020 to PENDING)))
        assertEquals(1000, MarketCutoffRules.prefixEnd(window, outcomes(1001 to PENDING, 1005 to CAPTURED, 1010 to CAPTURED, 1020 to CAPTURED)))
    }

    @Test
    fun `a decision keeps the records through its slot and turns every later one into NOT_NEEDED without an artifact`() {
        val window = window(MINT, 1001, 1100, 1001, 1005, 1010, 1020)
        val manifest = manifest(listOf(window), records(1001 to CAPTURED, 1005 to CAPTURED, 1010 to CAPTURED, 1020 to UNAVAILABLE))

        val decided = MarketCutoffRules.decided(manifest, 0, CUTOFF.copy(throughSlot = 1005))

        assertEquals(CUTOFF.copy(throughSlot = 1005), decided.windows.single().cutoff)
        assertEquals(listOf(CAPTURED, CAPTURED, NOT_NEEDED, NOT_NEEDED), decided.records.map { it.outcome })
        assertEquals(manifest.records.take(2), decided.records.take(2))
        decided.records.drop(2).forEach { record ->
            assertNull(record.artifact)
            assertNull(record.sha256)
            assertEquals(0, record.bytes)
        }
    }

    @Test
    fun `a record shared with a window that still needs it stays, and becomes NOT_NEEDED once no selecting window needs it`() {
        val decidedFirst = window(MINT, 1001, 1100, 1005, 1010)
        val other = window(OTHER_MINT, 1001, 1100, 1010)
        val manifest = manifest(listOf(decidedFirst, other), records(1005 to CAPTURED, 1010 to CAPTURED))

        val undecidedOther = MarketCutoffRules.decided(manifest, 0, CUTOFF.copy(throughSlot = 1005))
        assertEquals(listOf(CAPTURED, CAPTURED), undecidedOther.records.map { it.outcome })

        val otherDecidedAtTheRecord = MarketCutoffRules.decided(undecidedOther, 1, CUTOFF.copy(throughSlot = 1010))
        assertEquals(listOf(CAPTURED, CAPTURED), otherDecidedAtTheRecord.records.map { it.outcome })

        val otherDecidedBefore = MarketCutoffRules.decided(undecidedOther, 1, CUTOFF.copy(throughSlot = 1009))
        assertEquals(listOf(CAPTURED, NOT_NEEDED), otherDecidedBefore.records.map { it.outcome })
    }

    @Test
    fun `reopening a decided window clears its cutoff and makes its NOT_NEEDED records pending while captured ones stay`() {
        val window = window(MINT, 1001, 1100, 1001, 1005, 1010)
        val decided = MarketCutoffRules.decided(
            manifest(listOf(window), records(1001 to CAPTURED, 1005 to CAPTURED, 1010 to CAPTURED)), 0, CUTOFF.copy(throughSlot = 1001))

        val reopened = MarketCutoffRules.reopened(decided, 0)

        assertNull(reopened.windows.single().cutoff)
        assertEquals(listOf(CAPTURED, PENDING, PENDING), reopened.records.map { it.outcome })
        assertEquals(decided.records.first(), reopened.records.first())
    }

    @Test
    fun `a window that newly selects a NOT_NEEDED record of a decided window makes it pending again`() {
        val decidedWindow = window(MINT, 1001, 1100, 1005, 1010)
        val decided = MarketCutoffRules.decided(manifest(listOf(decidedWindow), records(1005 to CAPTURED, 1010 to CAPTURED)), 0,
            CUTOFF.copy(throughSlot = 1005))
        val admitted = decided.copy(windows = decided.windows + window(OTHER_MINT, 1001, 1100, 1010))

        assertEquals(listOf(CAPTURED, PENDING), MarketCutoffRules.reconciled(admitted).records.map { it.outcome })
    }

    @Test
    fun `the observed end of a window is its cutoff slot or else its last slot`() {
        val window = window(MINT, 1001, 1100)

        assertEquals(1100, MarketCutoffRules.observedThrough(window))
        assertEquals(1000, MarketCutoffRules.observedThrough(window.copy(cutoff = CUTOFF.copy(throughSlot = 1000))))
    }

    private fun window(mint: String, from: Long, to: Long, vararg slots: Long): MarketWindowRecord {
        val selection = slots.sortedDescending().map { MarketListedSignature(signature(it), it) }
        return MarketWindowRecord(mint, MintAddress.WRAPPED_SOL.value, from, to, signature(1), MarketWindowCoverage.COMPLETE, 1,
            selection.size, 0, 0, selection.size, selection, selection.lastOrNull())
    }

    private fun outcomes(vararg outcomes: Pair<Int, MarketTransactionOutcome>): Map<String, MarketTransactionOutcome> =
        outcomes.associate { (slot, outcome) -> signature(slot.toLong()) to outcome }

    private fun records(vararg outcomes: Pair<Int, MarketTransactionOutcome>): List<MarketTransactionRecord> =
        outcomes.map { (number, outcome) ->
            val slot = number.toLong()
            if (outcome == CAPTURED) MarketTransactionRecord(signature(slot), slot, outcome, "transactions/${signature(slot)}.json", "0".repeat(64), 10)
            else MarketTransactionRecord(signature(slot), slot, outcome)
        }

    private fun manifest(windows: List<MarketWindowRecord>, records: List<MarketTransactionRecord>) = MarketHistoryManifest(
        2, "genesis", "confirmed", "2026-10-04T00:00:00Z", MarketCaptureWindowParameters(4500, 2), 1, 300, windows, records,
        acquisitionComplete = false, complete = false, requestsUsed = 0, bytesUsed = 0)

    private fun signature(slot: Long): String = Base58.encode(ByteArray(64).also { it[62] = (slot shr 8).toByte(); it[63] = slot.toByte() })

    private companion object {
        const val MINT = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
        const val OTHER_MINT = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
        val CUTOFF = MarketWindowCutoff(1005, "TAKE_PROFIT", "engine-v1", "a".repeat(64))
    }
}
