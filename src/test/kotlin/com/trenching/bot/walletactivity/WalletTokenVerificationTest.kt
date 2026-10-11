package com.trenching.bot.walletactivity

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.WalletTokenChain.Call
import com.trenching.bot.walletactivity.WalletTokenChain.Companion.BUY
import com.trenching.bot.walletactivity.WalletTokenChain.Companion.BUY_SLOT
import com.trenching.bot.walletactivity.WalletTokenChain.Companion.CREATOR
import com.trenching.bot.walletactivity.WalletTokenChain.Companion.MINT
import com.trenching.bot.walletactivity.WalletTokenChain.Companion.OTHER
import com.trenching.bot.walletactivity.WalletTokenChain.Companion.OTHER_TOKEN_ACCOUNT
import com.trenching.bot.walletactivity.WalletTokenChain.Companion.PUMPSWAP_ACCUMULATOR
import com.trenching.bot.walletactivity.WalletTokenChain.Companion.PUMP_ACCUMULATOR
import com.trenching.bot.walletactivity.WalletTokenChain.Companion.PUMP_FUN
import com.trenching.bot.walletactivity.WalletTokenChain.Companion.SYSTEM_PROGRAM
import com.trenching.bot.walletactivity.WalletTokenChain.Companion.TOKEN_ACCOUNT
import com.trenching.bot.walletactivity.WalletTokenChain.Companion.WALLET
import com.trenching.bot.walletactivity.WalletTokenChain.Entry
import com.trenching.bot.walletactivity.application.WalletHistoryCaptureService
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryArtifactStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode

class WalletTokenVerificationTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val store = WalletHistoryArtifactStore(mapper)
    private val chain = WalletTokenChain(mapper)
    private val wallet = WalletAddress.of(WALLET)

    @Test
    fun `step two lists the mint before the buy, finds its creation oldest first, scans both accumulators from their heads and fetches each entry once`() {
        verifyingChain()

        val result = capture()

        assertEquals(listOf(
            Call("getAccountInfo", MINT, null),
            Call("getSignaturesForAddress", TOKEN_ACCOUNT, null),
            Call("getTransaction", LATER, null),
            Call("getTransaction", BUY, null),
            Call("getTransaction", MIDDLE, null),
            Call("getSignaturesForAddress", MINT, BUY),
            Call("getTransaction", PRE_TRANSFER, null),
            Call("getTransaction", CREATION, null),
            Call("getSignaturesForAddress", PUMP_ACCUMULATOR, null),
            Call("getSignaturesForAddress", PUMPSWAP_ACCUMULATOR, null),
            Call("getTransaction", IRRELEVANT, null),
        ), chain.work())
        val manifest = result.manifest
        val verification = checkNotNull(manifest.verification)
        assertTrue(manifest.complete)
        assertNull(result.stopReason)
        assertEquals(VerifiedFirstBuy(BUY, BUY_SLOT, 1), verification.firstBuy)
        assertEquals(CREATION, verification.creation.signature)
        assertEquals(CREATION_SLOT, verification.creation.slot)
        assertEquals(CREATION_TIME, verification.creation.blockTime)
        assertEquals(3, verification.fetches)
        assertEquals(AccumulatorScan(PUMP_ACCUMULATOR, WalletHistoryScanCursor(BELOW, CREATION_SLOT - 1),
            pages = 1, entries = 7, skippedFailed = 1, oldestSlot = CREATION_SLOT - 1, listedFirstBuy = true, complete = true), verification.pumpAccumulator)
        assertEquals(AccumulatorScan(PUMPSWAP_ACCUMULATOR, null,
            pages = 1, entries = 0, skippedFailed = 0, oldestSlot = null, listedFirstBuy = false, complete = true), verification.pumpSwapAccumulator)
        assertEquals(VerificationResult(VerificationResultKind.PROVEN, BUY, null, null, emptyList()), verification.result)
        assertEquals(manifest, recorded().inspect(directory).manifest)
    }

    @Test
    fun `in range entries reuse the token account and creation artifacts instead of fetching them again`() {
        verifyingChain()

        val records = checkNotNull(capture().manifest.verification).records.associateBy { it.listing to it.signature }

        assertEquals("transactions/$MIDDLE.json", records.getValue(VerificationListing.PUMP_ACCUMULATOR to MIDDLE).artifact)
        assertEquals("verification/$CREATION.json", records.getValue(VerificationListing.PUMP_ACCUMULATOR to CREATION).artifact)
        assertEquals("verification/$CREATION.json", records.getValue(VerificationListing.MINT to CREATION).artifact)
        assertEquals("verification/$IRRELEVANT.json", records.getValue(VerificationListing.PUMP_ACCUMULATOR to IRRELEVANT).artifact)
        assertTrue(Files.isRegularFile(directory.resolve("verification").resolve("$PRE_TRANSFER.json")))
        assertEquals(1, chain.work().count { it.method == "getTransaction" && it.subject == CREATION })
    }

    @Test
    fun `decoding proves the actual first buy and verification records never become wallet records`() {
        verifyingChain()
        capture()

        val decoded = recorded().decode(directory, wallet)

        assertEquals(ActualFirstBuy.Proven(
            signature = TransactionSignature.of(BUY), slot = Slot.of(BUY_SLOT), instructionIndex = 1,
            creationSignature = TransactionSignature.of(CREATION), creationSlot = Slot.of(CREATION_SLOT),
            creationTime = Instant.ofEpochSecond(CREATION_TIME),
            scannedTo = mapOf(VerificationListing.MINT to Slot.of(PRE_TRANSFER_SLOT), VerificationListing.PUMP_ACCUMULATOR to Slot.of(CREATION_SLOT - 1)),
        ), decoded.targetToken?.actualFirstBuy)
        assertEquals(TokenAccountFirstBuy.Found(TransactionSignature.of(BUY), Slot.of(BUY_SLOT), 1), decoded.targetToken?.tokenAccountFirstBuy)
        assertEquals(listOf(LATER, BUY, MIDDLE), decoded.records.map { it.signature.value })
        assertEquals(listOf(BUY), decoded.transactions.map { it.signature.value })
    }

    @Test
    fun `an earlier decoded wallet buy into another account is reported, nothing later is fetched and the capture is still complete`() {
        verifyingChain()
        chain.add(chain.buy(EARLIER_BUY, MIDDLE_SLOT - 5), chain.transaction(AFTER_EARLIER_BUY, MIDDLE_SLOT - 2, listOf(WALLET to true, PUMP_ACCUMULATOR to false)))
        chain.listing(PUMP_ACCUMULATOR, *pumpListing().toMutableList()
            .apply { add(3, Entry(AFTER_EARLIER_BUY, MIDDLE_SLOT - 2)); add(4, Entry(EARLIER_BUY, MIDDLE_SLOT - 5)) }.toTypedArray())

        val result = capture()

        assertTrue(result.manifest.complete)
        assertEquals(listOf(IRRELEVANT, EARLIER_BUY), chain.work().filter { it.method == "getTransaction" }.map { it.subject }.takeLast(2))
        assertEquals(0, chain.work().count { it.subject == AFTER_EARLIER_BUY })
        assertEquals(VerificationOutcome.PENDING, checkNotNull(result.manifest.verification).records.single { it.signature == AFTER_EARLIER_BUY }.outcome)
        assertEquals(ActualFirstBuy.OutsideTokenAccount(TransactionSignature.of(EARLIER_BUY), Slot.of(MIDDLE_SLOT - 5)),
            recorded().decode(directory, wallet).targetToken?.actualFirstBuy)
    }

    @Test
    fun `a pump accumulator listing that never lists the buy leaves it unproven as not listed and fetches no entry`() {
        verifyingChain()
        chain.listing(PUMP_ACCUMULATOR)

        val result = capture()

        assertTrue(result.manifest.complete)
        val verification = checkNotNull(result.manifest.verification)
        assertEquals(VerificationResult(VerificationResultKind.UNPROVEN, null, FirstBuyGap.FIRST_BUY_NOT_LISTED, null, listOf(BUY)), verification.result)
        assertEquals(false, verification.pumpAccumulator.listedFirstBuy)
        assertEquals(0, chain.work().count { it.subject == PUMPSWAP_ACCUMULATOR || it.subject == IRRELEVANT })
        assertEquals(ActualFirstBuy.Unproven(FirstBuyGap.FIRST_BUY_NOT_LISTED, listOf(TransactionSignature.of(BUY)), null),
            recorded().decode(directory, wallet).targetToken?.actualFirstBuy)
    }

    @Test
    fun `a buy that creates its own mint is its creation boundary and the mint is never listed`() {
        verifyingChain()
        val creatingBuy = chain.buy()
        (creatingBuy.path("transaction").path("message").path("instructions") as ArrayNode).add(chain.create())
        chain.add(creatingBuy)
        chain.listing(PUMP_ACCUMULATOR, Entry(ABOVE_BUY, BUY_SLOT + 50), Entry(BUY, BUY_SLOT), Entry(SAME_SLOT, BUY_SLOT), Entry(BELOW, BUY_SLOT - 1))
        chain.add(chain.transaction(SAME_SLOT, BUY_SLOT, listOf(WALLET to true, PUMP_ACCUMULATOR to false)))

        val result = capture()

        assertTrue(result.manifest.complete)
        assertEquals(0, chain.work().count { it.subject == MINT && it.method == "getSignaturesForAddress" })
        val verification = checkNotNull(result.manifest.verification)
        assertEquals(BUY, verification.creation.signature)
        assertEquals(BUY_SLOT, verification.creation.slot)
        assertEquals(0, verification.creation.pages)
        assertEquals(listOf(SAME_SLOT), verification.records.map { it.signature })
        assertEquals(ActualFirstBuy.Proven(
            signature = TransactionSignature.of(BUY), slot = Slot.of(BUY_SLOT), instructionIndex = 1,
            creationSignature = TransactionSignature.of(BUY), creationSlot = Slot.of(BUY_SLOT),
            creationTime = Instant.ofEpochSecond(creatingBuy.path("blockTime").asLong()),
            scannedTo = mapOf(VerificationListing.PUMP_ACCUMULATOR to Slot.of(BUY_SLOT - 1)),
        ), recorded().decode(directory, wallet).targetToken?.actualFirstBuy)
        store.save(directory, result.manifest.copy(verification = verification.copy(creation = verification.creation.copy(pages = 1, historyEnd = true))))
        assertEquals("VERIFICATION_LISTING", assertFailsWith<CaptureValidationException> { recorded().inspect(directory) }.category)
    }

    @Test
    fun `accumulator listings that fill fifty pages above the buy are verification bound results naming their own cap`() {
        listOf(
            VerificationListing.PUMP_ACCUMULATOR to VerificationLimit.PUMP_ACCUMULATOR_PAGES,
            VerificationListing.PUMPSWAP_ACCUMULATOR to VerificationLimit.PUMPSWAP_ACCUMULATOR_PAGES,
        ).forEach { (listing, limit) ->
            verifyingChain()
            val address = if (listing == VerificationListing.PUMP_ACCUMULATOR) PUMP_ACCUMULATOR else PUMPSWAP_ACCUMULATOR
            val busy = (0 until 50 * WalletTokenChain.PAGE_SIZE).map { Entry(WalletTokenChain.signature(it), LATER_SLOT + 60_000 - it) }
            chain.listing(address, *(busy + if (listing == VerificationListing.PUMP_ACCUMULATOR) pumpListing() else emptyList()).toTypedArray())
            chain.calls.clear()
            val target = directory.resolve(listing.name)

            val result = capture(target)

            assertTrue(result.manifest.complete, "$listing")
            assertNull(result.stopReason)
            assertEquals(50, chain.work().count { it.subject == address })
            val decoded = recorded().decode(target, wallet).targetToken?.actualFirstBuy as ActualFirstBuy.Unproven
            assertEquals(FirstBuyGap.VERIFICATION_BOUND, decoded.reason)
            assertEquals(limit, decoded.bound?.limit)
            assertEquals(50, decoded.bound?.pages?.get(listing))
            assertEquals(50_000, decoded.bound?.entries?.get(listing))
        }
    }

    @Test
    fun `the fetch cap ends verification at five hundred transactions and never requests a five hundred and first`() {
        verifyingChain()
        val unavailable = (0 until 600).map { Entry(WalletTokenChain.signature(it), IRRELEVANT_SLOT + 1 + it / 100) }.reversed()
        chain.listing(PUMP_ACCUMULATOR, *pumpListing().toMutableList().apply { addAll(4, unavailable) }.toTypedArray())

        val result = capture()

        assertTrue(result.manifest.complete)
        assertNull(result.stopReason)
        val verification = checkNotNull(result.manifest.verification)
        assertEquals(500, verification.fetches)
        val stepTwoFetches = chain.work().dropWhile { !(it.method == "getSignaturesForAddress" && it.subject == MINT) }.count { it.method == "getTransaction" }
        assertEquals(500, stepTwoFetches)
        assertEquals(VerificationResult(VerificationResultKind.UNPROVEN, null, FirstBuyGap.VERIFICATION_BOUND, VerificationLimit.FETCHES, emptyList()),
            verification.result)
    }

    @Test
    fun `an earlier undecoded acquisition through the pumpswap accumulator leaves the first buy unproven`() {
        verifyingChain()
        chain.add(chain.transaction(MULTI_HOP, MIDDLE_SLOT - 7, listOf(WALLET to true, PUMPSWAP_ACCUMULATOR to false, MINT to false, OTHER_TOKEN_ACCOUNT to false),
            postTokenBalances = listOf(chain.tokenBalance(3, WALLET, 1_000_000))))
        chain.listing(PUMPSWAP_ACCUMULATOR, Entry(MULTI_HOP, MIDDLE_SLOT - 7), Entry(BELOW, CREATION_SLOT - 1))

        capture()

        assertEquals(ActualFirstBuy.Unproven(FirstBuyGap.EARLIER_UNDECODED_ACQUISITION, listOf(TransactionSignature.of(MULTI_HOP)), null),
            recorded().decode(directory, wallet).targetToken?.actualFirstBuy)
    }

    @Test
    fun `an in range entry the provider no longer has leaves the first buy unproven as unknown`() {
        verifyingChain()
        chain.transactions.remove(IRRELEVANT)

        capture()

        assertEquals(ActualFirstBuy.Unproven(FirstBuyGap.EARLIER_UNKNOWN_RECORD, listOf(TransactionSignature.of(IRRELEVANT)), null),
            recorded().decode(directory, wallet).targetToken?.actualFirstBuy)
    }

    @Test
    fun `an accumulator entry without the accumulator keeps verification incomplete and is fetched again on the next run`() {
        verifyingChain()
        chain.add(chain.transaction(IRRELEVANT, IRRELEVANT_SLOT, listOf(WALLET to true)))

        val first = capture()
        assertEquals(false, first.manifest.complete)
        assertEquals(VerificationOutcome.INVALID_TRANSACTION,
            checkNotNull(first.manifest.verification).records.single { it.signature == IRRELEVANT }.outcome)
        chain.calls.clear()

        chain.add(chain.transaction(IRRELEVANT, IRRELEVANT_SLOT, listOf(WALLET to true, PUMP_ACCUMULATOR to false)))
        val second = capture()

        assertEquals(listOf(Call("getTransaction", IRRELEVANT, null)), chain.work())
        assertTrue(second.manifest.complete)
    }

    @Test
    fun `a mint without a creation among its oldest entries is not identified and the capture is complete`() {
        verifyingChain()
        chain.listing(MINT, Entry(PRE_TRANSFER, PRE_TRANSFER_SLOT))

        val result = capture()

        assertTrue(result.manifest.complete)
        assertEquals(VerificationResult(VerificationResultKind.UNPROVEN, null, FirstBuyGap.CREATION_NOT_IDENTIFIED, null, emptyList()),
            result.manifest.verification?.result)
        assertEquals(0, chain.work().count { it.subject in setOf(PUMP_ACCUMULATOR, PUMPSWAP_ACCUMULATOR) })
    }

    @Test
    fun `a creation before the accumulator coverage date is a pre accumulator mint`() {
        verifyingChain()
        chain.add(creation(blockTime = 1_754_000_000))

        val result = capture()

        assertEquals(VerificationResult(VerificationResultKind.UNPROVEN, null, FirstBuyGap.PRE_ACCUMULATOR_MINT, null, listOf(CREATION)),
            result.manifest.verification?.result)
    }

    @Test
    fun `a mint listing that fills fifty pages is a verification bound result, not an interruption`() {
        verifyingChain()
        chain.listing(MINT, *(0 until 50 * WalletTokenChain.PAGE_SIZE).map { Entry(WalletTokenChain.signature(it), BUY_SLOT - 1 - it / 10) }.toTypedArray())

        val result = capture()

        assertTrue(result.manifest.complete)
        assertNull(result.stopReason)
        val decoded = recorded().decode(directory, wallet).targetToken?.actualFirstBuy as ActualFirstBuy.Unproven
        assertEquals(FirstBuyGap.VERIFICATION_BOUND, decoded.reason)
        val bound = checkNotNull(decoded.bound)
        assertEquals(VerificationLimit.MINT_PAGES, bound.limit)
        assertEquals(50, bound.pages[VerificationListing.MINT])
        assertEquals(50_000, bound.entries[VerificationListing.MINT])
        assertEquals(0, bound.fetches)
        assertEquals(0, bound.pages[VerificationListing.PUMP_ACCUMULATOR])
    }

    @Test
    fun `a request budget interrupts step two and every resume continues from its saved cursors without repeating a request`() {
        verifyingChain()
        capture(directory.resolve("uninterrupted"))
        val uninterrupted = chain.work().toList()
        chain.calls.clear()

        var runs = 0
        do {
            val result = capture(requestBudget = 2)
            runs++
            if (!result.manifest.complete) assertEquals("REQUESTS", result.stopReason)
        } while (!result.manifest.complete && runs < 50)

        assertEquals(uninterrupted, chain.work())
        assertEquals(uninterrupted.size, runs)
        assertEquals(VerificationResultKind.PROVEN, store.inspect(directory).manifest.verification?.result?.kind)
    }

    @Test
    fun `inspection refuses a tampered verification artifact, verdict, accumulator address, creation or target`() {
        verifyingChain()
        val valid = capture().manifest
        val verification = checkNotNull(valid.verification)
        mapOf(
            "another verdict" to (valid.copy(verification = verification.copy(result = verification.result?.copy(kind = VerificationResultKind.UNPROVEN,
                signature = null, reason = FirstBuyGap.EARLIER_UNKNOWN_RECORD))) to "VERIFICATION_RESULT"),
            "no verdict" to (valid.copy(complete = false, verification = verification.copy(result = null)) to "VERIFICATION_RESULT"),
            "another accumulator" to (valid.copy(verification = verification.copy(
                pumpSwapAccumulator = verification.pumpSwapAccumulator.copy(address = PUMP_ACCUMULATOR))) to "VERIFICATION_ADDRESS"),
            "a buy the pump listing never listed" to (valid.copy(verification = verification.copy(
                pumpAccumulator = verification.pumpAccumulator.copy(listedFirstBuy = false))) to "VERIFICATION_RESULT"),
            "a listed buy below the scan" to (valid.copy(verification = verification.copy(
                pumpSwapAccumulator = verification.pumpSwapAccumulator.copy(listedFirstBuy = true))) to "VERIFICATION_LISTING"),
            "another creation" to (valid.copy(verification = verification.copy(
                creation = verification.creation.copy(blockTime = CREATION_TIME + 1))) to "VERIFICATION_RESULT"),
            "an incomplete scan" to (valid.copy(verification = verification.copy(
                pumpSwapAccumulator = verification.pumpSwapAccumulator.copy(complete = false))) to "VERIFICATION_LISTING"),
            "another instruction" to (valid.copy(verification = verification.copy(
                firstBuy = verification.firstBuy?.copy(instructionIndex = 7))) to "VERIFICATION_TARGET"),
        ).forEach { (name, forged) ->
            store.save(directory, forged.first)
            val failure = assertFailsWith<CaptureValidationException>(name) { recorded().inspect(directory) }
            assertEquals(forged.second, failure.category, name)
        }
        store.save(directory, valid)
        val artifact = directory.resolve("verification").resolve("$IRRELEVANT.json")
        Files.writeString(artifact, Files.readString(artifact).replace(WALLET, CREATOR))
        val failure = assertFailsWith<CaptureValidationException> { store.inspect(directory) }
        assertEquals("ARTIFACT_HASH", failure.category)
    }

    @Test
    fun `a token account capture without a token account first buy writes an empty verification and asks nothing more`() {
        verifyingChain()
        chain.listing(TOKEN_ACCOUNT, Entry(LATER, LATER_SLOT), Entry(MIDDLE, MIDDLE_SLOT))

        val result = capture()

        assertTrue(result.manifest.complete)
        assertNull(checkNotNull(result.manifest.verification).firstBuy)
        assertEquals(listOf("getAccountInfo", "getSignaturesForAddress", "getTransaction", "getTransaction"), chain.work().map { it.method })
        assertEquals(ActualFirstBuy.NotVerified, recorded().decode(directory, wallet).targetToken?.actualFirstBuy)
    }

    private fun capture(target: Path = directory, requestBudget: Int = 1000): WalletHistoryCaptureResult =
        WalletHistoryCaptureService(chain.rpc(), mapper, Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC)).capture(
            WalletHistoryCaptureRequest(wallet, target, limit = 100, scanPages = 5, requestBudget = requestBudget,
                scope = WalletHistoryScope.WALLET_TOKEN_ACCOUNT, targetMint = MintAddress.of(MINT)))

    private fun recorded() = WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper)

    /**
     * B through the token account, a later token-account entry, an in-range token-account
     * entry that also passes the Pump accumulator, a transfer to the mint before its creation, the creation, an irrelevant
     * Pump accumulator entry and a failed one, and an empty PumpSwap accumulator listing.
     */
    private fun verifyingChain() {
        chain.listing(TOKEN_ACCOUNT, Entry(LATER, LATER_SLOT), Entry(BUY, BUY_SLOT), Entry(MIDDLE, MIDDLE_SLOT))
        chain.listing(MINT, Entry(OTHER_TRADE, CREATION_SLOT + 20), Entry(CREATION, CREATION_SLOT), Entry(PRE_TRANSFER, PRE_TRANSFER_SLOT))
        chain.listing(PUMP_ACCUMULATOR, *pumpListing().toTypedArray())
        chain.add(
            chain.buy(),
            chain.transaction(LATER, LATER_SLOT, listOf(OTHER to true, TOKEN_ACCOUNT to false, WALLET to false)),
            chain.transaction(MIDDLE, MIDDLE_SLOT, listOf(OTHER to true, TOKEN_ACCOUNT to false, WALLET to false, PUMP_ACCUMULATOR to false)),
            chain.transaction(PRE_TRANSFER, PRE_TRANSFER_SLOT, listOf(OTHER to true, MINT to false, SYSTEM_PROGRAM to false)),
            creation(),
            chain.transaction(IRRELEVANT, IRRELEVANT_SLOT, listOf(WALLET to true, PUMP_ACCUMULATOR to false)),
        )
    }

    private fun pumpListing(): List<Entry> = listOf(
        Entry(ABOVE_BUY, BUY_SLOT + 50), Entry(BUY, BUY_SLOT), Entry(MIDDLE, MIDDLE_SLOT), Entry(FAILED, MIDDLE_SLOT - 10, failed = true),
        Entry(IRRELEVANT, IRRELEVANT_SLOT), Entry(CREATION, CREATION_SLOT), Entry(BELOW, CREATION_SLOT - 1), Entry(OLDER, CREATION_SLOT - 5),
    )

    private fun creation(blockTime: Long = CREATION_TIME) = chain.transaction(CREATION, CREATION_SLOT,
        listOf(CREATOR to true, MINT to true, WALLET to false, PUMP_ACCUMULATOR to false, PUMP_FUN to false), listOf(chain.create()), blockTime)

    private companion object {
        const val CREATION_SLOT = BUY_SLOT - 100
        const val PRE_TRANSFER_SLOT = CREATION_SLOT - 10
        const val MIDDLE_SLOT = CREATION_SLOT + 50
        const val IRRELEVANT_SLOT = CREATION_SLOT + 30
        const val LATER_SLOT = BUY_SLOT + 100
        const val CREATION_TIME = 1_791_048_000L
        val LATER = WalletTokenChain.signature(100_001)
        val MIDDLE = WalletTokenChain.signature(100_002)
        val CREATION = WalletTokenChain.signature(100_003)
        val PRE_TRANSFER = WalletTokenChain.signature(100_004)
        val OTHER_TRADE = WalletTokenChain.signature(100_005)
        val IRRELEVANT = WalletTokenChain.signature(100_006)
        val ABOVE_BUY = WalletTokenChain.signature(100_007)
        val FAILED = WalletTokenChain.signature(100_008)
        val BELOW = WalletTokenChain.signature(100_009)
        val OLDER = WalletTokenChain.signature(100_010)
        val EARLIER_BUY = WalletTokenChain.signature(100_011)
        val MULTI_HOP = WalletTokenChain.signature(100_012)
        val AFTER_EARLIER_BUY = WalletTokenChain.signature(100_013)
        val SAME_SLOT = WalletTokenChain.signature(100_014)
    }
}
