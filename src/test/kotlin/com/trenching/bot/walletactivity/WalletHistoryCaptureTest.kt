package com.trenching.bot.walletactivity

import com.trenching.bot.chaincapture.CaptureBudgetException
import com.trenching.bot.chaincapture.CaptureProviderException
import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.chaincapture.SolanaReadRpc
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.application.WalletHistoryCaptureService
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryArtifactStore
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper

class WalletHistoryCaptureTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val wallet = WalletAddress.of("1".repeat(32))
    private val signature = "1".repeat(64)
    private val calls = mutableListOf<String>()

    @Test
    fun `freezes unique latest signatures and accounts for missing details without replacing selection`() {
        val rpc = SolanaReadRpc { method, _, _ ->
            calls += method
            mapper.readTree(when (method) {
                "getGenesisHash" -> "\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\""
                "getSignaturesForAddress" -> """[{"signature":"$signature","slot":100,"err":null},{"signature":"$signature","slot":100,"err":null}]"""
                "getTransaction" -> "null"
                else -> error("Unexpected method")
            })
        }
        val result = WalletHistoryCaptureService(rpc, mapper, Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC))
            .capture(WalletHistoryCaptureRequest(wallet, directory, limit = 1))
        assertEquals(listOf(signature), result.manifest.selection.map { it.signature })
        assertEquals(WalletHistoryOutcome.UNAVAILABLE, result.manifest.records.single().outcome)
        assertTrue(result.manifest.complete)
        assertEquals(listOf("getGenesisHash", "getSignaturesForAddress", "getTransaction"), calls)
    }

    @Test
    fun `rejects non mainnet genesis before wallet discovery`() {
        val service = service { method, _, _ -> calls += method; mapper.readTree("\"different-network\"") }
        assertFailsWith<CaptureValidationException> { service.capture(WalletHistoryCaptureRequest(wallet, directory)) }
        assertEquals(listOf("getGenesisHash"), calls)
    }

    @Test
    fun `rejects unbounded selection before any provider request`() {
        val service = service { _, _, _ -> error("Must not call provider") }
        assertFailsWith<CaptureValidationException> { service.capture(WalletHistoryCaptureRequest(wallet, directory, limit = 101)) }
    }

    @Test
    fun `rejects scan page bounds before any provider request`() {
        val service = service { _, _, _ -> error("Must not call provider") }
        listOf(0, 51).forEach { scanPages ->
            val failure = assertFailsWith<CaptureValidationException> {
                service.capture(WalletHistoryCaptureRequest(wallet, directory, scanPages = scanPages))
            }
            assertTrue(failure.message.orEmpty().endsWith("category=REQUEST_BOUNDS"))
        }
    }

    @Test
    fun `refuses to resume a capture with a different scan page bound before any provider request`() {
        val service = service { method, _, _ ->
            calls += method
            mapper.readTree(when (method) {
                "getGenesisHash" -> "\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\""
                "getSignaturesForAddress" -> """[{"signature":"$signature","slot":100,"err":null}]"""
                "getTransaction" -> transaction()
                else -> error("Unexpected method")
            })
        }
        service.capture(WalletHistoryCaptureRequest(wallet, directory, limit = 1, scanPages = 3))
        val failure = assertFailsWith<CaptureValidationException> {
            service.capture(WalletHistoryCaptureRequest(wallet, directory, limit = 1, scanPages = 4))
        }
        assertTrue(failure.message.orEmpty().endsWith("category=RESUME_IDENTITY"))
        assertEquals(3, calls.size)
    }

    @Test
    fun `rejects a version one manifest for inspection and resume before any provider request`() {
        val inner = mapper.readTree("""{"schemaVersion":1,"wallet":"${wallet.value}","genesisHash":"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d",
            "commitment":"confirmed","capturedAt":"2026-10-03T00:00:00Z","limit":1,
            "selection":[{"signature":"$signature","slot":100,"discoveryFailed":true}],
            "records":[{"signature":"$signature","slot":100,"outcome":"PENDING","artifact":null,"sha256":null,"bytes":0}],
            "complete":false,"discoveryComplete":true,"requestsUsed":2,"bytesUsed":10}""")
        val envelope = mapper.createObjectNode().apply {
            put("sha256", sha256(mapper.writeValueAsBytes(inner)))
            set("manifest", inner)
        }
        Files.write(directory.resolve("manifest.json"), mapper.writeValueAsBytes(envelope))
        val service = service { method, _, _ -> calls += method; error("Must not call provider") }
        listOf<() -> Unit>(
            { WalletHistoryArtifactStore(mapper).inspect(directory) },
            { service.capture(WalletHistoryCaptureRequest(wallet, directory, limit = 1)) },
        ).forEach { action ->
            val failure = assertFailsWith<CaptureValidationException> { action() }
            assertTrue(failure.message.orEmpty().endsWith("category=MANIFEST_VERSION"), failure.message)
        }
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `rejects manifests whose discovery counters cursor or stop reason are inconsistent`() {
        val older = "1".repeat(63) + "2"
        val service = service { method, _, _ -> mapper.readTree(when (method) {
            "getGenesisHash" -> "\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\""
            "getSignaturesForAddress" -> """[{"signature":"$signature","slot":100,"err":null},{"signature":"$older","slot":99,"err":"AccountInUse"}]"""
            "getTransaction" -> transaction()
            else -> error("Unexpected method")
        }) }
        val valid = service.capture(WalletHistoryCaptureRequest(wallet, directory, limit = 2)).manifest
        assertEquals(WalletHistoryDiscoveryStop.HISTORY_END, valid.discoveryStop)
        assertEquals(WalletHistoryScanCursor(older, 99), valid.scanCursor)
        assertEquals(valid, WalletHistoryArtifactStore(mapper).inspect(directory).manifest)
        val forgeries = mapOf(
            "negative scanned" to valid.copy(scannedSignatures = -1),
            "negative skipped" to valid.copy(skippedFailed = -1),
            "skipped above scanned" to valid.copy(skippedFailed = 3),
            "selection above scanned" to valid.copy(scannedSignatures = 0, skippedFailed = 0),
            "missing cursor" to valid.copy(scanCursor = null),
            "cursor newer than selection" to valid.copy(scanCursor = WalletHistoryScanCursor(older, 101)),
            "missing stop on complete discovery" to valid.copy(discoveryStop = null),
            "stop on incomplete discovery" to valid.copy(discoveryComplete = false, complete = false),
            "limit reached below limit" to valid.copy(discoveryStop = WalletHistoryDiscoveryStop.LIMIT_REACHED),
            "scan page limit zero" to valid.copy(scanPageLimit = 0),
            "scan page limit above maximum" to valid.copy(scanPageLimit = 51),
        )
        forgeries.forEach { (name, forged) ->
            WalletHistoryArtifactStore(mapper).save(directory, forged)
            assertFailsWith<CaptureValidationException>(name) { WalletHistoryArtifactStore(mapper).inspect(directory) }
        }
    }

    @Test
    fun `resumes frozen selection and verifies saved artifact hashes before reuse`() {
        val service = service { method, _, _ ->
            calls += method
            mapper.readTree(when (method) {
                "getGenesisHash" -> "\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\""
                "getSignaturesForAddress" -> """[{"signature":"$signature","slot":100,"err":null}]"""
                "getTransaction" -> transaction()
                else -> error("Unexpected method")
            })
        }
        val request = WalletHistoryCaptureRequest(wallet, directory, limit = 1)
        val first = service.capture(request)
        assertEquals(WalletHistoryOutcome.UNCLASSIFIED, first.manifest.records.single().outcome)
        service.capture(request)
        assertEquals(1, calls.count { it == "getSignaturesForAddress" })
        assertEquals(1, calls.count { it == "getTransaction" })
        Files.writeString(directory.resolve(requireNotNull(first.manifest.records.single().artifact)), "{}")
        assertFailsWith<CaptureValidationException> { service.capture(request) }
        assertEquals(1, calls.count { it == "getTransaction" })
    }

    @Test
    fun `retains non signer transactions and records detail failures as contradicting successful discovery`() {
        listOf(
            transaction(err = "{\"InstructionError\":[0,\"Custom\"]}") to WalletHistoryOutcome.INVALID_TRANSACTION,
            transaction(signer = false) to WalletHistoryOutcome.NON_SIGNER,
            transaction() to WalletHistoryOutcome.UNCLASSIFIED,
            transaction(slot = 101) to WalletHistoryOutcome.INVALID_TRANSACTION,
            transaction(version = "2") to WalletHistoryOutcome.UNSUPPORTED_VERSION,
            transaction(version = "1") to WalletHistoryOutcome.UNCLASSIFIED,
            transaction(err = "\"AccountInUse\"") to WalletHistoryOutcome.INVALID_TRANSACTION,
            transaction(logs = "[\"Program log: Memo claim at https://spam.invalid\"]") to WalletHistoryOutcome.WITHHELD_UNSAFE_TEXT,
        ).forEachIndexed { index, (json, outcome) ->
            val service = service { method, _, _ -> mapper.readTree(when (method) {
                "getGenesisHash" -> "\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\""
                "getSignaturesForAddress" -> """[{"signature":"$signature","slot":100,"err":null}]"""
                "getTransaction" -> json
                else -> error("Unexpected method")
            }) }
            val captureDirectory = directory.resolve(index.toString())
            val result = service.capture(WalletHistoryCaptureRequest(wallet, captureDirectory, limit = 1))
            val record = result.manifest.records.single()
            assertEquals(outcome, record.outcome)
            assertEquals(outcome in setOf(WalletHistoryOutcome.NON_SIGNER, WalletHistoryOutcome.UNCLASSIFIED), record.artifact != null)
            assertEquals(record.artifact != null, Files.exists(captureDirectory.resolve("transactions/$signature.json")))
            assertEquals(outcome != WalletHistoryOutcome.INVALID_TRANSACTION, result.manifest.complete)
            assertEquals(result.manifest, WalletHistoryArtifactStore(mapper).inspect(captureDirectory).manifest)
        }
    }

    @Test
    fun `does not rediscover newer signatures when resuming an interrupted frozen capture`() {
        var available = false
        val service = service { method, _, _ ->
            calls += method
            when (method) {
                "getGenesisHash" -> mapper.readTree("\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\"")
                "getSignaturesForAddress" -> mapper.readTree("""[{"signature":"$signature","slot":100,"err":null}]""")
                "getTransaction" -> if (available) mapper.readTree(transaction()) else throw CaptureProviderException("UNAVAILABLE")
                else -> error("Unexpected method")
            }
        }
        val request = WalletHistoryCaptureRequest(wallet, directory, limit = 1)
        val incomplete = service.capture(request)
        assertEquals(false, incomplete.manifest.complete)
        assertEquals(WalletHistoryOutcome.PROVIDER_UNAVAILABLE, incomplete.manifest.records.single().outcome)
        available = true
        val complete = service.capture(request)
        assertTrue(complete.manifest.complete)
        assertEquals(1, calls.count { it == "getSignaturesForAddress" })
        assertEquals(WalletHistoryOutcome.UNCLASSIFIED, complete.manifest.records.single().outcome)
    }

    @Test
    fun `records conflicting transaction identities as invalid instead of storing another transaction`() {
        val service = service { method, _, _ -> mapper.readTree(when (method) {
            "getGenesisHash" -> "\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\""
            "getSignaturesForAddress" -> """[{"signature":"$signature","slot":100,"err":null}]"""
            "getTransaction" -> transaction().replace(signature, "1".repeat(63) + "2")
            else -> error("Unexpected method")
        }) }
        val result = service.capture(WalletHistoryCaptureRequest(wallet, directory, limit = 1))
        assertEquals(WalletHistoryOutcome.INVALID_TRANSACTION, result.manifest.records.single().outcome)
        assertEquals(null, result.manifest.records.single().artifact)
    }

    @Test
    fun `rejects unexpected secret bearing transaction metadata without writing raw data`() {
        val json = transaction().replace("\"meta\":{\"err\":null,", "\"meta\":{\"err\":null,\"api-key\":\"SENSITIVE_SENTINEL\",")
        val service = service { method, _, _ -> mapper.readTree(when (method) {
            "getGenesisHash" -> "\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\""
            "getSignaturesForAddress" -> """[{"signature":"$signature","slot":100,"err":null}]"""
            "getTransaction" -> json
            else -> error("Unexpected method")
        }) }
        val result = service.capture(WalletHistoryCaptureRequest(wallet, directory, limit = 1))
        assertEquals(WalletHistoryOutcome.INVALID_TRANSACTION, result.manifest.records.single().outcome)
        assertEquals(null, result.manifest.records.single().artifact)
    }

    @Test
    fun `rejects failed transaction details that do not reference the selected wallet`() {
        val json = transaction(err = "{\"InstructionError\":[0,\"Custom\"]}")
            .replace("\"pubkey\":\"${wallet.value}\"", "\"pubkey\":\"${"1".repeat(31)}2\"")
        val service = service { method, _, _ -> mapper.readTree(when (method) {
            "getGenesisHash" -> "\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\""
            "getSignaturesForAddress" -> """[{"signature":"$signature","slot":100,"err":null}]"""
            "getTransaction" -> json
            else -> error("Unexpected method")
        }) }
        val result = service.capture(WalletHistoryCaptureRequest(wallet, directory, limit = 1))
        assertEquals(WalletHistoryOutcome.INVALID_TRANSACTION, result.manifest.records.single().outcome)
        assertEquals(false, result.manifest.complete)
        assertEquals(null, result.manifest.records.single().artifact)
    }

    @Test
    fun `preserves cumulative acquisition usage through checkpoints and completed capture resumes`() {
        val service = service { method, _, budget ->
            budget.request()
            val result = mapper.readTree(when (method) {
                "getGenesisHash" -> "\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\""
                "getSignaturesForAddress" -> """[{"signature":"$signature","slot":100,"err":null}]"""
                "getTransaction" -> transaction()
                else -> error("Unexpected method")
            })
            budget.bytes(mapper.writeValueAsBytes(result).size.toLong())
            result
        }
        val request = WalletHistoryCaptureRequest(wallet, directory, limit = 1)
        val first = service.capture(request)
        val second = service.capture(request)
        val third = service.capture(request)
        assertEquals(3, first.manifest.requestsUsed)
        assertEquals(4, second.manifest.requestsUsed)
        assertEquals(5, third.manifest.requestsUsed)
        val genesisBytes = mapper.writeValueAsBytes(mapper.readTree("\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\"")).size.toLong()
        assertEquals(first.manifest.bytesUsed + genesisBytes, second.manifest.bytesUsed)
        assertEquals(first.manifest.bytesUsed + genesisBytes * 2, third.manifest.bytesUsed)
    }

    @Test
    fun `rejects an edited completion flag using the manifest checksum before any provider call`() {
        val service = service { method, _, _ ->
            calls += method
            mapper.readTree(when (method) {
                "getGenesisHash" -> "\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\""
                "getSignaturesForAddress" -> """[{"signature":"$signature","slot":100,"err":null}]"""
                "getTransaction" -> "null"
                else -> error("Unexpected method")
            })
        }
        val request = WalletHistoryCaptureRequest(wallet, directory, limit = 1)
        service.capture(request)
        val manifestFile = directory.resolve("manifest.json")
        Files.writeString(manifestFile, Files.readString(manifestFile).replace("\"complete\":true", "\"complete\":false"))
        assertFailsWith<CaptureValidationException> { WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper).inspect(directory) }
        assertFailsWith<CaptureValidationException> { service.capture(request) }
        assertEquals(3, calls.size)
    }

    @Test
    fun `rejects a traversing artifact reference even when its manifest hash is internally consistent`() {
        val service = service { method, _, _ -> mapper.readTree(when (method) {
            "getGenesisHash" -> "\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\""
            "getSignaturesForAddress" -> """[{"signature":"$signature","slot":100,"err":null}]"""
            "getTransaction" -> transaction()
            else -> error("Unexpected method")
        }) }
        val captureDirectory = directory.resolve("capture")
        val result = service.capture(WalletHistoryCaptureRequest(wallet, captureDirectory, limit = 1))
        Files.writeString(directory.resolve("outside.json"), "KEEP")
        val forged = result.manifest.copy(records = listOf(result.manifest.records.single().copy(artifact = "../outside.json")))
        WalletHistoryArtifactStore(mapper).save(captureDirectory, forged)
        assertFailsWith<CaptureValidationException> { WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper).inspect(captureDirectory) }
        assertEquals("KEEP", Files.readString(directory.resolve("outside.json")))
    }

    @Test
    fun `requests transaction details up to version one`() {
        val versions = mutableListOf<Any?>()
        service { method, params, _ ->
            if (method == "getTransaction") versions += (params[1] as Map<*, *>)["maxSupportedTransactionVersion"]
            mapper.readTree(when (method) {
                "getGenesisHash" -> "\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\""
                "getSignaturesForAddress" -> """[{"signature":"$signature","slot":100,"err":null}]"""
                "getTransaction" -> transaction()
                else -> error("Unexpected method")
            })
        }.capture(WalletHistoryCaptureRequest(wallet, directory, limit = 1))
        assertEquals(listOf<Any?>(1), versions)
    }

    @Test
    fun `replaces an unreferenced artifact left by a capture interrupted before its manifest checkpoint`() {
        var available = false
        val service = service { method, _, _ ->
            when (method) {
                "getGenesisHash" -> mapper.readTree("\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\"")
                "getSignaturesForAddress" -> mapper.readTree("""[{"signature":"$signature","slot":100,"err":null}]""")
                "getTransaction" -> if (available) mapper.readTree(transaction()) else throw CaptureBudgetException("REQUESTS")
                else -> error("Unexpected method")
            }
        }
        val request = WalletHistoryCaptureRequest(wallet, directory, limit = 1)
        assertEquals(WalletHistoryOutcome.PENDING, service.capture(request).manifest.records.single().outcome)
        val orphan = directory.resolve("transactions/$signature.json")
        Files.createDirectories(orphan.parent)
        Files.writeString(orphan, transaction(logs = "[\"Program log: earlier fetch\"]"))
        available = true
        val result = service.capture(request)
        val record = result.manifest.records.single()
        assertEquals(WalletHistoryOutcome.UNCLASSIFIED, record.outcome)
        assertTrue(result.manifest.complete)
        assertEquals(sha256(Files.readAllBytes(orphan)), record.sha256)
        assertEquals(result.manifest, WalletHistoryArtifactStore(mapper).inspect(directory).manifest)
    }

    @Test
    fun `refuses a concurrent capture into the same directory before any provider call`() {
        val service = service { method, _, _ ->
            calls += method
            mapper.readTree(when (method) {
                "getGenesisHash" -> "\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\""
                "getSignaturesForAddress" -> """[{"signature":"$signature","slot":100,"err":null}]"""
                "getTransaction" -> transaction()
                else -> error("Unexpected method")
            })
        }
        val request = WalletHistoryCaptureRequest(wallet, directory, limit = 1)
        FileChannel.open(directory.resolve(".capture.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val failure = assertFailsWith<CaptureValidationException> { service.capture(request) }
                assertTrue(failure.message.orEmpty().contains("CAPTURE_IN_PROGRESS"))
            }
        }
        assertEquals(emptyList(), calls)
        assertTrue(service.capture(request).manifest.complete)
        assertTrue(WalletHistoryArtifactStore(mapper).inspect(directory).manifest.complete)
    }

    @Test
    fun `rejects a stored artifact on a withheld record`() {
        val service = service { method, _, _ -> mapper.readTree(when (method) {
            "getGenesisHash" -> "\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\""
            "getSignaturesForAddress" -> """[{"signature":"$signature","slot":100,"err":null}]"""
            "getTransaction" -> transaction()
            else -> error("Unexpected method")
        }) }
        val result = service.capture(WalletHistoryCaptureRequest(wallet, directory, limit = 1))
        val forged = result.manifest.copy(records = listOf(result.manifest.records.single().copy(outcome = WalletHistoryOutcome.WITHHELD_UNSAFE_TEXT)))
        WalletHistoryArtifactStore(mapper).save(directory, forged)
        val failure = assertFailsWith<CaptureValidationException> { WalletHistoryArtifactStore(mapper).inspect(directory) }
        assertTrue(failure.message.orEmpty().contains("RECORD_ARTIFACT"))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun service(rpc: SolanaReadRpc) = WalletHistoryCaptureService(
        rpc, mapper, Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC),
    )

    private fun transaction(slot: Long = 100, signer: Boolean = true, err: String = "null", version: String = "0", logs: String = "[]") =
        """{"slot":$slot,"blockTime":1790985600,"version":$version,"meta":{"err":$err,"fee":5000,"preBalances":[1000000],"postBalances":[995000],"logMessages":$logs},""" +
            """"transaction":{"signatures":["$signature"],"message":{"accountKeys":[{"pubkey":"${wallet.value}","signer":$signer,"writable":true}],""" +
            """"instructions":[{"programId":"${wallet.value}","accounts":[],"data":""}],"recentBlockhash":"${wallet.value}"}}}"""
}
