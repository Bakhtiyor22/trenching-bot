package com.trenching.bot.replay

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketHistoryCaptureConfiguration
import com.trenching.bot.marketdata.MarketHistoryCaptureResult
import com.trenching.bot.marketdata.MarketHistoryDecodeResult
import com.trenching.bot.marketdata.MarketObservationExclusion
import com.trenching.bot.marketdata.MarketTransactionOutcome
import com.trenching.bot.marketdata.MarketTransactionRecord
import com.trenching.bot.marketdata.MarketWindowCoverage
import com.trenching.bot.marketdata.RecordedMarketHistory
import com.trenching.bot.marketdata.infrastructure.capture.MarketHistoryArtifactStore
import com.trenching.bot.replay.RecordedWalletFixtures.MINT
import com.trenching.bot.replay.RecordedWalletFixtures.SIGNATURE
import com.trenching.bot.replay.RecordedWalletFixtures.SLOT
import com.trenching.bot.replay.RecordedWalletFixtures.WALLET
import com.trenching.bot.replay.application.DatasetFingerprint
import com.trenching.bot.replay.application.RecordedReplayExportService
import com.trenching.bot.replay.domain.RecordedReplayExportRequest
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.walletactivity.WalletHistoryCaptureConfiguration
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper

class RecordedReplayExportTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private var marketDecodes = 0

    @Test
    fun `exports the real recorded buy and in-window trade as canonical v1 json that the loader round-trips`() {
        RecordedWalletFixtures.save(mapper, wallet())
        saveRealMarketCapture()

        val export = service().export(request())

        val json = Files.readString(output())
        val dataset = Files.newInputStream(output()).use { ReplayDatasetLoader(mapper).load(it) }
        assertEquals(WalletAddress.of(WALLET), dataset.trackedWallet)
        val buy = dataset.transactions.single()
        assertEquals(TransactionSignature.of(SIGNATURE), buy.signature)
        assertEquals(BigInteger("1238520682656"), buy.baseDelta)
        assertEquals(BigInteger("-244444444"), buy.quoteDelta)
        assertEquals("pumpfun-bonding-curve-buy/v1", buy.source)
        val observation = dataset.observations.single()
        assertEquals(Slot.of(SLOT + 1), observation.slot)
        assertEquals(BigDecimal("0.00000019870174082519143559411678139870"), observation.price)
        assertEquals("pumpfun-bonding-curve-trade/v1:$TRADE:4:15:$MINT", observation.id)
        assertEquals(DatasetFingerprint.checksum(dataset), export.checksum)
        assertEquals(export.checksum, mapper.readTree(json).path("checksum").asString())
        assertEquals(listOf(1, 1, 1, 1), listOf(export.sourceBuys, export.windows, export.transactions, export.observations))
        assertEquals(output(), export.output)
        assertEquals(WalletAddress.of(WALLET), export.trackedWallet)
        assertTrue(export.written)
    }

    @Test
    fun `a repeated export is byte identical and leaves the existing file in place`() {
        RecordedWalletFixtures.save(mapper, wallet())
        saveRealMarketCapture()
        val first = service().export(request())
        val bytes = Files.readAllBytes(output())

        val second = service().export(request())

        assertEquals(first.checksum, second.checksum)
        assertFalse(second.written)
        assertTrue(bytes.contentEquals(Files.readAllBytes(output())))
    }

    @Test
    fun `refuses to replace a different existing file`() {
        RecordedWalletFixtures.save(mapper, wallet())
        saveRealMarketCapture()
        Files.writeString(output(), "previous dataset")

        assertCategory("OUTPUT_EXISTS") { service().export(request()) }

        assertEquals("previous dataset", Files.readString(output()))
    }

    @Test
    fun `refuses a market capture whose windows or parameters differ from the wallet capture's plan`() {
        RecordedWalletFixtures.save(mapper, wallet())
        val shifted = ReplayMarketFixtures.manifest(listOf(window(SLOT + 2, SLOT + 12)))
        assertCategory("MARKET_CAPTURE_MISMATCH") { service(ReplayMarketFixtures.decoded(markets(), shifted, 1)).export(request()) }
        val planned = ReplayMarketFixtures.manifest(listOf(window(SLOT + 1, SLOT + 12)))
        val otherParameters = ReplayMarketFixtures.decoded(markets(), planned, 1)
            .copy(windowParameters = ReplayMarketFixtures.PARAMETERS.copy(holdingSlots = 6))
        assertCategory("MARKET_CAPTURE_MISMATCH") { service(otherParameters).export(request()) }
        assertFalse(Files.exists(output()))
    }

    @Test
    fun `refuses an incomplete market capture or a window that yields no observations`() {
        RecordedWalletFixtures.save(mapper, wallet())
        val planned = ReplayMarketFixtures.decoded(markets(), ReplayMarketFixtures.manifest(listOf(window(SLOT + 1, SLOT + 12))), 1)
        assertCategory("MARKET_CAPTURE_INCOMPLETE") { service(planned.copy(complete = false)).export(request()) }
        val gap = planned.copy(windows = planned.windows.map { it.copy(detailGaps = 1, yieldsObservations = false) })
        assertCategory("MARKET_CAPTURE_INCOMPLETE") { service(gap).export(request()) }
        assertFalse(Files.exists(output()))
    }

    @Test
    fun `refuses in-window exclusions but not trades outside the window or quoted in the quote mint`() {
        RecordedWalletFixtures.save(mapper, wallet())
        val planned = ReplayMarketFixtures.decoded(markets(), ReplayMarketFixtures.manifest(listOf(window(SLOT + 1, SLOT + 12))), 1)
        val blocking = MarketObservationExclusion.entries -
            setOf(MarketObservationExclusion.OUT_OF_WINDOW, MarketObservationExclusion.QUOTE_MINT)
        blocking.forEach { reason ->
            val excluded = planned.copy(exclusions = planned.exclusions + (reason to 1))
            assertCategory("MARKET_CAPTURE_EXCLUSIONS") { service(excluded).export(request()) }
        }
        assertFalse(Files.exists(output()))
        val outside = planned.copy(exclusions = planned.exclusions +
            mapOf(MarketObservationExclusion.OUT_OF_WINDOW to 4, MarketObservationExclusion.QUOTE_MINT to 1))
        assertEquals(1, service(outside).export(request()).observations)
    }

    @Test
    fun `refuses a recorded market directory that is unfinished or stopped at its page bound`() {
        RecordedWalletFixtures.save(mapper, wallet())
        val store = MarketHistoryArtifactStore(mapper)
        val planned = listOf(window(SLOT + 1, SLOT + 12))
        store.save(markets(), ReplayMarketFixtures.manifest(planned, firstCoverage = MarketWindowCoverage.PENDING))
        assertCategory("CAPTURE_INCOMPLETE") { service().export(request()) }
        store.save(markets(), ReplayMarketFixtures.manifest(planned, firstCoverage = MarketWindowCoverage.COVERAGE_INCOMPLETE))
        assertCategory("MARKET_CAPTURE_INCOMPLETE") { service().export(request()) }
        assertFalse(Files.exists(output()))
    }

    @Test
    fun `refuses an incomplete wallet capture before reading the market capture`() {
        RecordedWalletFixtures.save(mapper, wallet(), complete = false)
        assertCategory("WALLET_CAPTURE_INCOMPLETE") { service(ReplayMarketFixtures.decoded(markets(),
            ReplayMarketFixtures.manifest(listOf(window(SLOT + 1, SLOT + 12))), 1)).export(request()) }
        assertEquals(0, marketDecodes)
        assertFalse(Files.exists(output()))
    }

    @Test
    fun `refuses more observations than the replay format holds instead of truncating them`() {
        RecordedWalletFixtures.save(mapper, wallet())
        val tooMany = ReplayMarketFixtures.decoded(markets(), ReplayMarketFixtures.manifest(listOf(window(SLOT + 1, SLOT + 12))), 10_001)
        assertCategory("DATASET_BOUNDS") { service(tooMany).export(request()) }
        assertFalse(Files.exists(output()))
    }

    private fun saveRealMarketCapture() {
        val store = MarketHistoryArtifactStore(mapper)
        val transaction = javaClass.getResourceAsStream("/marketdata/pumpfun-trade-453004997.json").use { mapper.readTree(requireNotNull(it)) }
        val artifact = store.writeTransaction(markets(), TRADE, transaction)
        val record = MarketTransactionRecord(TRADE, SLOT + 1, MarketTransactionOutcome.CAPTURED, artifact.path, artifact.sha256, artifact.bytes)
        store.save(markets(), ReplayMarketFixtures.manifest(listOf(window(SLOT + 1, SLOT + 12)), listOf(record)))
    }

    private fun service(market: MarketHistoryDecodeResult? = null): RecordedReplayExportService {
        val recorded = MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper)
        val markets = object : RecordedMarketHistory {
            override fun inspect(directory: Path): MarketHistoryCaptureResult = recorded.inspect(directory)

            override fun decode(directory: Path): MarketHistoryDecodeResult {
                marketDecodes++
                return market ?: recorded.decode(directory)
            }
        }
        return RecordedReplayExportService(WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper), markets, mapper)
    }

    private fun request() = RecordedReplayExportRequest(wallet(), markets(), output())

    private fun window(from: Long, to: Long) =
        MarketCaptureWindow.of(MintAddress.of(MINT), Slot.of(from), Slot.of(to), TransactionSignature.of(SIGNATURE))

    private fun wallet(): Path = directory.resolve("wallet")

    private fun markets(): Path = directory.resolve("markets")

    private fun output(): Path = directory.resolve("replay.json")

    private fun assertCategory(category: String, action: () -> Unit) {
        val failure = assertFailsWith<CaptureValidationException> { action() }
        assertEquals(category, failure.category)
    }

    private companion object {
        const val TRADE = "2YHkUxxjxax3y2f8efvMgzboRQ6XDMQpqck7pNpBbEawhFhgAzB32Lh7eRX7RhfkvgjARf2kHgQJWz7rZPDnhvxM"
    }
}
