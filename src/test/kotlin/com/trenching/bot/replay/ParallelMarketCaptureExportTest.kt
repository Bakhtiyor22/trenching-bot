package com.trenching.bot.replay

import com.trenching.bot.chaincapture.SolanaReadRpc
import com.trenching.bot.common.Base58
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketHistoryCaptureConfiguration
import com.trenching.bot.marketdata.MarketHistoryCaptureRequest
import com.trenching.bot.marketdata.MarketTransactionOutcome
import com.trenching.bot.marketdata.RecordedReferenceRates
import com.trenching.bot.marketdata.ReferenceRate
import com.trenching.bot.marketdata.ReferenceRateCaptureResult
import com.trenching.bot.marketdata.application.MarketHistoryCaptureService
import com.trenching.bot.marketdata.infrastructure.capture.MarketHistoryArtifactStore
import com.trenching.bot.replay.RecordedWalletFixtures.MINT
import com.trenching.bot.replay.RecordedWalletFixtures.SIGNATURE
import com.trenching.bot.replay.RecordedWalletFixtures.SLOT
import com.trenching.bot.replay.application.RecordedReplayExportService
import com.trenching.bot.replay.domain.RecordedReplayExportRequest
import com.trenching.bot.walletactivity.WalletHistoryCaptureConfiguration
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class ParallelMarketCaptureExportTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `a market capture fetched at concurrency 4 exports the same v3 dataset and checksum as one fetched at concurrency 1`() {
        RecordedWalletFixtures.save(mapper, directory.resolve("wallet"))
        val sequential = captureAndExport("one", concurrency = 1)
        val parallel = captureAndExport("four", concurrency = 4)

        assertEquals(sequential.checksum, parallel.checksum)
        assertEquals(Files.readString(sequential.output), Files.readString(parallel.output))
        assertEquals(1, parallel.observations)
        assertEquals(
            Files.readAllBytes(directory.resolve("one/market-manifest.json")).toList(),
            Files.readAllBytes(directory.resolve("four/market-manifest.json")).toList(),
        )
        val manifest = MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper).inspect(directory.resolve("four")).manifest
        assertTrue(manifest.complete)
        assertEquals(UNRELATED_TRANSACTIONS + 1, manifest.records.count { it.outcome == MarketTransactionOutcome.CAPTURED })
    }

    private fun captureAndExport(name: String, concurrency: Int): com.trenching.bot.replay.domain.RecordedReplayExport {
        val markets = directory.resolve(name)
        val real = javaClass.getResourceAsStream("/marketdata/pumpfun-trade-453004997.json").use { mapper.readTree(requireNotNull(it)) }
        val unrelated = (1..UNRELATED_TRANSACTIONS).map { Base58.encode(ByteArray(64).also { bytes -> bytes[63] = it.toByte() }) }
        val rpc = SolanaReadRpc { method, params, budget ->
            budget.request()
            when (method) {
                "getGenesisHash" -> mapper.readTree("\"$GENESIS\"")
                "getSignaturesForAddress" -> mapper.readTree((unrelated.mapIndexed { index, signature -> entry(signature, SLOT + 4000 - index) } +
                    entry(TRADE, SLOT + 1)).joinToString(",", "[", "]"))
                "getTransaction" -> {
                    val signature = params[0] as String
                    Thread.sleep(if (signature == TRADE) 0 else 5)
                    if (signature == TRADE) real else unrelatedTransaction(signature, SLOT + 4000 - unrelated.indexOf(signature))
                }
                else -> error("Unexpected method $method")
            }
        }
        val window = MarketCaptureWindow.of(MintAddress.of(MINT), Slot.of(SLOT + 1), Slot.of(SLOT + 4500), TransactionSignature.of(SIGNATURE))
        MarketHistoryCaptureService(rpc, MarketHistoryArtifactStore(mapper), CLOCK, concurrency)
            .capture(MarketHistoryCaptureRequest(markets, listOf(window), ReplayMarketFixtures.PARAMETERS, 20, 300, 400, 104_857_600))
        val recordedRates = object : RecordedReferenceRates {
            override fun inspect(directory: Path): ReferenceRateCaptureResult = error("Export reads rates, not their audit")
            override fun rates(directory: Path): List<ReferenceRate> = listOf(RATE)
        }
        return RecordedReplayExportService(
            WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper), MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper),
            recordedRates, mapper,
        ).export(RecordedReplayExportRequest(directory.resolve("wallet"), markets, directory.resolve("rates"), directory.resolve("$name-replay.json")))
    }

    private fun entry(signature: String, slot: Long) =
        """{"signature":"$signature","slot":$slot,"err":null,"memo":null,"blockTime":1791049629}"""

    private fun unrelatedTransaction(signature: String, slot: Long): JsonNode = mapper.readTree(
        """{"slot":$slot,"blockTime":1791049629,"version":1,"meta":{"err":null,"fee":5000,"preBalances":[1000000],"postBalances":[995000],"logMessages":[]},""" +
            """"transaction":{"signatures":["$signature"],"message":{"accountKeys":[{"pubkey":"$MINT","signer":true,"writable":true}],""" +
            """"instructions":[{"programId":"$MINT","accounts":[],"data":""}],"recentBlockhash":"$MINT"}}}""")

    private companion object {
        const val GENESIS = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"
        const val TRADE = "2YHkUxxjxax3y2f8efvMgzboRQ6XDMQpqck7pNpBbEawhFhgAzB32Lh7eRX7RhfkvgjARf2kHgQJWz7rZPDnhvxM"
        const val UNRELATED_TRANSACTIONS = 12
        val RATE = ReferenceRate(Instant.parse("2026-10-03T17:47:00Z"), BigDecimal("150"), "test-fixture:SOLUSDT:1m:open")
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC)
    }
}
