package com.trenching.bot.replay

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.chaincapture.SolanaReadRpc
import com.trenching.bot.common.Base58
import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.MarketCaptureWindowParameters
import com.trenching.bot.marketdata.MarketCutoffMode
import com.trenching.bot.marketdata.MarketHistoryCaptureConfiguration
import com.trenching.bot.marketdata.MarketHistoryCaptureResult
import com.trenching.bot.marketdata.MarketTransactionOutcome
import com.trenching.bot.marketdata.MarketWindowCutoff
import com.trenching.bot.marketdata.RateLookup
import com.trenching.bot.marketdata.RecordedMarketHistory
import com.trenching.bot.marketdata.RecordedReferenceRates
import com.trenching.bot.marketdata.ReferenceRate
import com.trenching.bot.marketdata.ReferenceRateCaptureResult
import com.trenching.bot.marketdata.SolUsdtRateSource
import com.trenching.bot.marketdata.application.MarketHistoryCaptureService
import com.trenching.bot.marketdata.application.WindowCutoffEvaluator
import com.trenching.bot.marketdata.infrastructure.capture.MarketHistoryArtifactStore
import com.trenching.bot.portfolio.domain.CloseReason
import com.trenching.bot.portfolio.domain.ExitPriceAssumption
import com.trenching.bot.portfolio.domain.PositionClose
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import com.trenching.bot.replay.RecordedWalletFixtures.MINT
import com.trenching.bot.replay.RecordedWalletFixtures.SIGNATURE
import com.trenching.bot.replay.RecordedWalletFixtures.SLOT
import com.trenching.bot.replay.application.DipOutcomeCutoff
import com.trenching.bot.replay.application.RecordedReplayExportService
import com.trenching.bot.replay.application.ReplayMarketCaptureService
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.application.TargetTokenQualificationService
import com.trenching.bot.replay.domain.CaseClassification
import com.trenching.bot.replay.domain.DatasetCoverage
import com.trenching.bot.replay.domain.ExitVerdict
import com.trenching.bot.replay.domain.RecordedReplayExportRequest
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.ReplayMarketCaptureRequest
import com.trenching.bot.replay.domain.TargetTokenVerdict
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.walletactivity.WalletHistoryCaptureConfiguration
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * A decided capture of Decu's real buy against a synthetic tape of 340 same-mint trades, one per slot and one second apart
 * from the buy's block time t0: no dip until S+10, a fill at S+11, a take-profit crossing at S+39 and its sale at S+40, then
 * 300 more trades the outcome does not depend on. Under `SimulationConfig.agreed()` the window is still recorded until the
 * first trade four minutes after t0, at S+240.
 */
class OutcomeBoundedCaptureTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val fixture = javaClass.getResourceAsStream("/walletactivity/decoder/pumpfun-buy.json").use { mapper.readTree(requireNotNull(it)) } as ObjectNode

    @Test
    fun `a decided capture stops four minutes after the buy and exports a v6 dataset that qualifies like the full window`() {
        RecordedWalletFixtures.save(mapper, wallet())
        val decidedChain = Chain(::takeProfitTape)
        val rates = FixedRates()

        val decided = capture(decidedChain, "decided", MarketCutoffMode.DECIDED, rates).manifest
        val full = capture(Chain(::takeProfitTape), "full", MarketCutoffMode.FULL).manifest

        assertEquals((SLOT + 1..SLOT + 300).toList(), decidedChain.fetchedSlots)
        assertEquals(MarketWindowCutoff(SLOT + 240, "TAKE_PROFIT", DipOutcomeCutoff.IDENTITY.engineVersion,
            DipOutcomeCutoff.IDENTITY.configurationHash), decided.windows.single().cutoff)
        val later = decided.records.filter { it.slot > SLOT + 240 }
        assertEquals(100, later.size)
        assertTrue(later.all { it.outcome == MarketTransactionOutcome.NOT_NEEDED && it.artifact == null })
        assertEquals(3, rates.requests)
        assertEquals(listOf(SLOT + 100, SLOT + 200, SLOT + 240), decidedChain.blockTimeReads)
        assertEquals(blockTime(SLOT + 240), decided.windows.single().throughBlockTime)
        assertNull(full.windows.single().cutoff)
        assertEquals(TRADES.toInt(), full.records.count { it.outcome == MarketTransactionOutcome.CAPTURED })

        val decidedDataset = export("decided")
        val fullDataset = export("full")
        assertEquals(ReplayDataset.SCHEMA_V6, decidedDataset.schemaVersion)
        assertEquals(Slot.of(SLOT + 240), decidedDataset.coverage.single().throughSlot)
        assertEquals(blockTime(SLOT + 240), decidedDataset.coverage.single().throughTime)
        assertEquals(Slot.of(SLOT + 4500), fullDataset.coverage.single().throughSlot)
        assertEquals(240, decidedDataset.observations.size)
        assertEquals(RATES.take(5), decidedDataset.referenceRates)

        val decidedVerdict = qualify("decided")
        val fullVerdict = qualify("full")
        assertEquals(CaseClassification.QUALIFYING_DIP, decidedVerdict.classification)
        val exit = decidedVerdict.exit as ExitVerdict.Closed
        assertEquals(CloseReason.TAKE_PROFIT, exit.reason)
        assertEquals(Slot.of(SLOT + 40), exit.slot)
        assertEquals(listOf(fullVerdict.classification, fullVerdict.dip, fullVerdict.entry, fullVerdict.exit),
            listOf(decidedVerdict.classification, decidedVerdict.dip, decidedVerdict.entry, decidedVerdict.exit))

        val fartherTakeProfit = SimulationConfig.of(BigDecimal("0.1"), 2, 2000, 9000, 4000, BigDecimal("10000"), 4500, 1, BigDecimal("0.001"))
        val shortfall = DatasetCoverage.undecided(ReplayRunner().run(decidedDataset, fartherTakeProfit), decidedDataset).single()
        assertEquals(MINT, shortfall.pair.base.value)
        assertEquals(Slot.of(SLOT + 240), shortfall.throughSlot)
        assertTrue(DatasetCoverage.undecided(ReplayRunner().run(fullDataset, fartherTakeProfit), fullDataset).isEmpty())
    }

    @Test
    fun `a decided capture counts its market reads and its cutoff rates under the usage context of the request`() {
        RecordedWalletFixtures.save(mapper, wallet())
        val chain = Chain(::takeProfitTape)
        val rates = FixedRates()
        val usage = ApiUsageContext(ApiTask.MARKET_STEP, "capture-1")

        capture(chain, "decided", MarketCutoffMode.DECIDED, rates, usage)

        assertEquals(setOf(usage), chain.usages)
        assertEquals(setOf(usage), rates.usages)
    }

    @Test
    fun `a window without a dip in its 30 seconds is decided no dip only at the first trade four minutes after the buy`() {
        RecordedWalletFixtures.save(mapper, wallet())
        val chain = Chain({ NORMAL })

        val manifest = capture(chain, "no-dip", MarketCutoffMode.DECIDED, FixedRates()).manifest

        assertEquals((SLOT + 1..SLOT + 300).toList(), chain.fetchedSlots)
        assertEquals(SLOT + 240, manifest.windows.single().cutoff?.throughSlot)
        assertEquals("NO_DIP", manifest.windows.single().cutoff?.outcome)
        assertEquals(Slot.of(SLOT + 240), export("no-dip").coverage.single().throughSlot)
    }

    @Test
    fun `a take profit followed by 1200 quiet seconds is sold against its standing curve alike in a decided and a full capture`() {
        RecordedWalletFixtures.save(mapper, wallet())

        val decided = capture(Chain(::quietAfterTakeProfit, QUIET_TAPE), "decided", MarketCutoffMode.DECIDED, FixedRates()).manifest
        capture(Chain(::quietAfterTakeProfit, QUIET_TAPE), "full", MarketCutoffMode.FULL)

        assertEquals(MarketWindowCutoff(SLOT + 1299, "TAKE_PROFIT", DipOutcomeCutoff.IDENTITY.engineVersion,
            DipOutcomeCutoff.IDENTITY.configurationHash), decided.windows.single().cutoff)
        val decidedClose = agreedClose(export("decided"))
        assertEquals(ExitPriceAssumption.STANDING_CURVE, decidedClose.priceAssumption)
        assertEquals(Slot.of(SLOT + 101), decidedClose.slot)
        assertEquals(agreedClose(export("full")), decidedClose)
        assertEquals(qualify("full").exit, qualify("decided").exit)
    }

    @Test
    fun `a malformed block time reply leaves the cutoff waiting without a replay failure and the capture continues`() {
        RecordedWalletFixtures.save(mapper, wallet())
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        val loggers = listOf(DipOutcomeCutoff::class.java, WindowCutoffEvaluator::class.java).map { LoggerFactory.getLogger(it) as Logger }
        loggers.forEach { it.addAppender(logs) }

        val manifest = try {
            capture(Chain(::takeProfitTape, malformedBlockTime = true), "malformed", MarketCutoffMode.DECIDED, FixedRates()).manifest
        } finally {
            loggers.forEach { it.detachAppender(logs) }
        }

        assertEquals(SLOT + 240, manifest.windows.single().cutoff?.throughSlot)
        assertNull(manifest.windows.single().throughBlockTime)
        assertTrue(manifest.complete)
        assertTrue(logs.list.none { it.level == Level.ERROR })
        assertEquals(listOf(SLOT + 100, SLOT + 200).map { "market_capture cutoff block_time_unavailable mint=$MINT slot=$it category=BLOCK_TIME_RESPONSE" },
            logs.list.filter { it.level == Level.WARN }.map { it.formattedMessage })
    }

    @Test
    fun `a full capture reads no block time before its window settles`() {
        RecordedWalletFixtures.save(mapper, wallet())
        val chain = Chain(::takeProfitTape)

        capture(chain, "full", MarketCutoffMode.FULL)

        assertEquals(listOf(SLOT + 4500), chain.blockTimeReads)
    }

    @Test
    fun `a decided capture saved before the lead resumes with its legacy window and stays version three`() {
        RecordedWalletFixtures.save(mapper, wallet())
        val rates = FixedRates()
        val beforeLead = Chain(::takeProfitTape)
        val stopped = capture(beforeLead, "legacy", MarketCutoffMode.DECIDED, rates, requestBudget = 40, markets = plannedBeforeLead())
        assertEquals(2, stopped.manifest.schemaVersion)
        assertNull(stopped.manifest.windows.single().cutoff)

        val resumed = Chain(::takeProfitTape)
        val decided = capture(resumed, "legacy", MarketCutoffMode.DECIDED, rates).manifest
        val again = Chain(::takeProfitTape)
        val rerun = capture(again, "legacy", MarketCutoffMode.DECIDED, rates).manifest

        assertEquals(SLOT + 1, decided.windows.single().fromSlot)
        assertEquals(MarketCaptureWindowParameters.LEGACY_LEAD_SLOTS, decided.windowParameters.leadSlots)
        assertEquals(3, decided.schemaVersion)
        assertEquals(SLOT + 240, decided.windows.single().cutoff?.throughSlot)
        assertEquals(emptyList(), beforeLead.blockTimeReads + resumed.blockTimeReads + again.blockTimeReads)
        assertTrue((beforeLead.listings + resumed.listings).all { it["until"] == SIGNATURE })
        assertTrue(Files.readString(directory.resolve("legacy").resolve("market-manifest.json")).contains("\"schemaVersion\":3"))
        assertEquals(emptyList(), again.fetchedSlots)
        assertEquals(decided.copy(requestsUsed = 0), rerun.copy(requestsUsed = 0))
    }

    /** Plans as this service did before the lead existed: a directory without a manifest gets the legacy `S + 1` window. */
    private fun plannedBeforeLead(): RecordedMarketHistory {
        val recorded = MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper)
        return object : RecordedMarketHistory by recorded {
            override fun windowParameters(directory: Path): MarketCaptureWindowParameters =
                recorded.windowParameters(directory) ?: MarketCaptureWindowParameters(SimulationConfig.mvp().horizonSlots, SimulationConfig.mvp().maxAgeSlots)
        }
    }

    private fun takeProfitTape(offset: Long): BigInteger = when (offset) {
        10L -> DIP
        39L -> TAKE_PROFIT
        else -> NORMAL
    }

    /** The dip at S+10 and a take profit crossing at S+100, the last trade before [QUIET_TAPE]'s 1,200 quiet seconds. */
    private fun quietAfterTakeProfit(offset: Long): BigInteger = when (offset) {
        10L -> DIP
        100L -> TAKE_PROFIT
        else -> NORMAL
    }

    private fun agreedClose(dataset: ReplayDataset): PositionClose =
        assertNotNull(ReplayRunner().run(dataset, SimulationConfig.agreed()).scenarios.single { it.intent.pair.base.value == MINT }.position?.close)

    private fun capture(chain: Chain, name: String, mode: MarketCutoffMode, rates: SolUsdtRateSource? = null,
        usage: ApiUsageContext = ApiUsageContext.UNATTRIBUTED, requestBudget: Int = 1000,
        markets: RecordedMarketHistory = MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper)): MarketHistoryCaptureResult =
        ReplayMarketCaptureService(WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper), markets,
            { MarketHistoryCaptureService(chain.rpc(), MarketHistoryArtifactStore(mapper), CLOCK) }, rates)
            .capture(ReplayMarketCaptureRequest(wallet(), directory.resolve(name), 20, 1000, requestBudget, 104_857_600, cutoffMode = mode, usage = usage))

    private fun export(name: String): ReplayDataset {
        val output = directory.resolve("$name.json")
        val recordedRates = object : RecordedReferenceRates {
            override fun inspect(directory: Path): ReferenceRateCaptureResult = error("Export reads rates, not their audit")
            override fun rates(directory: Path): List<ReferenceRate> = RATES
        }
        RecordedReplayExportService(WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper),
            MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper), recordedRates, mapper)
            .export(RecordedReplayExportRequest(wallet(), directory.resolve(name), directory.resolve("rates"), output))
        return Files.newInputStream(output).use { ReplayDatasetLoader(mapper).load(it) }
    }

    private fun qualify(name: String): TargetTokenVerdict {
        val ready = CaptureStageSummary(CaptureStageState.READY, null)
        val inspection = CaptureInspection(ready, ready, ready, canCalibrate = true, canExport = true)
        return TargetTokenQualificationService(ReplayDatasetLoader(mapper), { inspection })
            .qualify(directory.resolve("$name.json"), CapturePaths(wallet()))
    }

    private fun wallet(): Path = directory.resolve("wallet")

    /**
     * One successful Pump.fun trade of [MINT] per slot of [slots], by default S+1..S+340, its trade event paying [solAmount]
     * lamports for the slot offset, and a block for every slot at [blockTime], answered with text when [malformedBlockTime].
     */
    private inner class Chain(
        private val solAmount: (Long) -> BigInteger,
        private val slots: List<Long> = (SLOT + 1..SLOT + TRADES).toList(),
        private val malformedBlockTime: Boolean = false,
    ) {
        private val slotOf = slots.associateBy(::signature)
        val fetchedSlots = mutableListOf<Long>()
        val listings = mutableListOf<Map<*, *>>()
        val blockTimeReads = mutableListOf<Long>()
        val usages: MutableSet<ApiUsageContext> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        fun rpc() = SolanaReadRpc { method, params, budget ->
            budget.request()
            usages += budget.usage
            when (method) {
                "getGenesisHash" -> mapper.readTree("\"$GENESIS\"")
                "getSlot" -> mapper.readTree(Long.MAX_VALUE.toString())
                "getSignaturesForAddress" -> mapper.readTree(slots.sortedDescending().also { listings += params[1] as Map<*, *> }
                    .joinToString(",", "[", "]") { """{"signature":"${signature(it)}","slot":$it,"err":null,"memo":null,"blockTime":1791049629}""" })
                "getTransaction" -> {
                    val slot = slotOf.getValue(params[0] as String)
                    synchronized(this) { fetchedSlots += slot }
                    trade(params[0] as String, slot, solAmount(slot - SLOT))
                }
                "getBlockTime" -> {
                    val slot = params[0] as Long
                    blockTimeReads += slot
                    mapper.readTree(if (malformedBlockTime) "\"soon\"" else blockTime(slot).epochSecond.toString())
                }
                else -> error("Unexpected method $method")
            }
        }
    }

    /** The buy's block time plus one second per slot after it. */
    private fun blockTime(slot: Long): Instant = T0.plusSeconds(slot - SLOT)

    private fun trade(signature: String, slot: Long, solAmount: BigInteger): JsonNode = fixture.deepCopy().apply {
        (path("transaction").path("signatures") as ArrayNode).set(0, mapper.nodeFactory.stringNode(signature))
        put("slot", slot)
        put("blockTime", blockTime(slot).epochSecond)
        val event = path("meta").path("innerInstructions").flatMap { it.path("instructions") }
            .single { it.path("programId").asString() == PumpFunLayout.PROGRAM_ID &&
                requireNotNull(Base58.decode(it.path("data").asString())).take(PumpFunLayout.TRADE_EVENT_PREFIX.size) ==
                PumpFunLayout.TRADE_EVENT_PREFIX.toList() } as ObjectNode
        val bytes = requireNotNull(Base58.decode(event.path("data").asString()))
        (0 until PumpFunLayout.U64_BYTES).forEach { index ->
            bytes[PumpFunLayout.EVENT_SOL_AMOUNT_OFFSET + index] = solAmount.shiftRight(index * Byte.SIZE_BITS).toByte()
        }
        event.put("data", Base58.encode(bytes))
    }

    private fun signature(slot: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return Base58.encode(digest.digest("a$slot".toByteArray()) + digest.digest("b$slot".toByteArray()))
    }

    /** 150 USDT per SOL for every minute, counting the lookups. */
    private class FixedRates : SolUsdtRateSource {
        var requests = 0
        val usages = mutableSetOf<ApiUsageContext>()

        override fun minuteRates(minutes: Set<Instant>, usage: ApiUsageContext): Map<Instant, RateLookup> {
            usages += usage
            return minuteRates(minutes)
        }

        override fun minuteRates(minutes: Set<Instant>): Map<Instant, RateLookup> {
            requests++
            return minutes.associateWith { RateLookup.Available(RATE.rate, it, RATE.source) }
        }

        override fun current(): RateLookup = error("The cutoff never asks for the current price")
    }

    private companion object {
        const val GENESIS = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"
        const val TRADES = 340L
        /** A trade per slot through S+100, then none until S+1300, 1,200 s after the take profit, and 40 more after it. */
        val QUIET_TAPE: List<Long> = (SLOT + 1..SLOT + 100) + (SLOT + 1300..SLOT + 1340)

        /** The tracked wallet's own buy paid 244444444 lamports for these tokens, so this is P0. */
        val NORMAL: BigInteger = BigInteger.valueOf(244_444_444)
        val DIP: BigInteger = BigInteger.valueOf(195_555_555)
        /** About 1.8 times the entry cost per token: above the agreed +55% take profit, below a +90% one. */
        val TAKE_PROFIT: BigInteger = BigInteger.valueOf(455_555_555)
        /** The wallet buy's block time, 2026-10-03T17:47:09Z. */
        val T0: Instant = Instant.ofEpochSecond(1_791_049_629)
        val RATE = ReferenceRate(Instant.parse("2026-10-03T17:47:00Z"), BigDecimal("150"), "test-fixture:SOLUSDT:1m:open")
        /** The rate of every minute a tape trades in, 17:47 through 18:10. */
        val RATES: List<ReferenceRate> = (0L..23L).map { RATE.copy(minute = RATE.minute.plusSeconds(60 * it)) }
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC)
    }
}
