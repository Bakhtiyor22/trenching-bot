package com.trenching.bot.replay

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.Commitment
import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketHistoryCaptureConfiguration
import com.trenching.bot.marketdata.MarketHistoryCaptureResult
import com.trenching.bot.marketdata.MarketHistoryDecodeResult
import com.trenching.bot.marketdata.MarketObservationExtractorVersion
import com.trenching.bot.marketdata.RecordedMarketHistory
import com.trenching.bot.marketdata.domain.CurveState
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.ObservationQuality
import com.trenching.bot.protocol.pumpfun.CurveReserves
import com.trenching.bot.replay.RecordedWalletFixtures.MINT
import com.trenching.bot.replay.RecordedWalletFixtures.SIGNATURE
import com.trenching.bot.replay.RecordedWalletFixtures.SLOT
import com.trenching.bot.replay.RecordedWalletFixtures.WALLET
import com.trenching.bot.replay.application.CalibrationMedians
import com.trenching.bot.replay.application.ExecutionCalibration
import com.trenching.bot.replay.application.ExecutionCalibrationService
import com.trenching.bot.replay.domain.BuyCosts
import com.trenching.bot.replay.domain.CalibrationReport
import com.trenching.bot.replay.domain.CalibrationSummary
import com.trenching.bot.replay.domain.CurveMathCheck
import com.trenching.bot.replay.domain.LatencyCheck
import com.trenching.bot.replay.domain.UnpredictableReason
import com.trenching.bot.replay.infrastructure.fixture.CalibrationReportWriter
import com.trenching.bot.walletactivity.PumpFunBuyFacts
import com.trenching.bot.walletactivity.PumpFunBuyInstruction
import com.trenching.bot.walletactivity.RecordedWalletHistory
import com.trenching.bot.walletactivity.WalletHistoryCaptureConfiguration
import com.trenching.bot.walletactivity.WalletHistoryDecodeResult
import com.trenching.bot.walletactivity.WalletHistoryDecoderVersion
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper

class ExecutionCalibrationTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `the curve formula and fees reproduce Decu's real captured buy exactly`() {
        RecordedWalletFixtures.save(mapper, wallet())

        val report = service(plannedMarket()).calibrate(wallet(), markets())

        val buy = report.buys.single()
        assertEquals(TransactionSignature.of(SIGNATURE), buy.signature)
        assertEquals(CurveMathCheck(
            tokenAmount = BigInteger("1238520682656"), computedTokenAmount = BigInteger("1238520682656"),
            feeLamports = BigInteger("2322223"), computedFeeLamports = BigInteger("2322223"),
            creatorFeeLamports = BigInteger("733334"), computedCreatorFeeLamports = BigInteger("733334"),
        ), buy.math)
        assertTrue(buy.math.exact)
        assertEquals(1, report.summary.mathExact)
        assertEquals(0, report.summary.mathMismatches)
        assertEquals(WalletAddress.of(WALLET), report.wallet)
        assertEquals(WalletHistoryDecoderVersion.PUMPFUN_BONDING_CURVE_BUY_V2, report.decoder)
        assertEquals(MarketObservationExtractorVersion.PUMPFUN_BONDING_CURVE_TRADE_V2, report.extractor)
    }

    @Test
    fun `Decu's first buy of a mint has no prior captured state, since its window starts the slot after it`() {
        RecordedWalletFixtures.save(mapper, wallet())

        val report = service(plannedMarket()).calibrate(wallet(), markets())

        assertEquals(LatencyCheck.NoPriorState, report.buys.single().latency)
        assertEquals(1, report.summary.noPriorState)
        assertEquals(0, report.summary.latencyChecked)
        assertNull(report.summary.medianErrorBps)
        assertNull(report.summary.maxErrorBps)
    }

    @Test
    fun `the real buy's other lamports are the wallet's spend beyond the curve, its fees and the network fee`() {
        RecordedWalletFixtures.save(mapper, wallet())

        val report = service(plannedMarket()).calibrate(wallet(), markets())

        // 265,005,001 spent - 244,444,444 curve - 2,322,223 fee - 733,334 creator fee - 5,005,000 network fee
        assertEquals(BuyCosts(BigInteger("5005000"), BigInteger("12500000")), report.buys.single().costs)
        assertEquals(BigDecimal("5005000").stripTrailingZeros(), report.summary.medianTransactionFeeLamports)
        assertEquals(BigDecimal("12500000").stripTrailingZeros(), report.summary.medianOtherLamports)
    }

    @Test
    fun `an exact quote in v3 buy is priced on one lamport less than it pays into the curve`() {
        // Pre-trade: 62,384,159,210 virtual SOL, 515,996,383,769,798 virtual tokens.
        // floor(244,444,442 x 515,996,383,769,798 / 62,628,603,652) = 2,013,975,096,834 tokens, the recorded amount;
        // the legacy rule on the full 244,444,443 lamports would give 2,013,975,105,041.
        val buys = ExecutionCalibration.buys(listOf(REAL_V3), emptyList())

        val math = buys.single().math
        assertTrue(math.exact)
        assertEquals(BigInteger("2013975096834"), math.computedTokenAmount)
        assertEquals(PumpFunBuyInstruction.BUY_EXACT_QUOTE_IN_V3, buys.single().instruction)
        assertEquals(mapOf("BUY_EXACT_QUOTE_IN_V3" to 1), ExecutionCalibration.summary(buys).instructions)
    }

    @Test
    fun `the legacy rule does not reproduce an exact quote in v3 buy`() {
        val math = ExecutionCalibration.buys(listOf(REAL_V3.copy(instruction = PumpFunBuyInstruction.BUY)), emptyList()).single().math

        assertEquals(BigInteger("2013975105041"), math.computedTokenAmount)
        assertFalse(math.exact)
    }

    @Test
    fun `latency predictions use the buy instruction's own curve rule`() {
        val prior = observation("prior", 455091774, CurveState(CurveReserves(BigInteger("62384159210"), BigInteger("515996383769798"),
            BigInteger("32384159210"), BigInteger("236096383769798")), feeBps = 95, creatorFeeBps = 30), REAL_V3.mint.value)

        val latency = ExecutionCalibration.buys(listOf(REAL_V3), listOf(prior)).single().latency

        val checked = assertIs<LatencyCheck.Checked>(latency)
        assertEquals(BigInteger("2013975096834"), checked.predictedTokenAmount)
        assertEquals(BigDecimal("0.00"), checked.errorBps)
    }

    @Test
    fun `a buy the formula does not reproduce is counted and listed with both values`() {
        val forged = REAL.copy(tokenAmount = REAL.tokenAmount.add(BigInteger.ONE), creatorFeeLamports = BigInteger("733333"))

        val buys = ExecutionCalibration.buys(listOf(forged), emptyList())

        val math = buys.single().math
        assertFalse(math.exact)
        assertEquals(BigInteger("1238520682657"), math.tokenAmount)
        assertEquals(BigInteger("1238520682656"), math.computedTokenAmount)
        assertEquals(BigInteger("733333"), math.creatorFeeLamports)
        assertEquals(BigInteger("733334"), math.computedCreatorFeeLamports)
        val summary = ExecutionCalibration.summary(buys)
        assertEquals(0, summary.mathExact)
        assertEquals(1, summary.mathMismatches)
    }

    @Test
    fun `a buy whose pre-trade reserves are not a valid curve is a mismatch without a computed amount`() {
        val forged = REAL.copy(postReserves = CurveReserves(REAL.solAmount, BigInteger("403233026450985"), BigInteger.ZERO, BigInteger.ONE))

        val math = ExecutionCalibration.buys(listOf(forged), emptyList()).single().math

        assertNull(math.computedTokenAmount)
        assertFalse(math.exact)
    }

    @Test
    fun `the latency error compares the buy with the newest captured state of its mint in a strictly earlier slot`() {
        val buy = synthetic(slot = 100, solAmount = "1000000000", tokenAmount = "34000000000000")
        val observations = listOf(
            observation("older", 98, OTHER_STATE),
            observation("newest-earlier", 99, LAUNCH_STATE),
            observation("same-slot", 100, OTHER_STATE),
            observation("other-mint", 99, OTHER_STATE, mint = OTHER_MINT),
        )

        val latency = ExecutionCalibration.buys(listOf(buy), observations).single().latency

        // floor(1e9 * 1.073e15 / (30e9 + 1e9)) = 34,612,903,225,806; (34e12 - that) / that = -177.0736.. bps
        assertEquals(LatencyCheck.Checked("newest-earlier", Slot.of(99), BigInteger("34612903225806"), BigDecimal("-177.07")), latency)
    }

    @Test
    fun `a prediction beyond the prior state's real tokens is reported per buy, not checked`() {
        val buy = synthetic(slot = 100, solAmount = "1000000000", tokenAmount = "34000000000000")
        val nearlyComplete = CurveState(CurveReserves(BigInteger("84000000000"), BigInteger("280000000000000"),
            BigInteger("54000000000"), BigInteger("1000000")), feeBps = 95, creatorFeeBps = 30)

        val buys = ExecutionCalibration.buys(listOf(buy), listOf(observation("full", 99, nearlyComplete)))

        assertEquals(LatencyCheck.Unpredictable("full", Slot.of(99), UnpredictableReason.EXCEEDS_CURVE), buys.single().latency)
        val summary = ExecutionCalibration.summary(buys)
        assertEquals(1, summary.unpredictable)
        assertEquals(0, summary.latencyChecked)
    }

    @Test
    fun `a prior state that yields no whole token is reported per buy, never divided by`() {
        val buy = synthetic(slot = 100, solAmount = "1", tokenAmount = "1")
        val tiny = CurveState(CurveReserves(BigInteger("1000"), BigInteger("10"), BigInteger.ZERO, BigInteger("10")), feeBps = 0, creatorFeeBps = 0)

        val latency = ExecutionCalibration.buys(listOf(buy), listOf(observation("tiny", 99, tiny))).single().latency

        assertEquals(LatencyCheck.Unpredictable("tiny", Slot.of(99), UnpredictableReason.ZERO_TOKENS), latency)
    }

    @Test
    fun `costs leave tips and rent as the residual of the wallet's lamport change`() {
        val buy = synthetic(slot = 100, solAmount = "1000000000", tokenAmount = "34000000000000").copy(
            feeLamports = BigInteger("9500000"),
            creatorFeeLamports = BigInteger("3000000"),
            transactionFeeLamports = BigInteger("5000"),
            walletLamportDelta = BigInteger("-1014544280"),
        )

        val costs = ExecutionCalibration.buys(listOf(buy), emptyList()).single().costs

        // 1,014,544,280 - 1,000,000,000 - 9,500,000 - 3,000,000 - 5,000 = 2,039,280, one token account's rent
        assertEquals(BuyCosts(BigInteger("5000"), BigInteger("2039280")), costs)
    }

    @Test
    fun `the summary counts every outcome and takes medians and the maximum of absolute errors`() {
        val first = synthetic(slot = 50, solAmount = "1000000000", tokenAmount = "34000000000000", signature = SIGNATURE_A)
        val checked = synthetic(slot = 100, solAmount = "1000000000", tokenAmount = "34000000000000", signature = SIGNATURE_B)
        val later = synthetic(slot = 200, solAmount = "500000000", tokenAmount = "16600000000000", signature = SIGNATURE_C)
        val observations = listOf(observation("launch", 99, LAUNCH_STATE), observation("after", 150, OTHER_STATE))

        val buys = ExecutionCalibration.buys(listOf(later, checked, first), observations)

        assertEquals(listOf(50L, 100L, 200L), buys.map { it.slot.value })
        // floor(5e8 * 1,038,387,096,774,194 / 31.5e9) = 16,482,334,869,431; (16.6e12 - that) / that = +71.39 bps
        assertEquals(BigDecimal("71.39"), assertIs<LatencyCheck.Checked>(buys[2].latency).errorBps)
        assertEquals(CalibrationSummary(
            buys = 3, mathExact = 0, mathMismatches = 3, latencyChecked = 2, noPriorState = 1, unpredictable = 0,
            medianErrorBps = BigDecimal("124.23"), maxErrorBps = BigDecimal("177.07"),
            medianTransactionFeeLamports = BigDecimal("5000").stripTrailingZeros(), medianOtherLamports = BigDecimal.ZERO,
            instructions = mapOf("BUY" to 3),
        ), ExecutionCalibration.summary(buys))
    }

    @Test
    fun `buys in one slot are ordered by signature`() {
        val b = synthetic(slot = 100, solAmount = "1", tokenAmount = "1", signature = SIGNATURE_B)
        val a = synthetic(slot = 100, solAmount = "1", tokenAmount = "1", signature = SIGNATURE_A)

        assertEquals(listOf(SIGNATURE_A, SIGNATURE_B), ExecutionCalibration.buys(listOf(b, a), emptyList()).map { it.signature.value })
    }

    @Test
    fun `basis point medians take the middle value, or the HALF_EVEN mean of the middle two at scale 2`() {
        assertEquals(BigDecimal("2.00"), CalibrationMedians.basisPoints(listOf(BigDecimal("3"), BigDecimal("1"), BigDecimal("2"))))
        assertEquals(BigDecimal("2.50"), CalibrationMedians.basisPoints(listOf(BigDecimal("4.01"), BigDecimal("1"), BigDecimal("3"), BigDecimal("2"))))
        assertEquals(BigDecimal("1.50"), CalibrationMedians.basisPoints(listOf(BigDecimal("1.00"), BigDecimal("2.01"))))
        assertEquals(BigDecimal("1.52"), CalibrationMedians.basisPoints(listOf(BigDecimal("1.01"), BigDecimal("2.02"))))
        assertNull(CalibrationMedians.basisPoints(emptyList()))
    }

    @Test
    fun `lamport medians preserve the exact middle value or fractional mean of the middle two`() {
        assertEquals("3", CalibrationMedians.lamports(listOf(7L, 1L, 3L).map(BigInteger::valueOf))?.toPlainString())
        assertEquals("5000.5", CalibrationMedians.lamports(listOf(5001L, 5000L).map(BigInteger::valueOf))?.toPlainString())
        assertEquals("5001.5", CalibrationMedians.lamports(listOf(5001L, 5002L).map(BigInteger::valueOf))?.toPlainString())
        assertEquals("-2", CalibrationMedians.lamports(listOf(-1L, -3L, 10L, -5L).map(BigInteger::valueOf))?.toPlainString())
        assertEquals("-1.5", CalibrationMedians.lamports(listOf(-1L, -2L).map(BigInteger::valueOf))?.toPlainString())
        assertNull(CalibrationMedians.lamports(emptyList()))
    }

    @Test
    fun `the report json is byte identical across runs`() {
        RecordedWalletFixtures.save(mapper, wallet())
        val writer = CalibrationReportWriter(mapper)

        val first = writer.write(service(plannedMarket()).calibrate(wallet(), markets()))
        val second = writer.write(service(plannedMarket()).calibrate(wallet(), markets()))

        assertEquals(first, second)
        val json = mapper.readTree(first)
        assertEquals("1238520682656", json.path("buys").path(0).path("math").path("computedTokenAmount").asString())
        assertEquals("NO_PRIOR_STATE", json.path("buys").path(0).path("latency").path("status").asString())
        assertEquals("12500000", json.path("summary").path("medianOtherLamports").asString())
        assertTrue(json.path("summary").path("medianErrorBps").isNull)
        assertEquals(0, json.path("mathMismatches").size())
    }

    @Test
    fun `the report json lists mismatching signatures and writes checked latencies and decimals exactly`() {
        val forged = REAL.copy(tokenAmount = REAL.tokenAmount.add(BigInteger.ONE))
        val checked = synthetic(slot = 100, solAmount = "1000000000", tokenAmount = "34000000000000", signature = SIGNATURE_A)
        val buys = ExecutionCalibration.buys(listOf(forged, checked), listOf(observation("launch", 99, LAUNCH_STATE)))
        val report = CalibrationReport(WalletAddress.of(WALLET),
            WalletHistoryDecoderVersion.PUMPFUN_BONDING_CURVE_BUY_V1, MarketObservationExtractorVersion.PUMPFUN_BONDING_CURVE_TRADE_V1,
            ExecutionCalibration.summary(buys), buys)

        val json = mapper.readTree(CalibrationReportWriter(mapper).write(report))

        val mismatches = json.path("mathMismatches")
        assertEquals(listOf(SIGNATURE_A, SIGNATURE), (0 until mismatches.size()).map { mismatches.path(it).asString() })
        val latency = json.path("buys").path(0).path("latency")
        assertEquals("CHECKED", latency.path("status").asString())
        assertEquals("-177.07", latency.path("errorBps").asString())
        assertEquals("34612903225806", latency.path("predictedTokenAmount").asString())
        assertEquals(99, latency.path("observationSlot").asLong())
        assertEquals(report.summary.maxErrorBps?.toPlainString(), json.path("summary").path("maxErrorBps").asString())
    }

    @Test
    fun `refuses an incomplete wallet capture and a market capture that does not match its plan`() {
        RecordedWalletFixtures.save(mapper, wallet(), complete = false)
        assertCategory("WALLET_CAPTURE_INCOMPLETE") { service(plannedMarket()).calibrate(wallet(), markets()) }

        RecordedWalletFixtures.save(mapper, directory.resolve("complete"))
        val shifted = ReplayMarketFixtures.decoded(markets(), ReplayMarketFixtures.manifest(listOf(window(SLOT + 2, SLOT + 4500))), 1)
        assertCategory("MARKET_CAPTURE_MISMATCH") { service(shifted).calibrate(directory.resolve("complete"), markets()) }
        assertCategory("MARKET_CAPTURE_INCOMPLETE") {
            service(plannedMarket().copy(complete = false)).calibrate(directory.resolve("complete"), markets())
        }
    }

    @Test
    fun `latency errors round signed half ties to even at scale two`() {
        val curve = CurveState(CurveReserves(BigInteger("1"), BigInteger("4000000"),
            BigInteger.ZERO, BigInteger("4000000")), feeBps = 0, creatorFeeBps = 0)
        val prior = listOf(observation("prior", 99, curve))
        mapOf("2000001" to "0.00", "2000003" to "0.02", "1999999" to "0.00", "1999997" to "-0.02")
            .forEach { (actual, expected) ->
                val buy = synthetic(slot = 100, solAmount = "1", tokenAmount = actual)
                val latency = assertIs<LatencyCheck.Checked>(ExecutionCalibration.buys(listOf(buy), prior).single().latency)
                assertEquals(BigInteger("2000000"), latency.predictedTokenAmount)
                assertEquals(BigDecimal(expected), latency.errorBps)
            }
    }

    @Test
    fun `same slot observations choose the last canonical id regardless of input order`() {
        val buy = synthetic(slot = 100, solAmount = "1000000000", tokenAmount = "34000000000000")
        val prior = listOf(observation("z", 99, OTHER_STATE), observation("a", 99, LAUNCH_STATE))
        val first = ExecutionCalibration.buys(listOf(buy), prior)
        val second = ExecutionCalibration.buys(listOf(buy), prior.reversed())

        assertEquals(first, second)
        assertEquals("z", assertIs<LatencyCheck.Checked>(first.single().latency).observationId)
    }

    @Test
    fun `wallet refunds remain a negative cost residual without clamping`() {
        val buy = synthetic(slot = 0, solAmount = "1", tokenAmount = "1")
            .copy(walletLamportDelta = BigInteger("2000000"))
        val result = ExecutionCalibration.buys(listOf(buy), emptyList()).single()

        assertEquals(LatencyCheck.NoPriorState, result.latency)
        assertEquals(BuyCosts(BigInteger("5000"), BigInteger("-2005001")), result.costs)
        assertEquals(BigDecimal("-2005001").stripTrailingZeros(), ExecutionCalibration.summary(listOf(result)).medianOtherLamports)
    }

    @Test
    fun `saving a report creates its directory replaces its file and leaves no pending file`() {
        RecordedWalletFixtures.save(mapper, wallet())
        val report = service(plannedMarket()).calibrate(wallet(), markets())
        val writer = CalibrationReportWriter(mapper)
        val target = directory.resolve("output/calibration.json")

        writer.save(target, report)
        Files.writeString(target, "outdated report")
        writer.save(target, report)

        assertEquals(writer.write(report), Files.readString(target))
        Files.list(target.parent).use { files -> assertEquals(listOf(target), files.toList()) }
    }
    @Test
    fun `cost summary and json preserve half lamports for an even number of buys`() {
        val first = synthetic(slot = 100, solAmount = "1", tokenAmount = "1", signature = SIGNATURE_A)
            .copy(transactionFeeLamports = BigInteger("5000"), walletLamportDelta = BigInteger("-5000"))
        val second = synthetic(slot = 101, solAmount = "1", tokenAmount = "1", signature = SIGNATURE_B)
            .copy(transactionFeeLamports = BigInteger("5001"), walletLamportDelta = BigInteger("-5000"))
        val buys = ExecutionCalibration.buys(listOf(second, first), emptyList())
        val summary = ExecutionCalibration.summary(buys)
        val report = CalibrationReport(WalletAddress.of(WALLET), WalletHistoryDecoderVersion.PUMPFUN_BONDING_CURVE_BUY_V1,
            MarketObservationExtractorVersion.PUMPFUN_BONDING_CURVE_TRADE_V1, summary, buys)
        val json = mapper.readTree(CalibrationReportWriter(mapper).write(report)).path("summary")

        assertEquals(BigDecimal("5000.5"), summary.medianTransactionFeeLamports)
        assertEquals(BigDecimal("-1.5"), summary.medianOtherLamports)
        assertEquals("5000.5", json.path("medianTransactionFeeLamports").asString())
        assertEquals("-1.5", json.path("medianOtherLamports").asString())
    }
    @Test
    fun `a later buy beyond a decided prefix refuses calibration instead of using its stale last trade`() {
        RecordedWalletFixtures.save(mapper, wallet())
        val partial = plannedMarket().let { market ->
            market.copy(windows = market.windows.map { it.copy(observedThrough = Slot.of(SLOT + 40)) },
                observations = listOf(observation("at-cutoff", SLOT + 40, LAUNCH_STATE)))
        }

        assertCategory("MARKET_COVERAGE_ENDED") {
            service(partial, laterBuySlot = SLOT + 200).calibrate(wallet(), markets())
        }

        val extended = partial.copy(windows = partial.windows.map { it.copy(observedThrough = it.window.toSlot) },
            observations = partial.observations + observation("before-later-buy", SLOT + 199, OTHER_STATE))
        val report = service(extended, laterBuySlot = SLOT + 200).calibrate(wallet(), markets())
        assertEquals("before-later-buy", assertIs<LatencyCheck.Checked>(report.buys.last().latency).observationId)
    }

    @Test
    fun `a decided prefix through the slot before a later buy is sufficient for calibration`() {
        RecordedWalletFixtures.save(mapper, wallet())
        val partial = plannedMarket().let { market ->
            market.copy(windows = market.windows.map { it.copy(observedThrough = Slot.of(SLOT + 40)) },
                observations = listOf(observation("at-cutoff", SLOT + 40, LAUNCH_STATE)))
        }

        val report = service(partial, laterBuySlot = SLOT + 41).calibrate(wallet(), markets())

        assertEquals(Slot.of(SLOT + 40), assertIs<LatencyCheck.Checked>(report.buys.last().latency).observationSlot)
    }

    @Test
    fun `a decided prefix without observations still calibrates its anchor buy without a prior state`() {
        RecordedWalletFixtures.save(mapper, wallet())
        val partial = plannedMarket().let { market ->
            market.copy(windows = market.windows.map { it.copy(observedThrough = Slot.of(SLOT)) }, observations = emptyList())
        }

        val report = service(partial).calibrate(wallet(), markets())

        assertEquals(LatencyCheck.NoPriorState, report.buys.single().latency)
    }

    private fun service(market: MarketHistoryDecodeResult, laterBuySlot: Long? = null): ExecutionCalibrationService {
        val markets = object : RecordedMarketHistory {
            override fun inspect(directory: Path): MarketHistoryCaptureResult =
                MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper).inspect(directory)

            override fun decode(directory: Path): MarketHistoryDecodeResult = market
        }
        val recorded = WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper)
        val wallets = object : RecordedWalletHistory {
            override fun inspect(directory: Path) = recorded.inspect(directory)
            override fun decode(directory: Path, wallet: WalletAddress): WalletHistoryDecodeResult {
                val history = recorded.decode(directory, wallet)
                return laterBuySlot?.let { slot ->
                    history.copy(transactions = history.transactions + history.transactions.first().copy(
                        signature = TransactionSignature.of(SIGNATURE_A), slot = Slot.of(slot)),
                        buyFacts = history.buyFacts + history.buyFacts.first().copy(
                            signature = TransactionSignature.of(SIGNATURE_A), slot = Slot.of(slot)))
                } ?: history
            }
        }
        return ExecutionCalibrationService(wallets, markets)
    }

    private fun plannedMarket(): MarketHistoryDecodeResult =
        ReplayMarketFixtures.decoded(markets(), ReplayMarketFixtures.manifest(listOf(window(SLOT + 1, SLOT + 4500))), 1)

    private fun window(from: Long, to: Long) =
        MarketCaptureWindow.of(MintAddress.of(MINT), Slot.of(from), Slot.of(to), TransactionSignature.of(SIGNATURE))

    private fun wallet(): Path = directory.resolve("wallet")

    private fun markets(): Path = directory.resolve("markets")

    private fun assertCategory(category: String, action: () -> Unit) {
        assertEquals(category, assertFailsWith<CaptureValidationException> { action() }.category)
    }

    private fun synthetic(
        slot: Long,
        solAmount: String,
        tokenAmount: String,
        signature: String = SIGNATURE_A,
        instruction: PumpFunBuyInstruction = PumpFunBuyInstruction.BUY,
    ) = PumpFunBuyFacts(
        signature = TransactionSignature.of(signature),
        slot = Slot.of(slot),
        mint = MintAddress.of(MINT),
        solAmount = BigInteger(solAmount),
        tokenAmount = BigInteger(tokenAmount),
        feeLamports = BigInteger.ZERO,
        creatorFeeLamports = BigInteger.ZERO,
        feeBps = BigInteger.ZERO,
        creatorFeeBps = BigInteger.ZERO,
        postReserves = LAUNCH_STATE.reserves,
        transactionFeeLamports = BigInteger("5000"),
        walletLamportDelta = BigInteger(solAmount).add(BigInteger("5000")).negate(),
        instruction = instruction,
    )

    private fun observation(id: String, slot: Long, curve: CurveState, mint: String = MINT) = MarketObservation(
        id = id,
        pair = MarketPair.of(MintAddress.of(mint), MintAddress.WRAPPED_SOL),
        slot = Slot.of(slot),
        priceSlot = Slot.of(slot),
        observedTime = Instant.ofEpochSecond(1791049629),
        price = BigDecimal("0.000000028"),
        liquidityQuote = null,
        source = "pumpfun-bonding-curve-trade/v1",
        sourceEventId = id,
        commitment = Commitment.CONFIRMED,
        quality = ObservationQuality.LAST_TRADE,
        curveComplete = false,
        curve = curve,
    )

    private companion object {
        const val SIGNATURE_A = "1111111111111111111111111111111111111111111111111111111111111112"
        const val SIGNATURE_B = "1111111111111111111111111111111111111111111111111111111111111113"
        const val SIGNATURE_C = "1111111111111111111111111111111111111111111111111111111111111114"
        const val OTHER_MINT = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"

        /** A fresh curve: 30 SOL and 1,073,000,000 tokens virtual, 793,100,000 tokens real. */
        val LAUNCH_STATE = CurveState(CurveReserves(BigInteger("30000000000"), BigInteger("1073000000000000"),
            BigInteger.ZERO, BigInteger("793100000000000")), feeBps = 95, creatorFeeBps = 30)

        /** The launch curve after a 1 SOL buy. */
        val OTHER_STATE = CurveState(CurveReserves(BigInteger("31000000000"), BigInteger("1038387096774194"),
            BigInteger("1000000000"), BigInteger("758487096774194")), feeBps = 95, creatorFeeBps = 30)

        /** Decu's real captured buy, as the decoder reads it. */
        val REAL = PumpFunBuyFacts(
            signature = TransactionSignature.of(SIGNATURE),
            slot = Slot.of(SLOT),
            mint = MintAddress.of(MINT),
            solAmount = BigInteger("244444444"),
            tokenAmount = BigInteger("1238520682656"),
            feeLamports = BigInteger("2322223"),
            creatorFeeLamports = BigInteger("733334"),
            feeBps = BigInteger("95"),
            creatorFeeBps = BigInteger("30"),
            postReserves = CurveReserves(BigInteger("79829770982"), BigInteger("403233026450985"),
                BigInteger("49829770982"), BigInteger("123333026450985")),
            transactionFeeLamports = BigInteger("5005000"),
            walletLamportDelta = BigInteger("-265005001"),
            instruction = PumpFunBuyInstruction.BUY,
        )

        /** Decu's captured `buy_exact_quote_in_v3`, signature 26jumCkd…, as the decoder reads it. */
        val REAL_V3 = PumpFunBuyFacts(
            signature = TransactionSignature.of("26jumCkdDKmagU9CTARuecYGp2zofCQm5kPWA1sXijWMtLMHB5tZJo2Vn478zRmysZ81izXzM9YAX3qevYAatJWq"),
            slot = Slot.of(455091775),
            mint = MintAddress.of("6LChGSaKeBUoZGkiNc8z3ZB2rttv7pg1XBPaQJ2rpump"),
            solAmount = BigInteger("244444443"),
            tokenAmount = BigInteger("2013975096834"),
            feeLamports = BigInteger("2322223"),
            creatorFeeLamports = BigInteger("733334"),
            feeBps = BigInteger("95"),
            creatorFeeBps = BigInteger("30"),
            postReserves = CurveReserves(BigInteger("62628603653"), BigInteger("513982408672964"),
                BigInteger("32628603653"), BigInteger("234082408672964")),
            transactionFeeLamports = BigInteger("55000"),
            walletLamportDelta = BigInteger("-250105000"),
            instruction = PumpFunBuyInstruction.BUY_EXACT_QUOTE_IN_V3,
        )
    }
}
