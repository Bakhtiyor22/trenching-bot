package com.trenching.bot.marketdata

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.chaincapture.SolanaReadRpc
import com.trenching.bot.common.Base58
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.marketdata.application.MarketHistoryCaptureService
import com.trenching.bot.marketdata.domain.TradeSide
import com.trenching.bot.marketdata.infrastructure.capture.MarketHistoryArtifactStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/** A capture whose window starts [MarketCaptureWindowParameters.DEFAULT_LEAD_SLOTS] before the wallet's first buy. */
class MarketHistoryLeadCaptureTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val fixture = javaClass.getResourceAsStream("/walletactivity/decoder/pumpfun-buy.json").use { mapper.readTree(requireNotNull(it)) } as ObjectNode
    private val window = MarketCaptureWindow.forFirstBuy(MintAddress.of(MINT), Slot.of(BUY_SLOT), TransactionSignature.of(BUY), LEAD)

    @Test
    fun `lists through the buy without an until and records the lead, the whole buy slot and the buy's own trade`() {
        val chain = Chain()

        val manifest = capture(chain).manifest

        assertTrue(chain.listings.isNotEmpty())
        assertTrue(chain.listings.none { "until" in it }, "a lead window lists past the buy")
        val selected = manifest.windows.single().selection.map { it.signature }.toSet()
        assertEquals((LEAD_TRADES + SAME_SLOT_TRADES + BUY + LATER_TRADES).toSet(), selected)
        assertTrue(BEFORE_LEAD.none { it in chain.fetched })
        assertEquals(selected, chain.fetched.toSet())
        assertEquals(4, manifest.schemaVersion)
        assertEquals(LEAD, manifest.windowParameters)
        assertTrue(Files.readString(directory.resolve("market-manifest.json")).contains("\"leadSlots\":50"))
        assertTrue(manifest.complete)

        val observations = recorded().decode(directory).observations
        assertEquals(selected, observations.map { it.sourceEventId.substringBefore(':') }.toSet())
        val buy = observations.single { it.sourceEventId.startsWith("$BUY:") }
        assertEquals(Slot.of(BUY_SLOT), buy.slot)
        assertEquals(WALLET, buy.trade?.trader)
        assertEquals(TradeSide.BUY, buy.trade?.side)
        assertEquals(BUY_SLOT - 50, observations.minOf { it.slot.value })
    }

    @Test
    fun `rerunning a lead capture lists and fetches nothing and changes nothing`() {
        val first = capture(Chain()).manifest
        val rerun = Chain()

        val again = capture(rerun).manifest

        assertEquals(emptyList(), rerun.fetched)
        assertEquals(emptyList(), rerun.listings)
        assertEquals(first.copy(requestsUsed = 0), again.copy(requestsUsed = 0))
    }

    @Test
    fun `a legacy window still lists until its buy`() {
        val legacy = MarketCaptureWindow.forFirstBuy(MintAddress.of(MINT), Slot.of(BUY_SLOT), TransactionSignature.of(BUY), LEGACY)
        val chain = Chain()

        val manifest = service(chain).capture(MarketHistoryCaptureRequest(directory, listOf(legacy), LEGACY, 20, 1000, 1000, 104_857_600)).manifest

        assertEquals(listOf(BUY), chain.listings.map { it["until"] }.distinct())
        assertEquals(2, manifest.schemaVersion)
        assertTrue(BUY !in manifest.windows.single().selection.map { it.signature })
    }

    @Test
    fun `the cutoff is first asked once the captured prefix reaches the buy and a decision at the buy is accepted`() {
        val cutoff = DecideAtSlot(BUY_SLOT)

        val manifest = capture(Chain(), cutoff).manifest

        assertTrue(cutoff.evaluations.isNotEmpty())
        assertTrue(cutoff.evaluations.all { it.observedThrough >= BUY_SLOT })
        assertEquals(BUY_SLOT, manifest.windows.single().cutoff?.throughSlot)
        assertEquals(4, manifest.schemaVersion)
        val later = manifest.records.filter { it.slot > BUY_SLOT }
        assertTrue(later.isNotEmpty() && later.all { it.outcome == MarketTransactionOutcome.NOT_NEEDED })
        assertEquals(manifest, recorded().inspect(directory).manifest)
    }

    @Test
    fun `a decision inside the lead is refused`() {
        val insideLead = MarketCaptureCutoff { _, _, observedThrough ->
            if (observedThrough.value >= BUY_SLOT) CutoffDecision.Decided(Slot.of(BUY_SLOT - 1), "TAKE_PROFIT") else CutoffDecision.Undecided
        }

        val failure = assertFailsWith<CaptureValidationException> { capture(Chain(), insideLead) }
        assertTrue(failure.message.orEmpty().endsWith("category=CUTOFF_DECISION"), failure.message)
    }

    @Test
    fun `a token created inside the lead completes on its short page and its window still starts fifty slots before the buy`() {
        val chain = Chain(CREATED_IN_LEAD)

        val manifest = capture(chain).manifest

        val record = manifest.windows.single()
        assertEquals(MarketWindowCoverage.COMPLETE, record.coverage)
        assertEquals(BUY_SLOT - 50, record.fromSlot)
        assertEquals(CREATED_IN_LEAD.keys, record.selection.map { it.signature }.toSet())
        assertTrue(manifest.complete)
        val decoded = recorded().decode(directory).windows.single()
        assertEquals(Slot.of(BUY_SLOT - 50), decoded.window.fromSlot)
        assertTrue(decoded.yieldsObservations)
        assertEquals(BUY_SLOT - 20, recorded().decode(directory).observations.minOf { it.slot.value })
    }

    @Test
    fun `a busy lead pushes the window over its cap, which is reported and never trimmed`() {
        val afterBuy = LATER_TRADES.size
        val legacy = MarketCaptureWindow.forFirstBuy(MintAddress.of(MINT), Slot.of(BUY_SLOT), TransactionSignature.of(BUY), LEGACY)
        val legacyManifest = service(Chain()).capture(MarketHistoryCaptureRequest(directory.resolve("legacy"), listOf(legacy), LEGACY, 20,
            afterBuy, 1000, 104_857_600)).manifest
        assertEquals(MarketWindowCoverage.COMPLETE, legacyManifest.windows.single().coverage)
        val chain = Chain()

        val manifest = capture(chain, maxTransactions = afterBuy).manifest

        val record = manifest.windows.single()
        assertEquals(MarketWindowCoverage.OVER_CAP, record.coverage)
        assertEquals(emptyList(), record.selection)
        assertTrue(record.selected > afterBuy)
        assertEquals(emptyList(), chain.fetched)
        assertEquals(emptyList(), manifest.records)
        assertFalse(manifest.complete)
    }

    private fun capture(chain: Chain, cutoff: MarketCaptureCutoff? = null, maxTransactions: Int = 1000): MarketHistoryCaptureResult {
        val request = MarketHistoryCaptureRequest(directory, listOf(window), LEAD, 20, maxTransactions, 1000, 104_857_600)
        return service(chain).capture(if (cutoff == null) request else request.copy(cutoffMode = MarketCutoffMode.DECIDED, cutoff = cutoff, cutoffIdentity = IDENTITY))
    }

    private fun service(chain: Chain): MarketHistoryCapture = MarketHistoryCaptureService(chain.rpc(), MarketHistoryArtifactStore(mapper), CLOCK)

    private fun recorded(): RecordedMarketHistory = MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper)

    /**
     * The mint's [history] by signature, newest first in one short page: by default trades before, inside and after the lead,
     * and the buy's slot.
     */
    private inner class Chain(private val slotOf: Map<String, Long> = HISTORY) {
        val listings = mutableListOf<Map<*, *>>()
        val fetched = mutableListOf<String>()

        fun rpc() = SolanaReadRpc { method, params, budget ->
            budget.request()
            when (method) {
                "getGenesisHash" -> mapper.readTree("\"$GENESIS\"")
                "getSlot" -> mapper.readTree(Long.MAX_VALUE.toString())
                "getSignaturesForAddress" -> {
                    val options = params[1] as Map<*, *>
                    listings += options
                    val history = slotOf.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                    val listed = options["until"]?.let { until -> history.takeWhile { it.key != until } } ?: history
                    mapper.readTree(listed.joinToString(",", "[", "]") {
                        """{"signature":"${it.key}","slot":${it.value},"err":null,"memo":null,"blockTime":1791049629}"""
                    })
                }
                "getTransaction" -> {
                    val signature = params[0] as String
                    synchronized(this) { fetched += signature }
                    trade(signature, slotOf.getValue(signature))
                }
                else -> error("Unexpected method $method")
            }
        }
    }

    private fun trade(signature: String, slot: Long): JsonNode = fixture.deepCopy().apply {
        (path("transaction").path("signatures") as ArrayNode).set(0, mapper.nodeFactory.stringNode(signature))
        put("slot", slot)
    }

    private companion object {
        const val GENESIS = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"
        const val MINT = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
        /** The fixture trade's signer, so every trade here is the tracked wallet's. */
        const val WALLET = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
        const val BUY_SLOT = 2_000L
        val LEAD = MarketCaptureWindowParameters(horizonSlots = 4500, maxAgeSlots = 2, leadSlots = MarketCaptureWindowParameters.DEFAULT_LEAD_SLOTS)
        val LEGACY = LEAD.copy(leadSlots = MarketCaptureWindowParameters.LEGACY_LEAD_SLOTS)
        val IDENTITY =CutoffIdentity("engine-v1", "a".repeat(64))
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC)
        val BUY = signature("buy")
        val BEFORE_LEAD_SLOTS = listOf(BUY_SLOT - 52, BUY_SLOT - 51)
        val BEFORE_LEAD = BEFORE_LEAD_SLOTS.map { signature("before$it") }
        val LEAD_SLOTS = listOf(BUY_SLOT - 50, BUY_SLOT - 20, BUY_SLOT - 1)
        val LEAD_TRADES = LEAD_SLOTS.map { signature("lead$it") }
        val SAME_SLOT_TRADES = listOf(signature("sameSlotA"), signature("sameSlotB"))
        val LATER_SLOTS = listOf(BUY_SLOT + 1, BUY_SLOT + 300)
        val LATER_TRADES = LATER_SLOTS.map { signature("later$it") }
        val HISTORY = (BEFORE_LEAD.zip(BEFORE_LEAD_SLOTS) + LEAD_TRADES.zip(LEAD_SLOTS) + SAME_SLOT_TRADES.map { it to BUY_SLOT } +
            (BUY to BUY_SLOT) + LATER_TRADES.zip(LATER_SLOTS)).toMap()

        /** A token created 20 slots before the buy: nothing exists earlier, so its listing ends on a short page inside the lead. */
        val CREATED_IN_LEAD = HISTORY.filterValues { it >= BUY_SLOT - 20 }

        fun signature(seed: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return Base58.encode(digest.digest("a$seed".toByteArray()) + digest.digest("b$seed".toByteArray()))
        }
    }
}
