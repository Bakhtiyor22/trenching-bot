package com.trenching.bot.walletactivity

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.chaincapture.SolanaReadRpc
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.application.WalletHistoryCaptureService
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryArtifactStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

class WalletHistoryScopeTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val wallet = WalletAddress.of("1".repeat(32))
    private val listed = mutableListOf<Any?>()
    private val calls = mutableListOf<String>()

    @Test
    fun `accumulator scope lists the wallet's derived Pump fun accumulator and never the wallet`() {
        val result = capture(accumulatorRequest())
        assertEquals(listOf<Any?>(ACCUMULATOR), listed)
        assertEquals(3, result.manifest.schemaVersion)
        assertEquals(WalletHistoryScope.PUMPFUN_USER_VOLUME_ACCUMULATOR, result.manifest.scope)
        assertEquals(ACCUMULATOR, result.manifest.listingAddress)
    }

    @Test
    fun `accumulator scope records a transaction without the wallet as a resolved wallet absent gap with no artifact`() {
        val result = capture(accumulatorRequest(limit = 3))
        assertEquals(listOf(WalletHistoryOutcome.UNCLASSIFIED, WalletHistoryOutcome.NON_SIGNER, WalletHistoryOutcome.WALLET_ABSENT),
            result.manifest.records.map { it.outcome })
        assertEquals(listOf(true, true, false), result.manifest.records.map { it.artifact != null })
        assertTrue(result.manifest.complete)
        assertEquals(false, Files.exists(directory.resolve("transactions/$ABSENT.json")))
        assertEquals(result.manifest, WalletHistoryArtifactStore(mapper).inspect(directory).manifest)
    }

    @Test
    fun `accumulator scope treats a transaction without the listed accumulator as contradicting its listing`() {
        val result = capture(accumulatorRequest(limit = 1)) { transaction(SIGNER, walletSigner = true, accumulator = false) }
        assertEquals(WalletHistoryOutcome.INVALID_TRANSACTION, result.manifest.records.single().outcome)
        assertEquals(false, result.manifest.complete)
    }

    @Test
    fun `reference scope still lists the wallet and treats a missing wallet as invalid`() {
        val result = capture(WalletHistoryCaptureRequest(wallet, directory, limit = 3))
        assertEquals(listOf<Any?>(wallet.value), listed)
        assertEquals(WalletHistoryScope.WALLET_REFERENCES, result.manifest.scope)
        assertEquals(wallet.value, result.manifest.listingAddress)
        assertEquals(WalletHistoryOutcome.INVALID_TRANSACTION, result.manifest.records.last().outcome)
    }

    @Test
    fun `refuses to resume a capture under another scope before any provider request`() {
        capture(accumulatorRequest(limit = 1))
        calls.clear()
        val failure = assertFailsWith<CaptureValidationException> {
            service().capture(WalletHistoryCaptureRequest(wallet, directory, limit = 1))
        }
        assertTrue(failure.message.orEmpty().endsWith("category=RESUME_IDENTITY"))
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `reads a version two manifest as the reference scope and saves it as version three on resume`() {
        val manifest = capture(WalletHistoryCaptureRequest(wallet, directory, limit = 1)).manifest
        writeManifest(versionTwo(manifest))
        val inspected = WalletHistoryArtifactStore(mapper).inspect(directory).manifest
        assertEquals(2, inspected.schemaVersion)
        assertEquals(WalletHistoryScope.WALLET_REFERENCES, inspected.scope)
        assertEquals(wallet.value, inspected.listingAddress)
        assertEquals(manifest.records, inspected.records)
        calls.clear()
        val resumed = service().capture(WalletHistoryCaptureRequest(wallet, directory, limit = 1)).manifest
        assertEquals(3, resumed.schemaVersion)
        assertEquals(listOf("getGenesisHash"), calls)
        assertEquals(3, WalletHistoryArtifactStore(mapper).inspect(directory).manifest.schemaVersion)
    }

    @Test
    fun `refuses manifests whose scope fields are forged or out of place`() {
        val valid = capture(accumulatorRequest(limit = 3)).manifest
        val forgeries = mapOf(
            "listing address of another scope" to (mapper.valueToTree<ObjectNode>(valid).put("listingAddress", wallet.value) to "LISTING_ADDRESS"),
            "unknown scope" to (mapper.valueToTree<ObjectNode>(valid).put("scope", "ALL_HISTORY") to "MANIFEST_FIELDS"),
            "missing scope" to (mapper.valueToTree<ObjectNode>(valid).apply { remove("scope") } to "MANIFEST_STRING"),
            "scope fields in version two" to (mapper.valueToTree<ObjectNode>(valid).put("schemaVersion", 2) to "MANIFEST_FIELDS"),
            "wallet absent under the reference scope" to (mapper.valueToTree<ObjectNode>(valid)
                .put("scope", WalletHistoryScope.WALLET_REFERENCES.name).put("listingAddress", wallet.value) to "RECORD_OUTCOME"),
        )
        forgeries.forEach { (name, forged) ->
            val (node, category) = forged
            writeManifest(node)
            val failure = assertFailsWith<CaptureValidationException>(name) { WalletHistoryArtifactStore(mapper).inspect(directory) }
            assertTrue(failure.message.orEmpty().endsWith("category=$category"), "$name: ${failure.message}")
        }
    }

    @Test
    fun `decoding passes a wallet absent outcome through`() {
        capture(accumulatorRequest(limit = 3))
        val decoded = WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper).decode(directory, wallet)
        assertEquals(WalletHistoryDecodeOutcome.WALLET_ABSENT, decoded.records.last().outcome)
    }

    private fun accumulatorRequest(limit: Int = 1) =
        WalletHistoryCaptureRequest(wallet, directory, limit = limit, scope = WalletHistoryScope.PUMPFUN_USER_VOLUME_ACCUMULATOR)

    private fun capture(
        request: WalletHistoryCaptureRequest,
        detail: (String) -> String = { signature ->
            when (signature) {
                SIGNER -> transaction(SIGNER, walletSigner = true)
                NON_SIGNER -> transaction(NON_SIGNER, walletSigner = false)
                else -> transaction(ABSENT, walletSigner = null)
            }
        },
    ): WalletHistoryCaptureResult = service { method, params, _ ->
        calls += method
        mapper.readTree(when (method) {
            "getGenesisHash" -> "\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\""
            "getSignaturesForAddress" -> {
                listed += params[0]
                """[{"signature":"$SIGNER","slot":100,"err":null},{"signature":"$NON_SIGNER","slot":99,"err":null},""" +
                    """{"signature":"$ABSENT","slot":98,"err":null}]"""
            }
            "getTransaction" -> detail(params[0] as String)
            else -> error("Unexpected method")
        })
    }.capture(request)

    private fun service(rpc: SolanaReadRpc = SolanaReadRpc { method, _, _ -> calls += method; mapper.readTree("\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\"") }) =
        WalletHistoryCaptureService(rpc, mapper, Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC))

    /** [walletSigner] null leaves the wallet out of the accounts; [accumulator] false leaves the listed accumulator out. */
    private fun transaction(signature: String, walletSigner: Boolean?, accumulator: Boolean = true): String {
        val slot = when (signature) { SIGNER -> 100; NON_SIGNER -> 99; else -> 98 }
        val keys = buildList {
            add("""{"pubkey":"$PAYER","signer":true,"writable":true}""")
            walletSigner?.let { add("""{"pubkey":"${wallet.value}","signer":$it,"writable":true}""") }
            if (accumulator) add("""{"pubkey":"$ACCUMULATOR","signer":false,"writable":true}""")
        }.joinToString(",")
        return """{"slot":$slot,"blockTime":1790985600,"version":0,"meta":{"err":null,"fee":5000,"preBalances":[1000000],"postBalances":[995000],"logMessages":[]},""" +
            """"transaction":{"signatures":["$signature"],"message":{"accountKeys":[$keys],""" +
            """"instructions":[{"programId":"$PAYER","accounts":[],"data":""}],"recentBlockhash":"$PAYER"}}}"""
    }

    private fun versionTwo(manifest: WalletHistoryManifest): ObjectNode =
        mapper.valueToTree<ObjectNode>(manifest).apply {
            put("schemaVersion", 2)
            remove("scope")
            remove("listingAddress")
        }

    private fun writeManifest(inner: ObjectNode) {
        val envelope = mapper.createObjectNode().apply {
            put("sha256", sha256(mapper.writeValueAsBytes(inner)))
            set("manifest", inner)
        }
        Files.write(directory.resolve("manifest.json"), mapper.writeValueAsBytes(envelope))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        /** PDA(["user_volume_accumulator", 32 zero bytes], Pump.fun), derived offline. */
        const val ACCUMULATOR = "7GxTMtPtJuY11YEPk9uTXrixjW4XHidUmrWRCSL9VE94"
        const val PAYER = "11111111111111111111111111111112"
        val SIGNER = "1".repeat(64)
        val NON_SIGNER = "1".repeat(63) + "2"
        val ABSENT = "1".repeat(63) + "3"
    }
}
