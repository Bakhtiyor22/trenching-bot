package com.trenching.bot.walletactivity

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.Base58
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryArtifactStore
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

class RecordedWalletHistoryDecodeTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val store = WalletHistoryArtifactStore(mapper)
    private val recorded = WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper)
    private val wallet = WalletAddress.of("4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9")

    @Test
    fun `decode verifies the capture and reports one outcome per selected signature in selection order`() {
        val nonSigner = buy(OTHER_A, 453004990).also { walletKey(it).put("signer", false) }
        val sell = buy(OTHER_B, 453004980).also { sellDiscriminator(it) }
        val records = listOf(
            artifact(buy(SIGNATURE, SLOT), WalletHistoryOutcome.UNCLASSIFIED),
            artifact(nonSigner, WalletHistoryOutcome.NON_SIGNER),
            artifact(sell, WalletHistoryOutcome.UNCLASSIFIED),
            WalletHistoryRecord(OTHER_C, 453004970, WalletHistoryOutcome.UNAVAILABLE),
        )
        save(records)

        val result = recorded.decode(directory, wallet)

        assertEquals(directory.toAbsolutePath().normalize(), result.directory)
        assertEquals(4, result.selected)
        assertTrue(result.complete)
        assertEquals(WalletHistoryDecoderVersion.PUMPFUN_BONDING_CURVE_BUY_V2, result.decoder)
        assertEquals(listOf(
            WalletHistoryDecodeRecord(TransactionSignature.of(SIGNATURE), Slot.of(SLOT), WalletHistoryDecodeOutcome.DECODED),
            WalletHistoryDecodeRecord(TransactionSignature.of(OTHER_A), Slot.of(453004990), WalletHistoryDecodeOutcome.NOT_SIGNER),
            WalletHistoryDecodeRecord(TransactionSignature.of(OTHER_B), Slot.of(453004980), WalletHistoryDecodeOutcome.UNSUPPORTED_ROUTE),
            WalletHistoryDecodeRecord(TransactionSignature.of(OTHER_C), Slot.of(453004970), WalletHistoryDecodeOutcome.UNAVAILABLE),
        ), result.records)
        assertEquals(listOf(SIGNATURE), result.transactions.map { it.signature.value })
        assertEquals(mapOf("NO_BUY" to 1), result.rejections)
        assertEquals(mapOf("SELL" to 1), result.rejectedInstructions)
    }

    @Test
    fun `decoded transactions are ordered by slot then signature then instruction index`() {
        save(listOf(
            artifact(buy(SIGNATURE, SLOT), WalletHistoryOutcome.UNCLASSIFIED),
            artifact(buy(OTHER_B, 453004900), WalletHistoryOutcome.UNCLASSIFIED),
            artifact(buy(OTHER_A, 453004900), WalletHistoryOutcome.UNCLASSIFIED),
        ))

        val result = recorded.decode(directory, wallet)

        assertEquals(listOf(OTHER_A, OTHER_B, SIGNATURE), result.transactions.map { it.signature.value })
        assertEquals(listOf(453004900L, 453004900L, SLOT), result.transactions.map { it.slot.value })
        assertEquals(listOf(OTHER_A, OTHER_B, SIGNATURE), result.buyFacts.map { it.signature.value })
        assertEquals(listOf(453004900L, 453004900L, SLOT), result.buyFacts.map { it.slot.value })
    }

    @Test
    fun `decode reports execution facts only for the decoded buys`() {
        val sell = buy(OTHER_B, 453004980).also { sellDiscriminator(it) }
        save(listOf(
            artifact(buy(SIGNATURE, SLOT), WalletHistoryOutcome.UNCLASSIFIED),
            artifact(sell, WalletHistoryOutcome.UNCLASSIFIED),
        ))

        val facts = recorded.decode(directory, wallet).buyFacts.single()

        assertEquals(SIGNATURE, facts.signature.value)
        assertEquals(BigInteger("244444444"), facts.solAmount)
    }

    @Test
    fun `decode refuses an incomplete capture`() {
        save(listOf(
            artifact(buy(SIGNATURE, SLOT), WalletHistoryOutcome.UNCLASSIFIED),
            WalletHistoryRecord(OTHER_A, 453004900, WalletHistoryOutcome.PENDING),
        ))
        val failure = assertFailsWith<CaptureValidationException> { recorded.decode(directory, wallet) }
        assertTrue(failure.message.orEmpty().endsWith("category=CAPTURE_INCOMPLETE"), failure.message)
    }

    @Test
    fun `decode refuses a capture of a different wallet`() {
        save(listOf(artifact(buy(SIGNATURE, SLOT), WalletHistoryOutcome.UNCLASSIFIED)))
        val failure = assertFailsWith<CaptureValidationException> {
            recorded.decode(directory, WalletAddress.of("1".repeat(32)))
        }
        assertTrue(failure.message.orEmpty().endsWith("category=WALLET_MISMATCH"), failure.message)
    }

    @Test
    fun `decode runs the artifact hash verification before decoding`() {
        val record = artifact(buy(SIGNATURE, SLOT), WalletHistoryOutcome.UNCLASSIFIED)
        save(listOf(record))
        val file = directory.resolve(requireNotNull(record.artifact))
        val bytes = Files.readAllBytes(file)
        bytes[bytes.indexOfFirst { it == '7'.code.toByte() }] = '8'.code.toByte()
        Files.write(file, bytes)
        val failure = assertFailsWith<CaptureValidationException> { recorded.decode(directory, wallet) }
        assertTrue(failure.message.orEmpty().endsWith("category=ARTIFACT_HASH"), failure.message)
    }

    private fun artifact(transaction: JsonNode, outcome: WalletHistoryOutcome): WalletHistoryRecord {
        val signature = transaction.path("transaction").path("signatures").path(0).asString()
        val written = store.writeTransaction(directory, signature, transaction)
        return WalletHistoryRecord(signature, transaction.path("slot").asLong(), outcome, written.path, written.sha256, written.bytes)
    }

    private fun save(records: List<WalletHistoryRecord>) {
        val oldest = records.minBy { it.slot }
        val complete = records.none { it.outcome == WalletHistoryOutcome.PENDING }
        store.save(directory, WalletHistoryManifest(
            schemaVersion = 3, wallet = wallet.value, genesisHash = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d",
            commitment = "confirmed", capturedAt = "2026-10-03T00:00:00Z", limit = 100, scanPageLimit = 1,
            selection = records.map { WalletHistorySelection(it.signature, it.slot) }, records = records,
            complete = complete, discoveryComplete = true, discoveryStop = WalletHistoryDiscoveryStop.HISTORY_END,
            scannedSignatures = records.size, skippedFailed = 0, scanCursor = WalletHistoryScanCursor(oldest.signature, oldest.slot),
            requestsUsed = records.size + 2, bytesUsed = 1000,
        ))
    }

    private fun buy(signature: String, slot: Long): ObjectNode {
        val transaction = javaClass.getResourceAsStream("/walletactivity/decoder/pumpfun-buy.json")
            .use { mapper.readTree(requireNotNull(it)) } as ObjectNode
        (transaction.path("transaction").path("signatures") as ArrayNode).set(0, mapper.getNodeFactory().stringNode(signature))
        return transaction.put("slot", slot)
    }

    private fun walletKey(transaction: ObjectNode): ObjectNode =
        transaction.path("transaction").path("message").path("accountKeys").first { it.path("pubkey").asString() == wallet.value } as ObjectNode

    private fun sellDiscriminator(transaction: ObjectNode) {
        val buy = transaction.path("meta").path("innerInstructions").path(0).path("instructions").path(0) as ObjectNode
        val bytes = requireNotNull(Base58.decode(buy.path("data").asString()))
        "33e685a4017f83ad".chunked(2).forEachIndexed { index, hex -> bytes[index] = hex.toInt(16).toByte() }
        buy.put("data", Base58.encode(bytes))
    }

    private companion object {
        const val SIGNATURE = "35Vnpu9DSwADxy77FBQV8seGitMXuQRPB575AKXv1Jhh3NQSuZRKtuppUkiyLkYszxxi9HaKcmNN7fywqJ9R3mNA"
        const val SLOT = 453004996L
        val OTHER_A = "1".repeat(63) + "2"
        val OTHER_B = "1".repeat(63) + "3"
        val OTHER_C = "1".repeat(63) + "4"
    }
}
