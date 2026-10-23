package com.trenching.bot.replay

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.Base58
import com.trenching.bot.common.Commitment
import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketCaptureWindowParameters
import com.trenching.bot.marketdata.MarketCutoffMode
import com.trenching.bot.marketdata.MarketHistoryCaptureConfiguration
import com.trenching.bot.marketdata.MarketWindowCoverage
import com.trenching.bot.marketdata.infrastructure.capture.MarketHistoryArtifactStore
import com.trenching.bot.marketdata.MarketHistoryCapture
import com.trenching.bot.marketdata.MarketHistoryCaptureRequest
import com.trenching.bot.marketdata.MarketHistoryCaptureResult
import com.trenching.bot.marketdata.SolUsdtRateSource
import com.trenching.bot.marketdata.infrastructure.rates.DisabledSolUsdtRateSource
import com.trenching.bot.replay.application.DipOutcomeCutoff
import com.trenching.bot.replay.application.ReplayMarketCaptureService
import com.trenching.bot.replay.application.ReplayMarketWindowPlanner
import com.trenching.bot.replay.domain.ReplayMarketCaptureRequest
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.walletactivity.WalletHistoryCaptureConfiguration
import com.trenching.bot.walletactivity.WalletHistoryDecodeOutcome
import com.trenching.bot.walletactivity.WalletHistoryDecodeRecord
import com.trenching.bot.walletactivity.WalletHistoryDecodeResult
import com.trenching.bot.walletactivity.WalletHistoryDecoderVersion
import com.trenching.bot.walletactivity.domain.WalletTransaction
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper

class ReplayMarketWindowPlannerTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val planner = ReplayMarketWindowPlanner()
    private var factoryCalls = 0
    private var captured: MarketHistoryCaptureRequest? = null

    @Test
    fun `plans one window from the lead before the source buy over the horizon after it with the default simulation parameters`() {
        val plan = planner.plan(history(buy(SIGNATURE, SLOT)))
        assertEquals(listOf(window(MINT, SLOT - 50, SLOT + 4500, SIGNATURE)), plan.windows)
        assertEquals(MarketCaptureWindowParameters(horizonSlots = 4500, maxAgeSlots = 2, leadSlots = 50), plan.parameters)
        assertEquals(1, plan.sourceBuys)
    }

    @Test
    fun `the legacy lead plans the window from the slot after the source buy`() {
        val plan = planner.plan(history(buy(SIGNATURE, SLOT)), leadSlots = MarketCaptureWindowParameters.LEGACY_LEAD_SLOTS)
        assertEquals(listOf(window(MINT, SLOT + 1, SLOT + 4500, SIGNATURE)), plan.windows)
        assertEquals(MarketCaptureWindowParameters(horizonSlots = 4500, maxAgeSlots = 2), plan.parameters)
    }

    @Test
    fun `a first buy whose lead would start before slot zero is an invalid window`() {
        assertCategory("MARKET_WINDOW_INVALID") { planner.plan(history(buy(SIGNATURE, 49))) }
        assertEquals(listOf(window(MINT, 0, 4550, SIGNATURE)), planner.plan(history(buy(SIGNATURE, 50))).windows)
    }

    @Test
    fun `derives the window span from the horizon it is given`() {
        val config = SimulationConfig.of(BigDecimal("0.1"), 4, 1500, 3000, 1500, BigDecimal("10000"), 900, 1, BigDecimal("0.001"))
        val plan = planner.plan(history(buy(SIGNATURE, SLOT)), config)
        assertEquals(listOf(window(MINT, SLOT - 50, SLOT + 900, SIGNATURE)), plan.windows)
        assertEquals(MarketCaptureWindowParameters(900, 4, 50), plan.parameters)
    }

    @Test
    fun `plans one window per mint from its first buy, since later buys of a mint start no cycle`() {
        val plan = planner.plan(history(
            buy(signature(1), SLOT), buy(signature(2), SLOT + 10), buy(signature(4), SLOT + 9000),
            buy(signature(5), SLOT, OTHER_MINT),
        ))
        assertEquals(setOf(
            window(MINT, SLOT - 50, SLOT + 4500, signature(1)),
            window(OTHER_MINT, SLOT - 50, SLOT + 4500, signature(5)),
        ), plan.windows.toSet())
        assertEquals(4, plan.sourceBuys)
    }

    @Test
    fun `breaks a same slot tie between source buys by signature`() {
        val plan = planner.plan(history(buy(signature(9), SLOT), buy(signature(8), SLOT)))
        assertEquals(listOf(window(MINT, SLOT - 50, SLOT + 4500, signature(8))), plan.windows)
    }

    @Test
    fun `ignores decoded transactions the strategy would not buy`() {
        val sell = buy(signature(1), SLOT).copy(baseDelta = BigInteger("-5"), quoteDelta = BigInteger("7"))
        val otherQuote = buy(signature(2), SLOT).copy(pair = MarketPair.of(MintAddress.of(MINT), MintAddress.of(OTHER_MINT)))
        val plan = planner.plan(history(sell, otherQuote))
        assertEquals(emptyList(), plan.windows)
        assertEquals(0, plan.sourceBuys)
    }

    @Test
    fun `a new capture records the windows planned with the lead from a complete recorded wallet capture with the requested bounds`() {
        saveWalletCapture(complete = true)
        val result = service().capture(ReplayMarketCaptureRequest(directory, directory.resolve("markets"), 7, 30, 70, 1_000_000))
        val request = requireNotNull(captured)
        assertEquals(listOf(window(MINT, SLOT - 50, SLOT + 4500, SIGNATURE)), request.windows)
        assertEquals(50, request.windowParameters.leadSlots)
        assertEquals(directory.resolve("markets"), request.directory)
        assertEquals(listOf(7, 30, 70), listOf(request.scanPages, request.maxTransactions, request.requestBudget))
        assertEquals(1_000_000L, request.byteBudget)
        assertEquals(MarketCutoffMode.FULL, request.cutoffMode)
        assertEquals(null, request.cutoff)
        assertEquals(null, request.cutoffIdentity)
        assertEquals(1, factoryCalls)
        assertEquals(directory.resolve("markets"), result.directory)
    }

    @Test
    fun `resuming a capture saved before the lead plans its legacy windows and parameters again`() {
        saveWalletCapture(complete = true)
        val legacy = window(MINT, SLOT + 1, SLOT + 4500, SIGNATURE)
        MarketHistoryArtifactStore(mapper).save(directory.resolve("markets"),
            ReplayMarketFixtures.manifest(listOf(legacy), firstCoverage = MarketWindowCoverage.PENDING))

        service().capture(ReplayMarketCaptureRequest(directory, directory.resolve("markets"), 7, 30, 70, 1_000_000))

        val request = requireNotNull(captured)
        assertEquals(listOf(legacy), request.windows)
        assertEquals(ReplayMarketFixtures.PARAMETERS, request.windowParameters)
    }

    @Test
    fun `a decided capture passes the dip outcome cutoff and its identity to the market capture`() {
        saveWalletCapture(complete = true)

        service(rates = DisabledSolUsdtRateSource()).capture(ReplayMarketCaptureRequest(directory, directory.resolve("markets"), 7, 30, 70,
            1_000_000, cutoffMode = MarketCutoffMode.DECIDED))

        val request = requireNotNull(captured)
        assertEquals(MarketCutoffMode.DECIDED, request.cutoffMode)
        assertTrue(request.cutoff is DipOutcomeCutoff)
        assertEquals(DipOutcomeCutoff.IDENTITY, request.cutoffIdentity)
    }

    @Test
    fun `a decided capture without a rate source is refused before planning or creating the market capture adapter`() {
        assertCategory("RATES_NOT_CONFIGURED") {
            service().capture(ReplayMarketCaptureRequest(directory.resolve("missing"), directory.resolve("markets"), 20, 300, 400, 104857600,
                cutoffMode = MarketCutoffMode.DECIDED))
        }
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `refuses an incomplete missing tampered or buy-free wallet capture before creating the market capture adapter`() {
        assertCategory("MANIFEST_MISSING") { service().plan(directory) }
        saveWalletCapture(complete = false)
        assertCategory("WALLET_CAPTURE_INCOMPLETE") { service().plan(directory) }
        saveWalletCapture(complete = true)
        val artifact = directory.resolve("transactions/$SIGNATURE.json")
        Files.writeString(artifact, Files.readString(artifact).replaceFirst("1", "2"))
        assertCategory("ARTIFACT_HASH") { service().plan(directory) }
        val noBuys = directory.resolve("no-buys")
        saveWalletCapture(complete = true, target = noBuys, signer = false)
        assertCategory("NO_SOURCE_BUYS") {
            service().capture(ReplayMarketCaptureRequest(noBuys, directory.resolve("markets"), 20, 300, 400, 104857600))
        }
        assertEquals(0, factoryCalls)
    }

    private fun service(rates: SolUsdtRateSource? = null) = ReplayMarketCaptureService(
        walletHistory = WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper),
        markets = MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper),
        marketCapture = {
            factoryCalls++
            object : MarketHistoryCapture {
                override fun capture(request: MarketHistoryCaptureRequest): MarketHistoryCaptureResult {
                    captured = request
                    return MarketHistoryCaptureResult(request.directory, ReplayMarketFixtures.manifest(request.windows))
                }
            }
        },
        cutoffRates = rates,
    )

    private fun saveWalletCapture(complete: Boolean, target: Path = directory, signer: Boolean = true) =
        RecordedWalletFixtures.save(mapper, target, complete, signer)

    private fun history(vararg transactions: WalletTransaction) = WalletHistoryDecodeResult(
        directory, WalletAddress.of(WALLET), transactions.size, true, WalletHistoryDecoderVersion.PUMPFUN_BONDING_CURVE_BUY_V1,
        transactions.toList(), transactions.map { WalletHistoryDecodeRecord(it.signature, it.slot, WalletHistoryDecodeOutcome.DECODED) })

    private fun buy(signature: String, slot: Long, mint: String = MINT) = WalletTransaction(
        WalletAddress.of(WALLET), TransactionSignature.of(signature), 1, Slot.of(slot), null,
        MarketPair.of(MintAddress.of(mint), MintAddress.WRAPPED_SOL), BigInteger("1238520682656"), BigInteger("-244444444"),
        6, 9, true, true, true, "pumpfun-bonding-curve-buy/v1", Commitment.CONFIRMED)

    private fun window(mint: String, from: Long, to: Long, until: String) =
        MarketCaptureWindow.of(MintAddress.of(mint), Slot.of(from), Slot.of(to), TransactionSignature.of(until))

    private fun assertCategory(category: String, action: () -> Unit) {
        val failure = assertFailsWith<CaptureValidationException> { action() }
        assertTrue(failure.message.orEmpty().endsWith("category=$category"), failure.message)
    }

    private fun signature(index: Int): String = Base58.encode(ByteArray(64).also { it[63] = index.toByte() })

    private companion object {
        const val WALLET = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
        const val SIGNATURE = "35Vnpu9DSwADxy77FBQV8seGitMXuQRPB575AKXv1Jhh3NQSuZRKtuppUkiyLkYszxxi9HaKcmNN7fywqJ9R3mNA"
        const val MINT = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
        const val OTHER_MINT = "Bo82e4Upnsdg9zDeM6wPpckRyuzkLZovJucKCQ2ivUye"
        const val SLOT = 453004996L
    }
}
