package com.trenching.bot.replay

import com.trenching.bot.common.Commitment
import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.replay.api.WalletHistoryCommands
import com.trenching.bot.walletactivity.WalletHistoryCapture
import com.trenching.bot.walletactivity.WalletHistoryCaptureConfiguration
import com.trenching.bot.walletactivity.WalletHistoryCaptureLimits
import com.trenching.bot.walletactivity.WalletHistoryCaptureRequest
import com.trenching.bot.walletactivity.WalletHistoryCaptureResult
import com.trenching.bot.walletactivity.WalletHistoryDecodeOutcome
import com.trenching.bot.walletactivity.WalletHistoryDecodeRecord
import com.trenching.bot.walletactivity.WalletHistoryDecodeResult
import com.trenching.bot.walletactivity.WalletHistoryDecoderVersion
import com.trenching.bot.walletactivity.WalletHistoryDiscoveryStop
import com.trenching.bot.walletactivity.WalletHistoryManifest
import com.trenching.bot.walletactivity.WalletHistoryOutcome
import com.trenching.bot.walletactivity.WalletHistoryRecord
import com.trenching.bot.walletactivity.WalletHistoryScanCursor
import com.trenching.bot.walletactivity.WalletHistorySelection
import com.trenching.bot.walletactivity.domain.WalletTransaction
import com.trenching.bot.walletactivity.WalletHistoryScope
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryArtifactStore
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper

class WalletHistoryCommandsTest {
    @TempDir
    lateinit var directory: Path

    private val wallet = "11111111111111111111111111111111"
    private val output = ByteArrayOutputStream()
    private val errors = ByteArrayOutputStream()
    private var factoryCalls = 0
    private var captured: WalletHistoryCaptureRequest? = null
    private var inspected: Path? = null
    private var decoded: Pair<Path, WalletAddress>? = null

    @Test
    fun `capture validates arguments before creating a credential consuming adapter`() {
        val commands = commands()
        assertEquals(2, commands.capture(arrayOf("--wallet", "invalid", "--directory", directory.toString())))
        assertEquals(0, factoryCalls)
        assertEquals(null, captured)
        assertTrue(errors.toString().contains("INVALID_ARGUMENTS"))
    }

    @Test
    fun `capture forwards public wallet directory and explicit limits`() {
        assertEquals(0, commands().capture(arrayOf("--wallet", wallet, "--directory", directory.toString(),
            "--limit", "25", "--scan-pages", "7", "--request-budget", "70", "--byte-budget", "1000000")))
        assertEquals(1, factoryCalls)
        val request = requireNotNull(captured)
        assertEquals(wallet, request.wallet.value)
        assertEquals(directory.toAbsolutePath().normalize(), request.directory)
        assertEquals(25, request.limit)
        assertEquals(7, request.scanPages)
        assertEquals(70, request.requestBudget)
        assertEquals(1000000L, request.byteBudget)
        assertTrue(output.toString().contains("selected=1"))
        assertTrue(output.toString().contains("UNCLASSIFIED=1"))
        assertTrue(output.toString().contains("WITHHELD_UNSAFE_TEXT=0"))
        assertTrue(output.toString().contains("replayReady=false"))
        assertTrue(output.toString().contains("scope=LATEST_SUCCESSFUL_WALLET_REFERENCES"))
        assertTrue(output.toString().lines().contains("scanned=1500 skippedFailed=1499 scanPages=2 discoveryStop=HISTORY_END"))
    }

    @Test
    fun `capture defaults to latest one hundred transactions with bounded budgets`() {
        assertEquals(0, commands().capture(arrayOf("--wallet", wallet, "--directory", directory.toString())))
        val request = requireNotNull(captured)
        assertEquals(100, request.limit)
        assertEquals(20, request.scanPages)
        assertEquals(400, request.requestBudget)
        assertEquals(104857600L, request.byteBudget)
    }

    @Test
    fun `capture rejects missing unknown repeated and out of range arguments without constructing provider`() {
        val base = arrayOf("--wallet", wallet, "--directory", directory.toString())
        val invalid = listOf(emptyArray(), arrayOf("--wallet"), base + arrayOf("--endpoint", "private"),
            base + arrayOf("--wallet", wallet), base + arrayOf("--limit", "0"),
            base + arrayOf("--limit", "${WalletHistoryCaptureLimits.MAX_TRANSACTIONS + 1}"), base + arrayOf("--request-budget", "0"),
            base + arrayOf("--byte-budget", "-1"), base + arrayOf("--byte-budget", "overflow"),
            base + arrayOf("--scan-pages", "0"), base + arrayOf("--scan-pages", "51"), base + arrayOf("--scan-pages", "many"))
        invalid.forEach { assertEquals(2, commands().capture(it)) }
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `capture selects the scope option and prints the scope and listed address`() {
        val base = arrayOf("--wallet", wallet, "--directory", directory.toString())
        assertEquals(0, commands().capture(base))
        assertEquals(WalletHistoryScope.WALLET_REFERENCES, requireNotNull(captured).scope)
        assertTrue(output.toString().lines().contains(
            "scope=LATEST_SUCCESSFUL_WALLET_REFERENCES wallet=$wallet commitment=confirmed listingAddress=$wallet"))
        assertEquals(0, commands().capture(base + arrayOf("--scope", "wallet-references")))
        assertEquals(WalletHistoryScope.WALLET_REFERENCES, requireNotNull(captured).scope)
        output.reset()
        assertEquals(0, commands().capture(base + arrayOf("--scope", "pumpfun-user-buys")))
        assertEquals(WalletHistoryScope.PUMPFUN_USER_VOLUME_ACCUMULATOR, requireNotNull(captured).scope)
        assertTrue(output.toString().lines().contains(
            "scope=LATEST_SUCCESSFUL_PUMPFUN_USER_ACCUMULATOR_REFERENCES wallet=$wallet commitment=confirmed listingAddress=$ACCUMULATOR"))
        assertTrue(output.toString().contains("WALLET_ABSENT=0"))
    }

    @Test
    fun `capture rejects an unknown or repeated scope without constructing provider`() {
        val base = arrayOf("--wallet", wallet, "--directory", directory.toString())
        listOf(base + arrayOf("--scope", "all"), base + arrayOf("--scope", "PUMPFUN_USER_VOLUME_ACCUMULATOR"),
            base + arrayOf("--scope", "pumpfun-user-buys", "--scope", "pumpfun-user-buys")).forEach {
            assertEquals(2, commands().capture(it))
        }
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `capture rejects oversized positive budgets before consuming provider configuration`() {
        val base = arrayOf("--wallet", wallet, "--directory", directory.toString())
        assertEquals(2, commands().capture(base + arrayOf("--request-budget", "1001")))
        assertEquals(2, commands().capture(base + arrayOf("--byte-budget", "524288001")))
        assertEquals(0, factoryCalls)
        assertEquals(null, captured)
    }

    @Test
    fun `provider exception text and nested causes never reach command output`() {
        val marker = "SANITIZED_UNSAFE_PROVIDER_MESSAGE"
        val commands = commands(captureFailure = IllegalStateException(marker, IllegalArgumentException(marker)))
        assertEquals(1, commands.capture(arrayOf("--wallet", wallet, "--directory", directory.toString())))
        assertFalse(output.toString().contains(marker))
        assertFalse(errors.toString().contains(marker))
        assertTrue(errors.toString().contains("UNEXPECTED_FAILURE"))
    }

    @Test
    fun `incomplete acquisition reports gaps and returns failure instead of advertising completion`() {
        assertEquals(1, commands(complete = false).capture(arrayOf("--wallet", wallet, "--directory", directory.toString())))
        assertTrue(output.toString().contains("complete=false"))
        assertTrue(errors.toString().contains("CAPTURE_INCOMPLETE"))
        assertTrue(output.toString().contains("discoveryStop=NONE"))
    }

    @Test
    fun `offline inspection delegates to verified artifact reader without constructing provider`() {
        assertEquals(0, commands().inspect(arrayOf("--directory", directory.toString())))
        assertEquals(directory.toAbsolutePath().normalize(), inspected)
        assertEquals(0, factoryCalls)
        assertEquals(null, captured)
        assertTrue(output.toString().contains("UNCLASSIFIED=1"))
    }

    @Test
    fun `offline inspection rejects live options before artifact access`() {
        assertEquals(2, commands().inspect(arrayOf("--directory", directory.toString(), "--wallet", wallet)))
        assertEquals(null, inspected)
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `help prints usage without constructing capture adapter or opening artifacts`() {
        val commands = commands()
        assertEquals(0, commands.capture(arrayOf("--help")))
        assertEquals(0, commands.inspect(arrayOf("--help")))
        assertEquals(0, factoryCalls)
        assertEquals(null, inspected)
        assertTrue(output.toString().contains("--request-budget"))
        assertTrue(output.toString().contains("[--scan-pages <1..50>]"))
    }

    @Test
    fun `offline inspection reports reader failure without unsafe exception details`() {
        val marker = "SANITIZED_UNSAFE_ARTIFACT_ERROR"
        val commands = WalletHistoryCommands(captureFactory = { error("Must not construct capture adapter") },
            inspect = { throw IllegalStateException(marker) }, decode = { _, _ -> error("Must not decode") },
            output = PrintStream(output), errors = PrintStream(errors))
        assertEquals(1, commands.inspect(arrayOf("--directory", directory.toString())))
        assertFalse(errors.toString().contains(marker))
        assertTrue(errors.toString().contains("UNEXPECTED_FAILURE"))
    }

    @Test
    fun `inspection lists at most three artifact references for successful signer evidence`() {
        assertEquals(0, commands(candidates = 6).inspect(arrayOf("--directory", directory.toString())))
        val candidates = output.toString().lineSequence().filter { it.startsWith("candidateSignature=") }.toList()
        assertEquals(3, candidates.size)
        assertTrue(candidates.first().contains("artifact=transactions/signature1.json"))
        assertTrue(output.toString().contains("UNCLASSIFIED=6"))
    }

    @Test
    fun `offline inspection decodes the verified capture and prints counts and at most three decoded trades`() {
        assertEquals(0, commands(decodedTrades = 5).inspect(arrayOf("--directory", directory.toString())))
        assertEquals(directory.toAbsolutePath().normalize() to WalletAddress.of(wallet), decoded)
        val lines = output.toString().lines()
        assertTrue(lines.contains("replayReady=false decoderStatus=PUMPFUN_BONDING_CURVE_BUY_V1"))
        assertTrue(lines.contains("decode.DECODED=5"))
        assertTrue(lines.contains("decode.AMBIGUOUS=0"))
        val trades = lines.filter { it.startsWith("decodedTrade=") }
        assertEquals(3, trades.size)
        assertEquals("decodedTrade=${tradeSignature(1)} slot=453004991 mint=$MINT baseRaw=1238520682656 baseDecimals=6 " +
            "quoteRaw=244444444 quoteDecimals=9 instructionIndex=1", trades.first())
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `offline inspection of an incomplete capture reports gaps without decoding`() {
        assertEquals(0, commands(complete = false).inspect(arrayOf("--directory", directory.toString())))
        assertEquals(null, decoded)
        assertTrue(output.toString().lines().contains("replayReady=false decoderStatus=CAPTURE_INCOMPLETE"))
        assertFalse(output.toString().contains("decodedTrade="))
    }

    @Test
    fun `capture never decodes and keeps its candidate summary`() {
        assertEquals(0, commands().capture(arrayOf("--wallet", wallet, "--directory", directory.toString())))
        assertEquals(null, decoded)
        assertTrue(output.toString().lines().contains("replayReady=false decoderStatus=NOT_RUN"))
        assertTrue(output.toString().contains("candidateSignature=signature1"))
        assertFalse(output.toString().contains("decodedTrade="))
    }

    @Test
    fun `inspection of a real recorded buy prints the decoded trade and never raw transaction json`() {
        val mapper = JsonMapper.builder().build()
        val transaction = javaClass.getResourceAsStream("/walletactivity/decoder/pumpfun-buy.json").use { mapper.readTree(requireNotNull(it)) }
        val store = WalletHistoryArtifactStore(mapper)
        val artifact = store.writeTransaction(directory, REAL_SIGNATURE, transaction)
        store.save(directory, WalletHistoryManifest(
            schemaVersion = 3, wallet = REAL_WALLET, genesisHash = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d",
            commitment = "confirmed", capturedAt = "2026-10-03T00:00:00Z", limit = 100, scanPageLimit = 1,
            selection = listOf(WalletHistorySelection(REAL_SIGNATURE, 453004996)),
            records = listOf(WalletHistoryRecord(REAL_SIGNATURE, 453004996, WalletHistoryOutcome.UNCLASSIFIED,
                artifact.path, artifact.sha256, artifact.bytes)),
            complete = true, discoveryComplete = true, discoveryStop = WalletHistoryDiscoveryStop.HISTORY_END,
            scannedSignatures = 1, skippedFailed = 0, scanCursor = WalletHistoryScanCursor(REAL_SIGNATURE, 453004996),
            requestsUsed = 3, bytesUsed = artifact.bytes))
        val recorded = WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper)
        val commands = WalletHistoryCommands(captureFactory = { error("Must not construct capture adapter") },
            inspect = recorded::inspect, decode = recorded::decode, output = PrintStream(output), errors = PrintStream(errors))

        assertEquals(0, commands.inspect(arrayOf("--directory", directory.toString())))

        val printed = output.toString()
        assertTrue(printed.lines().contains("decodedTrade=$REAL_SIGNATURE slot=453004996 mint=$MINT baseRaw=1238520682656 " +
            "baseDecimals=6 quoteRaw=244444444 quoteDecimals=9 instructionIndex=1"), printed)
        assertTrue(printed.lines().contains("decode.DECODED=1"))
        listOf("{", "}", "innerInstructions", "programId", "logMessages", "Program data").forEach { raw ->
            assertFalse(printed.contains(raw), raw)
        }
        assertEquals("", errors.toString())
    }

    private fun commands(complete: Boolean = true, captureFailure: Exception? = null, candidates: Int = 1, decodedTrades: Int = 1): WalletHistoryCommands =
        WalletHistoryCommands(
            captureFactory = {
                factoryCalls++
                object : WalletHistoryCapture {
                    override fun capture(request: WalletHistoryCaptureRequest): WalletHistoryCaptureResult {
                        captured = request
                        captureFailure?.let { throw it }
                        return result(complete, candidates).let {
                            if (request.scope == WalletHistoryScope.WALLET_REFERENCES) it
                            else it.copy(manifest = it.manifest.copy(scope = request.scope, listingAddress = ACCUMULATOR))
                        }
                    }
                }
            },
            inspect = { inspected = it; result(complete, candidates) },
            decode = { path, decodedWallet -> decoded = path to decodedWallet; decodeResult(decodedTrades) },
            output = PrintStream(output),
            errors = PrintStream(errors),
        )

    private fun result(complete: Boolean, candidates: Int): WalletHistoryCaptureResult = WalletHistoryCaptureResult(directory,
        WalletHistoryManifest(
            schemaVersion = 3, wallet = wallet, genesisHash = "mainnet-identity", commitment = "confirmed",
            capturedAt = "2026-10-03T00:00:00Z", limit = 100, scanPageLimit = 2,
            selection = (1..candidates).map { WalletHistorySelection("signature$it", 123L) },
            records = (1..candidates).map { WalletHistoryRecord("signature$it", 123L, WalletHistoryOutcome.UNCLASSIFIED,
                artifact = "transactions/signature$it.json") },
            complete = complete, discoveryComplete = complete,
            discoveryStop = if (complete) WalletHistoryDiscoveryStop.HISTORY_END else null,
            scannedSignatures = 1500, skippedFailed = 1499, scanCursor = WalletHistoryScanCursor("signature$candidates", 123L),
            requestsUsed = 3, bytesUsed = 500))

    private fun decodeResult(trades: Int): WalletHistoryDecodeResult {
        val transactions = (1..trades).map { index ->
            WalletTransaction(WalletAddress.of(wallet), TransactionSignature.of(tradeSignature(index)), 1, Slot.of(453004990L + index), null,
                MarketPair.of(MintAddress.of(MINT), MintAddress.WRAPPED_SOL), BigInteger("1238520682656"), BigInteger("-244444444"),
                6, 9, true, true, true, "pumpfun-bonding-curve-buy/v1", Commitment.CONFIRMED)
        }
        return WalletHistoryDecodeResult(directory, WalletAddress.of(wallet), trades, true, WalletHistoryDecoderVersion.PUMPFUN_BONDING_CURVE_BUY_V1,
            transactions, transactions.map { WalletHistoryDecodeRecord(it.signature, it.slot, WalletHistoryDecodeOutcome.DECODED) })
    }

    private fun tradeSignature(index: Int): String = "1".repeat(63) + (index + 1)

    private companion object {
        const val MINT = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
        const val REAL_WALLET = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
        const val REAL_SIGNATURE = "35Vnpu9DSwADxy77FBQV8seGitMXuQRPB575AKXv1Jhh3NQSuZRKtuppUkiyLkYszxxi9HaKcmNN7fywqJ9R3mNA"
        const val ACCUMULATOR = "7GxTMtPtJuY11YEPk9uTXrixjW4XHidUmrWRCSL9VE94"
    }
}
