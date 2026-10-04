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
import com.trenching.bot.marketdata.MarketHistoryCapture
import com.trenching.bot.marketdata.MarketHistoryCaptureRequest
import com.trenching.bot.marketdata.MarketHistoryCaptureResult
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
    fun `plans one window from source slot plus one through plus twelve with the default simulation parameters`() {
        val plan = planner.plan(history(buy(SIGNATURE, SLOT)))
        assertEquals(listOf(window(MINT, SLOT + 1, SLOT + 12, SIGNATURE)), plan.windows)
        assertEquals(MarketCaptureWindowParameters(firstEntryOffset = 1, lastEntryOffset = 3, maxWaitSlots = 2, maxAgeSlots = 2, holdingSlots = 5),
            plan.parameters)
        assertEquals(1, plan.sourceBuys)
    }

    @Test
    fun `derives the window span from the simulation parameters it is given`() {
        val config = SimulationConfig.of(BigDecimal("100"), 10, 50, maxWaitSlots = 1, maxAgeSlots = 4, holdingSlots = 3)
        val plan = planner.plan(history(buy(SIGNATURE, SLOT)), config)
        assertEquals(listOf(window(MINT, SLOT + 1, SLOT + 8, SIGNATURE)), plan.windows)
        assertEquals(MarketCaptureWindowParameters(1, 3, 1, 4, 3), plan.parameters)
    }

    @Test
    fun `merges overlapping and adjacent windows of one mint and keeps the oldest source signature as the bound`() {
        val plan = planner.plan(history(
            buy(signature(1), SLOT), buy(signature(2), SLOT + 10), buy(signature(3), SLOT + 22), buy(signature(4), SLOT + 40),
            buy(signature(5), SLOT, OTHER_MINT),
        ))
        assertEquals(listOf(
            window(MINT, SLOT + 1, SLOT + 34, signature(1)),
            window(MINT, SLOT + 41, SLOT + 52, signature(4)),
            window(OTHER_MINT, SLOT + 1, SLOT + 12, signature(5)),
        ), plan.windows)
        assertEquals(5, plan.sourceBuys)
    }

    @Test
    fun `breaks a same slot tie between source buys by signature`() {
        val plan = planner.plan(history(buy(signature(9), SLOT), buy(signature(8), SLOT)))
        assertEquals(listOf(window(MINT, SLOT + 1, SLOT + 12, signature(8))), plan.windows)
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
    fun `captures the windows planned from a complete recorded wallet capture with the requested bounds`() {
        saveWalletCapture(complete = true)
        val result = service().capture(ReplayMarketCaptureRequest(directory, directory.resolve("markets"), 7, 30, 70, 1_000_000))
        val request = requireNotNull(captured)
        assertEquals(listOf(window(MINT, SLOT + 1, SLOT + 12, SIGNATURE)), request.windows)
        assertEquals(directory.resolve("markets"), request.directory)
        assertEquals(listOf(7, 30, 70), listOf(request.scanPages, request.maxTransactions, request.requestBudget))
        assertEquals(1_000_000L, request.byteBudget)
        assertEquals(1, factoryCalls)
        assertEquals(directory.resolve("markets"), result.directory)
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

    private fun service() = ReplayMarketCaptureService(
        walletHistory = WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper),
        marketCapture = {
            factoryCalls++
            object : MarketHistoryCapture {
                override fun capture(request: MarketHistoryCaptureRequest): MarketHistoryCaptureResult {
                    captured = request
                    return MarketHistoryCaptureResult(request.directory, ReplayMarketFixtures.manifest(request.windows))
                }
            }
        },
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
