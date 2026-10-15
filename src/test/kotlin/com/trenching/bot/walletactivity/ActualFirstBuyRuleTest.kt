package com.trenching.bot.walletactivity

import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.walletactivity.domain.ActualFirstBuyEvidence
import com.trenching.bot.walletactivity.domain.ActualFirstBuyRule
import com.trenching.bot.walletactivity.domain.CreationCandidate
import com.trenching.bot.walletactivity.domain.CreationCandidateKind
import com.trenching.bot.walletactivity.domain.CreationSearchEvidence
import com.trenching.bot.walletactivity.domain.ListingScanEvidence
import com.trenching.bot.walletactivity.domain.VerificationEntry
import com.trenching.bot.walletactivity.domain.VerificationEntryKind
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ActualFirstBuyRuleTest {
    @Test
    fun `a verified buy is proven with its creation and how far each listing was scanned`() {
        val result = evaluate(entries = listOf(
            entry(OTHER, 950, VerificationEntryKind.IRRELEVANT),
            entry(B, B_SLOT, VerificationEntryKind.UNKNOWN),
        ))

        assertEquals(ActualFirstBuy.Proven(
            signature = signature(B), slot = Slot.of(B_SLOT), instructionIndex = 2,
            creationSignature = signature(CREATION), creationSlot = Slot.of(CREATION_SLOT), creationTime = AFTER_COVERAGE,
            scannedTo = mapOf(VerificationListing.MINT to Slot.of(800), VerificationListing.PUMP_ACCUMULATOR to Slot.of(850)),
        ), result)
    }

    @Test
    fun `an older candidate without the creation is ignored and the oldest creation found is the boundary`() {
        val result = evaluate(creation = creation(fetched = listOf(
            candidate(TRANSFER, 800, CreationCandidateKind.Other),
            candidate(UNKNOWN, 850, CreationCandidateKind.Unknown),
            candidate(CREATION, CREATION_SLOT, CreationCandidateKind.Creation(AFTER_COVERAGE)),
        )))
        assertEquals(signature(CREATION), (result as ActualFirstBuy.Proven).creationSignature)
    }

    @Test
    fun `an earlier decoded wallet buy is reported outside the token account and the earliest one wins over every gap`() {
        val result = evaluate(entries = listOf(
            entry(LATER_BUY, 990, VerificationEntryKind.WALLET_BUY),
            entry(EARLIER_BUY, 960, VerificationEntryKind.WALLET_BUY),
            entry(UNDECODED, 950, VerificationEntryKind.UNDECODED_ACQUISITION),
            entry(UNKNOWN, 940, VerificationEntryKind.UNKNOWN),
        ))
        assertEquals(ActualFirstBuy.OutsideTokenAccount(signature(EARLIER_BUY), Slot.of(960)), result)
    }

    @Test
    fun `the earliest decoded wallet buy decides as soon as nothing unresolved precedes it or shares its slot`() {
        val result = evaluate(entries = listOf(
            entry(EARLIER_BUY, 960, VerificationEntryKind.WALLET_BUY),
            entry(UNKNOWN, 950, VerificationEntryKind.UNKNOWN),
            entry(LATER_BUY, 980, VerificationEntryKind.UNRESOLVED),
        ))
        assertEquals(ActualFirstBuy.OutsideTokenAccount(signature(EARLIER_BUY), Slot.of(960)), result)
    }

    @Test
    fun `an unresolved entry before or in the slot of the earliest decoded wallet buy keeps verification open`() {
        listOf(950L, 960L).forEach { slot ->
            val result = evaluate(entries = listOf(entry(EARLIER_BUY, 960, VerificationEntryKind.WALLET_BUY), entry(UNKNOWN, slot, VerificationEntryKind.UNRESOLVED)))
            assertEquals(ActualFirstBuy.NotVerified, result, "slot=$slot")
        }
    }

    @Test
    fun `an outside account buy beats a fetch cap reached on later entries`() {
        val result = evaluate(entries = listOf(
            entry(EARLIER_BUY, 960, VerificationEntryKind.WALLET_BUY),
            entry(LATER_BUY, 980, VerificationEntryKind.UNRESOLVED),
        ), fetches = 500)
        assertEquals(ActualFirstBuy.OutsideTokenAccount(signature(EARLIER_BUY), Slot.of(960)), result)
    }

    @Test
    fun `a pump accumulator scan that reached below the creation without listing the buy leaves it unproven as not listed`() {
        listOf(PUMP_COMPLETE, ListingScanEvidence(1, 0, null, true)).forEach { pump ->
            val result = evaluate(pump = pump, pumpListed = false, entries = listOf(entry(EARLIER_BUY, 960, VerificationEntryKind.WALLET_BUY)))
            assertEquals(unproven(FirstBuyGap.FIRST_BUY_NOT_LISTED, B), result, "$pump")
        }
        assertEquals(ActualFirstBuy.NotVerified, evaluate(pump = ListingScanEvidence(2, 2000, 960, false), pumpListed = false))
    }

    @Test
    fun `a buy that creates its own mint is its creation boundary without any mint listing`() {
        val noMintListing = creation(listing = ListingScanEvidence(0, 0, null, false), candidates = 0, fetched = emptyList())
        val result = evaluate(firstBuyKind = CreationCandidateKind.Creation(AFTER_COVERAGE), creation = noMintListing,
            pump = ListingScanEvidence(1, 3, B_SLOT - 1, true), entries = listOf(entry(OTHER, 950, VerificationEntryKind.WALLET_BUY)))

        assertEquals(ActualFirstBuy.Proven(
            signature = signature(B), slot = Slot.of(B_SLOT), instructionIndex = 2,
            creationSignature = signature(B), creationSlot = Slot.of(B_SLOT), creationTime = AFTER_COVERAGE,
            scannedTo = mapOf(VerificationListing.PUMP_ACCUMULATOR to Slot.of(B_SLOT - 1)),
        ), result)
    }

    @Test
    fun `a creating buy is still checked for coverage, accumulator scans below its slot and other entries in its slot`() {
        val noMintListing = creation(listing = ListingScanEvidence(0, 0, null, false), candidates = 0, fetched = emptyList())
        val creating = CreationCandidateKind.Creation(AFTER_COVERAGE)
        assertEquals(unproven(FirstBuyGap.PRE_ACCUMULATOR_MINT, B), evaluate(firstBuyKind = CreationCandidateKind.Creation(null), creation = noMintListing))
        assertEquals(ActualFirstBuy.NotVerified, evaluate(firstBuyKind = creating, creation = noMintListing, pump = ListingScanEvidence(1, 1000, B_SLOT, false)))
        assertEquals(unproven(FirstBuyGap.EARLIER_UNKNOWN_RECORD, LATER_BUY), evaluate(firstBuyKind = creating, creation = noMintListing,
            entries = listOf(entry(LATER_BUY, B_SLOT, VerificationEntryKind.UNKNOWN))))
    }

    @Test
    fun `an earlier undecoded acquisition such as a multi hop curve hop leaves the first buy unproven`() {
        val result = evaluate(entries = listOf(
            entry(UNDECODED, 950, VerificationEntryKind.UNDECODED_ACQUISITION),
            entry(UNDECODED, 950, VerificationEntryKind.UNDECODED_ACQUISITION, VerificationListing.PUMPSWAP_ACCUMULATOR),
            entry(OTHER, 920, VerificationEntryKind.UNDECODED_ACQUISITION, VerificationListing.PUMPSWAP_ACCUMULATOR),
            entry(UNKNOWN, 910, VerificationEntryKind.UNKNOWN),
        ))
        assertEquals(unproven(FirstBuyGap.EARLIER_UNDECODED_ACQUISITION, OTHER, UNDECODED), result)
    }

    @Test
    fun `an entry without an artifact leaves the first buy unproven`() {
        val result = evaluate(entries = listOf(entry(UNKNOWN, 950, VerificationEntryKind.UNKNOWN)))
        assertEquals(unproven(FirstBuyGap.EARLIER_UNKNOWN_RECORD, UNKNOWN), result)
    }

    @Test
    fun `a same slot entry blocks whichever side of the buy its signature sorts on and the buy itself never does`() {
        val sortsAfter = evaluate(entries = listOf(entry(B, B_SLOT, VerificationEntryKind.WALLET_BUY), entry(LATER_BUY, B_SLOT, VerificationEntryKind.UNKNOWN)))
        assertEquals(unproven(FirstBuyGap.EARLIER_UNKNOWN_RECORD, LATER_BUY), sortsAfter)
        val sortsBefore = evaluate(entries = listOf(entry(TRANSFER, B_SLOT, VerificationEntryKind.UNDECODED_ACQUISITION)))
        assertEquals(unproven(FirstBuyGap.EARLIER_UNDECODED_ACQUISITION, TRANSFER), sortsBefore)
    }

    @Test
    fun `entries outside the creation to buy range never decide`() {
        val result = evaluate(entries = listOf(
            entry(LATER_BUY, B_SLOT + 1, VerificationEntryKind.WALLET_BUY),
            entry(EARLIER_BUY, CREATION_SLOT - 1, VerificationEntryKind.WALLET_BUY),
        ))
        assertEquals(signature(B), (result as ActualFirstBuy.Proven).signature)
    }

    @Test
    fun `an entry in the creation slot is in range`() {
        val result = evaluate(entries = listOf(entry(UNKNOWN, CREATION_SLOT, VerificationEntryKind.UNKNOWN)))
        assertEquals(unproven(FirstBuyGap.EARLIER_UNKNOWN_RECORD, UNKNOWN), result)
    }

    @Test
    fun `a creation before the accumulator coverage date or without a block time is a pre accumulator mint`() {
        listOf(Instant.parse("2025-08-01T11:31:27Z"), null).forEach { time ->
            val result = evaluate(creation = creation(fetched = listOf(candidate(CREATION, CREATION_SLOT, CreationCandidateKind.Creation(time)))))
            assertEquals(unproven(FirstBuyGap.PRE_ACCUMULATOR_MINT, CREATION), result, "$time")
        }
        val atCoverage = evaluate(creation = creation(fetched = listOf(
            candidate(CREATION, CREATION_SLOT, CreationCandidateKind.Creation(Instant.parse("2025-08-01T11:31:28Z"))))))
        assertEquals(signature(B), (atCoverage as ActualFirstBuy.Proven).signature)
    }

    @Test
    fun `a trade of the mint older than its creation means the creation is not identified`() {
        val result = evaluate(creation = creation(fetched = listOf(
            candidate(TRANSFER, 800, CreationCandidateKind.Trade),
            candidate(CREATION, CREATION_SLOT, CreationCandidateKind.Creation(AFTER_COVERAGE)),
        )))
        assertEquals(unproven(FirstBuyGap.CREATION_NOT_IDENTIFIED, TRANSFER), result)
    }

    @Test
    fun `a trade after an unknown candidate blocks on the unknown candidate, which could be the creation`() {
        val result = evaluate(creation = creation(fetched = listOf(
            candidate(UNKNOWN, 790, CreationCandidateKind.Unknown),
            candidate(TRANSFER, 800, CreationCandidateKind.Trade),
        )))
        assertEquals(unproven(FirstBuyGap.CREATION_NOT_IDENTIFIED, UNKNOWN), result)
    }

    @Test
    fun `no creation among every candidate or within ten fetches means the creation is not identified`() {
        val allFetched = evaluate(creation = creation(candidates = 2, fetched = listOf(
            candidate(TRANSFER, 800, CreationCandidateKind.Other), candidate(UNKNOWN, 850, CreationCandidateKind.Unknown))))
        assertEquals(unproven(FirstBuyGap.CREATION_NOT_IDENTIFIED), allFetched)
        val tenFetched = evaluate(creation = creation(candidates = 10,
            fetched = "ABCDEFGHJK".mapIndexed { index, last -> candidate("1".repeat(63) + last, 801L + index, CreationCandidateKind.Other) }))
        assertEquals(unproven(FirstBuyGap.CREATION_NOT_IDENTIFIED), tenFetched)
        val emptyListing = evaluate(creation = creation(candidates = 0, fetched = emptyList(), listing = ListingScanEvidence(1, 0, null, true)))
        assertEquals(unproven(FirstBuyGap.CREATION_NOT_IDENTIFIED), emptyListing)
    }

    @Test
    fun `evidence that still needs acquisition is not verified`() {
        listOf(
            "mint listing in progress" to evidence(creation = creation(listing = ListingScanEvidence(3, 3000, 700, false))),
            "candidate not fetched" to evidence(creation = creation(candidates = 3, fetched = listOf(candidate(TRANSFER, 800, CreationCandidateKind.Other)))),
            "candidate to retry" to evidence(creation = creation(fetched = listOf(candidate(CREATION, CREATION_SLOT, CreationCandidateKind.Unresolved)))),
            "pump scan in progress" to evidence(pump = ListingScanEvidence(2, 2000, 960, false)),
            "pumpswap scan not started" to evidence(pumpSwap = ListingScanEvidence(0, 0, null, false)),
            "entry not fetched" to evidence(entries = listOf(entry(UNKNOWN, 950, VerificationEntryKind.UNRESOLVED))),
        ).forEach { (name, evidence) -> assertEquals(ActualFirstBuy.NotVerified, ActualFirstBuyRule.evaluate(evidence), name) }
    }

    @Test
    fun `a pump accumulator scan reaching only its creation slot is not complete`() {
        val result = evaluate(pump = ListingScanEvidence(1, 1000, CREATION_SLOT, false))
        assertEquals(ActualFirstBuy.NotVerified, result)
    }

    @Test
    fun `each cap reached before its bound is a verification bound with what was scanned`() {
        val mintCapped = ListingScanEvidence(50, 50_000, 700, false)
        assertEquals(bound(VerificationLimit.MINT_PAGES, mintPages = 50, mintEntries = 50_000, mintOldest = 700, pumpPages = 0, pumpEntries = 0, pumpOldest = null,
            pumpSwapPages = 0, pumpSwapEntries = 0, pumpSwapOldest = null, fetches = 0),
            evaluate(creation = creation(listing = mintCapped, candidates = 0, fetched = emptyList()), pump = NOT_STARTED, pumpSwap = NOT_STARTED, fetches = 0))

        val pumpCapped = ListingScanEvidence(50, 50_000, 960, false)
        assertEquals(bound(VerificationLimit.PUMP_ACCUMULATOR_PAGES, pumpPages = 50, pumpEntries = 50_000, pumpOldest = 960,
            pumpSwapPages = 0, pumpSwapEntries = 0, pumpSwapOldest = null),
            evaluate(pump = pumpCapped, pumpSwap = NOT_STARTED))

        val pumpSwapCapped = ListingScanEvidence(50, 50_000, 990, false)
        assertEquals(bound(VerificationLimit.PUMPSWAP_ACCUMULATOR_PAGES, pumpSwapPages = 50, pumpSwapEntries = 50_000, pumpSwapOldest = 990),
            evaluate(pumpSwap = pumpSwapCapped))

        assertEquals(bound(VerificationLimit.FETCHES, fetches = 500),
            evaluate(entries = listOf(entry(UNKNOWN, 950, VerificationEntryKind.UNRESOLVED)), fetches = 500))
        assertEquals(bound(VerificationLimit.FETCHES, pumpPages = 0, pumpEntries = 0, pumpOldest = null,
            pumpSwapPages = 0, pumpSwapEntries = 0, pumpSwapOldest = null, fetches = 500),
            evaluate(creation = creation(candidates = 3, fetched = listOf(candidate(TRANSFER, 800, CreationCandidateKind.Other))),
                pump = NOT_STARTED, pumpSwap = NOT_STARTED, fetches = 500))
    }

    @Test
    fun `a cap reached after the creation search ended is not a bound on it`() {
        val result = evaluate(creation = creation(listing = ListingScanEvidence(50, 49_999, 800, true)))
        assertEquals(signature(B), (result as ActualFirstBuy.Proven).signature)
    }

    private fun evaluate(
        creation: CreationSearchEvidence = creation(),
        pump: ListingScanEvidence = PUMP_COMPLETE,
        pumpSwap: ListingScanEvidence = PUMPSWAP_EMPTY,
        entries: List<VerificationEntry> = emptyList(),
        fetches: Int = 3,
        firstBuyKind: CreationCandidateKind = CreationCandidateKind.Trade,
        pumpListed: Boolean = true,
    ): ActualFirstBuy = ActualFirstBuyRule.evaluate(evidence(creation, pump, pumpSwap, entries, fetches, firstBuyKind, pumpListed))

    private fun evidence(
        creation: CreationSearchEvidence = creation(),
        pump: ListingScanEvidence = PUMP_COMPLETE,
        pumpSwap: ListingScanEvidence = PUMPSWAP_EMPTY,
        entries: List<VerificationEntry> = emptyList(),
        fetches: Int = 3,
        firstBuyKind: CreationCandidateKind = CreationCandidateKind.Trade,
        pumpListed: Boolean = true,
    ) = ActualFirstBuyEvidence(TokenAccountFirstBuy.Found(signature(B), Slot.of(B_SLOT), 2), firstBuyKind, creation, pump, pumpSwap,
        pumpListed, entries, fetches)

    private fun creation(
        listing: ListingScanEvidence = ListingScanEvidence(1, 12, 800, true),
        candidates: Int = 2,
        fetched: List<CreationCandidate> = listOf(
            candidate(TRANSFER, 800, CreationCandidateKind.Other),
            candidate(CREATION, CREATION_SLOT, CreationCandidateKind.Creation(AFTER_COVERAGE)),
        ),
    ) = CreationSearchEvidence(listing, candidates, fetched)

    private fun candidate(value: String, slot: Long, kind: CreationCandidateKind) = CreationCandidate(signature(value), Slot.of(slot), kind)

    private fun entry(value: String, slot: Long, kind: VerificationEntryKind, listing: VerificationListing = VerificationListing.PUMP_ACCUMULATOR) =
        VerificationEntry(signature(value), Slot.of(slot), listing, kind)

    private fun unproven(reason: FirstBuyGap, vararg blocking: String) = ActualFirstBuy.Unproven(reason, blocking.map(::signature), null)

    private fun bound(
        limit: VerificationLimit,
        mintPages: Int = 1, mintEntries: Int = 12, mintOldest: Long? = 800,
        pumpPages: Int = 1, pumpEntries: Int = 7, pumpOldest: Long? = 850,
        pumpSwapPages: Int = 1, pumpSwapEntries: Int = 0, pumpSwapOldest: Long? = null,
        fetches: Int = 3,
    ) = ActualFirstBuy.Unproven(FirstBuyGap.VERIFICATION_BOUND, emptyList(), VerificationBoundHit(
        limit = limit,
        pages = mapOf(VerificationListing.MINT to mintPages, VerificationListing.PUMP_ACCUMULATOR to pumpPages, VerificationListing.PUMPSWAP_ACCUMULATOR to pumpSwapPages),
        entries = mapOf(VerificationListing.MINT to mintEntries, VerificationListing.PUMP_ACCUMULATOR to pumpEntries, VerificationListing.PUMPSWAP_ACCUMULATOR to pumpSwapEntries),
        fetches = fetches,
        oldestSlot = mapOf(VerificationListing.MINT to mintOldest, VerificationListing.PUMP_ACCUMULATOR to pumpOldest, VerificationListing.PUMPSWAP_ACCUMULATOR to pumpSwapOldest),
    ))

    private fun signature(value: String) = TransactionSignature.of(value)

    private companion object {
        const val B_SLOT = 1000L
        const val CREATION_SLOT = 900L
        val AFTER_COVERAGE: Instant = Instant.parse("2026-10-09T00:00:00Z")
        val PUMP_COMPLETE = ListingScanEvidence(1, 7, 850, true)
        val PUMPSWAP_EMPTY = ListingScanEvidence(1, 0, null, true)
        val NOT_STARTED = ListingScanEvidence(0, 0, null, false)
        val B = "1".repeat(63) + "2"
        val CREATION = "1".repeat(63) + "3"
        val TRANSFER = "1".repeat(63) + "4"
        val OTHER = "1".repeat(63) + "5"
        val UNKNOWN = "1".repeat(63) + "6"
        val UNDECODED = "1".repeat(63) + "7"
        val EARLIER_BUY = "1".repeat(63) + "8"
        val LATER_BUY = "1".repeat(63) + "9"
    }
}
