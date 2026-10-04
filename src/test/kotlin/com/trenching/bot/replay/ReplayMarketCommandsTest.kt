package com.trenching.bot.replay

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketHistoryCapture
import com.trenching.bot.marketdata.MarketHistoryCaptureConfiguration
import com.trenching.bot.marketdata.MarketHistoryCaptureRequest
import com.trenching.bot.marketdata.MarketHistoryCaptureResult
import com.trenching.bot.marketdata.MarketTransactionOutcome
import com.trenching.bot.marketdata.MarketTransactionRecord
import com.trenching.bot.marketdata.MarketWindowCoverage
import com.trenching.bot.marketdata.infrastructure.capture.MarketHistoryArtifactStore
import com.trenching.bot.replay.api.ReplayMarketCommands
import com.trenching.bot.replay.application.ReplayMarketCaptureService
import com.trenching.bot.replay.domain.ReplayMarketCaptureRequest
import com.trenching.bot.walletactivity.WalletHistoryCaptureConfiguration
import com.trenching.bot.walletactivity.WalletHistoryDiscoveryStop
import com.trenching.bot.walletactivity.WalletHistoryManifest
import com.trenching.bot.walletactivity.WalletHistoryOutcome
import com.trenching.bot.walletactivity.WalletHistoryRecord
import com.trenching.bot.walletactivity.WalletHistoryScanCursor
import com.trenching.bot.walletactivity.WalletHistorySelection
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryArtifactStore
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper

class ReplayMarketCommandsTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val output = ByteArrayOutputStream()
    private val errors = ByteArrayOutputStream()
    private var captured: ReplayMarketCaptureRequest? = null
    private var inspected: Path? = null
    private var decodes = 0

    @Test
    fun `capture rejects missing unknown repeated and out of range arguments before any planning`() {
        val base = arrayOf("--wallet-capture-directory", wallet().toString(), "--directory", markets().toString())
        val invalid = listOf(emptyArray(), arrayOf("--directory", markets().toString()),
            arrayOf("--wallet-capture-directory", wallet().toString()), base + arrayOf("--endpoint", "private"),
            base + arrayOf("--directory", markets().toString()), base + arrayOf("--scan-pages", "0"), base + arrayOf("--scan-pages", "51"),
            base + arrayOf("--scan-pages", "many"), base + arrayOf("--max-transactions", "0"), base + arrayOf("--max-transactions", "1001"),
            base + arrayOf("--request-budget", "0"), base + arrayOf("--request-budget", "1001"),
            base + arrayOf("--byte-budget", "0"), base + arrayOf("--byte-budget", "524288001"),
            arrayOf("--wallet-capture-directory", wallet().toString(), "--directory", wallet().toString()))
        invalid.forEach { args -> assertEquals(2, commands().capture(args), args.joinToString(" ")) }
        assertEquals(null, captured)
        assertTrue(errors.toString().contains("category=INVALID_ARGUMENTS"))
    }

    @Test
    fun `capture forwards directories and explicit bounds`() {
        assertEquals(0, commands().capture(arrayOf("--wallet-capture-directory", wallet().toString(), "--directory", markets().toString(),
            "--scan-pages", "7", "--max-transactions", "30", "--request-budget", "70", "--byte-budget", "1000000")))
        assertEquals(ReplayMarketCaptureRequest(wallet(), markets(), 7, 30, 70, 1_000_000), captured)
    }

    @Test
    fun `capture defaults to twenty pages three hundred transactions and the bounded budgets`() {
        assertEquals(0, commands().capture(arrayOf("--wallet-capture-directory", wallet().toString(), "--directory", markets().toString())))
        assertEquals(ReplayMarketCaptureRequest(wallet(), markets(), 20, 300, 400, 104857600), captured)
    }

    @Test
    fun `capture prints window coverage counts and usage and never decodes`() {
        assertEquals(0, commands().capture(arrayOf("--wallet-capture-directory", wallet().toString(), "--directory", markets().toString())))
        val lines = output.toString().lines()
        assertTrue(lines.contains("window=$MINT fromSlot=453004997 toSlot=453005008 coverage=COMPLETE scanned=1003 skippedFailed=1000 skippedNewer=2 selected=1"), output.toString())
        assertTrue(lines.contains("windows=1 scanPages=20 maxTransactions=300 acquisitionComplete=true complete=true"))
        assertTrue(lines.contains("entryOffsets=1..3 maxWaitSlots=2 maxAgeSlots=2 holdingSlots=5"))
        assertTrue(lines.contains("requestsUsed=5 bytesUsed=4096"))
        assertTrue(lines.contains("transaction.CAPTURED=1"))
        assertTrue(lines.contains("transaction.WITHHELD_UNSAFE_TEXT=0"))
        assertTrue(lines.contains("replayReady=false extractorStatus=NOT_RUN"))
        assertTrue(lines.contains("manifest=${markets().resolve("market-manifest.json")}"))
        assertEquals(0, decodes)
    }

    @Test
    fun `an incomplete capture reports its gaps and fails`() {
        val commands = commands(coverage = MarketWindowCoverage.COVERAGE_INCOMPLETE)
        assertEquals(1, commands.capture(arrayOf("--wallet-capture-directory", wallet().toString(), "--directory", markets().toString())))
        assertTrue(output.toString().contains("coverage=COVERAGE_INCOMPLETE"))
        assertTrue(output.toString().contains("complete=false"))
        assertTrue(errors.toString().lines().contains("operation=captureReplayMarkets category=CAPTURE_INCOMPLETE"))
    }

    @Test
    fun `the wallet capture prerequisite is refused before the credential consuming adapter is created`() {
        WalletHistoryArtifactStore(mapper).save(wallet(), WalletHistoryManifest(
            schemaVersion = 2, wallet = WALLET, genesisHash = GENESIS, commitment = "confirmed", capturedAt = "2026-10-03T00:00:00Z",
            limit = 100, scanPageLimit = 1, selection = listOf(WalletHistorySelection(SIGNATURE, SLOT)),
            records = listOf(WalletHistoryRecord(SIGNATURE, SLOT, WalletHistoryOutcome.PENDING)),
            complete = false, discoveryComplete = true, discoveryStop = WalletHistoryDiscoveryStop.HISTORY_END,
            scannedSignatures = 1, skippedFailed = 0, scanCursor = WalletHistoryScanCursor(SIGNATURE, SLOT), requestsUsed = 2, bytesUsed = 0))
        var adapters = 0
        val service = ReplayMarketCaptureService(WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper), {
            adapters++
            object : MarketHistoryCapture {
                override fun capture(request: MarketHistoryCaptureRequest): MarketHistoryCaptureResult = error("Must not capture")
            }
        })
        val commands = ReplayMarketCommands(service::capture, { error("Must not inspect") }, { error("Must not decode") },
            PrintStream(output), PrintStream(errors))

        assertEquals(1, commands.capture(arrayOf("--wallet-capture-directory", wallet().toString(), "--directory", markets().toString())))

        assertEquals(0, adapters)
        assertEquals("operation=captureReplayMarkets category=ARTIFACT_OR_CONFIGURATION_INVALID", errors.toString().lines().first())
    }

    @Test
    fun `provider exception text never reaches command output`() {
        val marker = "SANITIZED_UNSAFE_PROVIDER_MESSAGE"
        val commands = ReplayMarketCommands({ throw IllegalStateException(marker) }, { error(marker) }, { error(marker) },
            PrintStream(output), PrintStream(errors))
        assertEquals(1, commands.capture(arrayOf("--wallet-capture-directory", wallet().toString(), "--directory", markets().toString())))
        assertEquals(1, commands.inspect(arrayOf("--directory", markets().toString())))
        assertFalse(output.toString().contains(marker))
        assertFalse(errors.toString().contains(marker))
        assertTrue(errors.toString().contains("operation=inspectReplayMarkets category=UNEXPECTED_FAILURE"))
    }

    @Test
    fun `inspection of a real recorded market capture prints observations and never raw transaction json`() {
        val store = MarketHistoryArtifactStore(mapper)
        val transaction = javaClass.getResourceAsStream("/walletactivity/decoder/pumpfun-buy.json").use { mapper.readTree(requireNotNull(it)) }
        val artifact = store.writeTransaction(markets(), SIGNATURE, transaction)
        val record = MarketTransactionRecord(SIGNATURE, SLOT, MarketTransactionOutcome.CAPTURED, artifact.path, artifact.sha256, artifact.bytes)
        store.save(markets(), ReplayMarketFixtures.manifest(listOf(window(SLOT - 6, SLOT + 12)), listOf(record)))
        val recorded = MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper)
        val commands = ReplayMarketCommands({ error("Must not capture") }, recorded::inspect, recorded::decode, PrintStream(output), PrintStream(errors))

        assertEquals(0, commands.inspect(arrayOf("--directory", markets().toString())))

        val printed = output.toString()
        val lines = printed.lines()
        assertTrue(lines.contains("replayReady=false extractorStatus=PUMPFUN_BONDING_CURVE_TRADE_V1"), printed)
        assertTrue(lines.contains("observations=1"))
        assertTrue(lines.contains("observation=pumpfun-bonding-curve-trade/v1:$SIGNATURE:1:7:$MINT slot=453004996 " +
            "price=0.00000019736807582073671197813450558296"), printed)
        assertTrue(lines.contains("windowYield=$MINT fromSlot=453004990 toSlot=453005008 yieldsObservations=true detailGaps=0 observations=1"))
        assertTrue(lines.contains("exclusion.OUT_OF_WINDOW=0"))
        assertTrue(lines.contains("exclusion.EXCEEDS_REPLAY_BOUNDS=0"))
        listOf("{", "}", "innerInstructions", "programId", "logMessages", "Program data").forEach { raw -> assertFalse(printed.contains(raw), raw) }
        assertEquals("", errors.toString())
    }

    @Test
    fun `inspection prints at most three observations`() {
        assertEquals(0, commands(observations = 5).inspect(arrayOf("--directory", markets().toString())))
        assertEquals(3, output.toString().lines().count { it.startsWith("observation=") })
        assertTrue(output.toString().lines().contains("observations=5"))
        assertEquals(markets(), inspected)
    }

    @Test
    fun `inspection of an unfinished capture reports gaps without decoding`() {
        val commands = ReplayMarketCommands({ error("Must not capture") },
            { MarketHistoryCaptureResult(it, ReplayMarketFixtures.manifest(listOf(window(SLOT + 1, SLOT + 12)), firstCoverage = MarketWindowCoverage.PENDING)) },
            { error("Must not decode") }, PrintStream(output), PrintStream(errors))
        assertEquals(0, commands.inspect(arrayOf("--directory", markets().toString())))
        assertTrue(output.toString().lines().contains("replayReady=false extractorStatus=CAPTURE_INCOMPLETE"))
        assertFalse(output.toString().contains("observation="))
    }

    @Test
    fun `inspection rejects capture options and help prints usage without touching artifacts`() {
        assertEquals(2, commands().inspect(arrayOf("--directory", markets().toString(), "--scan-pages", "3")))
        assertEquals(0, commands().capture(arrayOf("--help")))
        assertEquals(0, commands().inspect(arrayOf("--help")))
        assertEquals(null, inspected)
        assertEquals(null, captured)
        assertTrue(output.toString().contains("--wallet-capture-directory <path>"))
        assertTrue(output.toString().contains("Usage: inspectReplayMarkets --directory <path>"))
    }

    private fun commands(coverage: MarketWindowCoverage = MarketWindowCoverage.COMPLETE, observations: Int = 1): ReplayMarketCommands {
        val record = MarketTransactionRecord(SIGNATURE, SLOT + 2, MarketTransactionOutcome.CAPTURED, "transactions/$SIGNATURE.json", "0".repeat(64), 10)
        val manifest = ReplayMarketFixtures.manifest(listOf(window(SLOT + 1, SLOT + 12)), listOf(record), coverage)
        return ReplayMarketCommands(
            capture = { request -> captured = request; MarketHistoryCaptureResult(request.directory, manifest) },
            inspect = { path -> inspected = path; MarketHistoryCaptureResult(path, manifest) },
            decode = { path -> decodes++; ReplayMarketFixtures.decoded(path, manifest, observations) },
            output = PrintStream(output),
            errors = PrintStream(errors),
        )
    }

    private fun window(from: Long, to: Long) =
        MarketCaptureWindow.of(MintAddress.of(MINT), Slot.of(from), Slot.of(to), TransactionSignature.of(SIGNATURE))

    private fun wallet(): Path = directory.resolve("wallet").toAbsolutePath().normalize()

    private fun markets(): Path = directory.resolve("markets").toAbsolutePath().normalize()

    private companion object {
        const val GENESIS = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"
        const val WALLET = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
        const val SIGNATURE = "35Vnpu9DSwADxy77FBQV8seGitMXuQRPB575AKXv1Jhh3NQSuZRKtuppUkiyLkYszxxi9HaKcmNN7fywqJ9R3mNA"
        const val MINT = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
        const val SLOT = 453004996L
    }
}
