package com.trenching.bot.walletactivity

import com.trenching.bot.chaincapture.CaptureProviderException
import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.chaincapture.SolanaReadRpc
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.application.WalletHistoryCaptureService
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryArtifactStore
import java.math.BigInteger
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper

class WalletHistoryPaginationTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val wallet = WalletAddress.of("1".repeat(32))
    private val pageSize = 1000

    @Test
    fun `selects only successful references and stops deterministically at the Nth success mid-page`() {
        val fetched = mutableListOf<String>()
        val pages = mutableListOf<Map<*, *>>()
        val capture = service { method, params, _ -> mapper.readTree(when (method) {
            "getGenesisHash" -> genesis()
            "getSignaturesForAddress" -> { pages += params[1] as Map<*, *>; page(1..pageSize) { it % 3 != 0 } }
            "getTransaction" -> { fetched += params[0] as String; "null" }
            else -> error("Unexpected method")
        }) }
        val first = capture.capture(WalletHistoryCaptureRequest(wallet, directory.resolve("first"))).manifest
        val second = capture.capture(WalletHistoryCaptureRequest(wallet, directory.resolve("second"))).manifest
        assertEquals((3..300 step 3).map(::signature), first.selection.map { it.signature })
        assertEquals(first.selection.map { it.signature } + second.selection.map { it.signature }, fetched)
        assertEquals(mapOf("commitment" to "confirmed", "limit" to pageSize), pages.first())
        assertEquals(2, pages.size)
        assertEquals(300, first.scannedSignatures)
        assertEquals(200, first.skippedFailed)
        assertEquals(WalletHistoryScanCursor(signature(300), slot(300)), first.scanCursor)
        assertEquals(WalletHistoryDiscoveryStop.LIMIT_REACHED, first.discoveryStop)
        assertEquals(20, first.scanPageLimit)
        assertEquals(2, first.schemaVersion)
        assertTrue(first.complete)
        assertEquals(first, second)
    }

    @Test
    fun `ends discovery at a short page with fewer successes than requested`() {
        var transactionCalls = 0
        val capture = service { method, _, _ -> mapper.readTree(when (method) {
            "getGenesisHash" -> genesis()
            "getSignaturesForAddress" -> page(1..10) { it % 2 == 0 }
            "getTransaction" -> { transactionCalls++; "null" }
            else -> error("Unexpected method")
        }) }
        val manifest = capture.capture(WalletHistoryCaptureRequest(wallet, directory)).manifest
        assertEquals(listOf(1, 3, 5, 7, 9).map(::signature), manifest.selection.map { it.signature })
        assertEquals(10, manifest.scannedSignatures)
        assertEquals(5, manifest.skippedFailed)
        assertEquals(WalletHistoryDiscoveryStop.HISTORY_END, manifest.discoveryStop)
        assertEquals(5, transactionCalls)
        assertTrue(manifest.complete)
    }

    @Test
    fun `ends discovery at an empty page after a full page`() {
        var calls = 0
        val capture = service { method, params, _ -> mapper.readTree(when (method) {
            "getGenesisHash" -> genesis()
            "getSignaturesForAddress" -> {
                calls++
                if (calls == 1) page(1..pageSize) { it != 1 } else { assertEquals(signature(pageSize), (params[1] as Map<*, *>)["before"]); "[]" }
            }
            "getTransaction" -> "null"
            else -> error("Unexpected method")
        }) }
        val manifest = capture.capture(WalletHistoryCaptureRequest(wallet, directory)).manifest
        assertEquals(2, calls)
        assertEquals(WalletHistoryDiscoveryStop.HISTORY_END, manifest.discoveryStop)
        assertEquals(pageSize, manifest.scannedSignatures)
        assertEquals(WalletHistoryScanCursor(signature(pageSize), slot(pageSize)), manifest.scanCursor)
    }

    @Test
    fun `completes a bounded scan with fewer successes than requested at the page bound`() {
        var calls = 0
        val capture = service { method, _, _ -> mapper.readTree(when (method) {
            "getGenesisHash" -> genesis()
            "getSignaturesForAddress" -> { calls++; page((calls - 1) * pageSize + 1..calls * pageSize) { it % pageSize != 1 } }
            "getTransaction" -> "null"
            else -> error("Unexpected method")
        }) }
        val manifest = capture.capture(WalletHistoryCaptureRequest(wallet, directory, scanPages = 2)).manifest
        assertEquals(2, calls)
        assertEquals(listOf(signature(1), signature(1001)), manifest.selection.map { it.signature })
        assertEquals(2000, manifest.scannedSignatures)
        assertEquals(1998, manifest.skippedFailed)
        assertEquals(WalletHistoryScanCursor(signature(2000), slot(2000)), manifest.scanCursor)
        assertEquals(WalletHistoryDiscoveryStop.SCAN_BOUND, manifest.discoveryStop)
        assertTrue(manifest.discoveryComplete)
        assertTrue(manifest.complete)
    }

    @Test
    fun `checkpoints an all failed page and resumes before its cursor without rescanning or admitting newer signatures`() {
        var resume = false
        val befores = mutableListOf<Any?>()
        val capture = service { method, params, _ ->
            if (method == "getSignaturesForAddress") {
                val before = (params[1] as Map<*, *>)["before"]
                befores += before
                when {
                    before == null -> mapper.readTree(page(1..pageSize) { true })
                    !resume -> throw CaptureProviderException("UNAVAILABLE")
                    else -> mapper.readTree(page(pageSize + 1..pageSize + 2) { false })
                }
            } else mapper.readTree(when (method) {
                "getGenesisHash" -> genesis()
                "getTransaction" -> "null"
                else -> error("Unexpected method")
            })
        }
        val request = WalletHistoryCaptureRequest(wallet, directory)
        val interrupted = capture.capture(request).manifest
        val checkpoint = WalletHistoryArtifactStore(mapper).inspect(directory).manifest
        assertEquals(interrupted, checkpoint)
        assertEquals(emptyList(), checkpoint.selection)
        assertEquals(pageSize, checkpoint.scannedSignatures)
        assertEquals(pageSize, checkpoint.skippedFailed)
        assertEquals(WalletHistoryScanCursor(signature(pageSize), slot(pageSize)), checkpoint.scanCursor)
        assertNull(checkpoint.discoveryStop)
        assertFalse(checkpoint.discoveryComplete)
        assertFalse(checkpoint.complete)
        resume = true
        val result = capture.capture(request).manifest
        assertEquals(listOf<Any?>(null, signature(pageSize), signature(pageSize)), befores)
        assertEquals(listOf(pageSize + 1, pageSize + 2).map(::signature), result.selection.map { it.signature })
        assertEquals(pageSize + 2, result.scannedSignatures)
        assertEquals(pageSize, result.skippedFailed)
        assertEquals(WalletHistoryDiscoveryStop.HISTORY_END, result.discoveryStop)
        assertTrue(result.complete)
    }

    @Test
    fun `rejects a page newer than the last scanned failed entry even when older than the last selection`() {
        var calls = 0
        val capture = service { method, _, _ -> mapper.readTree(when (method) {
            "getGenesisHash" -> genesis()
            "getSignaturesForAddress" -> { calls++; if (calls == 1) page(1..pageSize) { it != 1 } else "[${entry(pageSize + 1, slot = slot(500), failed = true)}]" }
            "getTransaction" -> error("Must reject selection before details")
            else -> error("Unexpected method")
        }) }
        assertCategory("SIGNATURE_ORDER") { capture.capture(WalletHistoryCaptureRequest(wallet, directory)) }
    }

    @Test
    fun `rejects ascending slots between skipped failed entries`() {
        val capture = listing("[${entry(1, failed = true)},${entry(2, slot = slot(1) + 1, failed = true)}]")
        assertCategory("SIGNATURE_ORDER") { capture.capture(WalletHistoryCaptureRequest(wallet, directory)) }
    }

    @Test
    fun `rejects a duplicate signature whose error status conflicts with a skipped entry`() {
        val capture = listing("[${entry(1, failed = true)},${entry(1)}]")
        assertCategory("CONFLICTING_SIGNATURE") { capture.capture(WalletHistoryCaptureRequest(wallet, directory)) }
    }

    @Test
    fun `rejects a later failed listing of an already selected signature`() {
        var calls = 0
        val capture = service { method, _, _ -> mapper.readTree(when (method) {
            "getGenesisHash" -> genesis()
            "getSignaturesForAddress" -> { calls++; if (calls == 1) page(1..pageSize) { it != pageSize } else "[${entry(pageSize, failed = true)}]" }
            "getTransaction" -> error("Must reject selection before details")
            else -> error("Unexpected method")
        }) }
        assertCategory("CONFLICTING_SIGNATURE") { capture.capture(WalletHistoryCaptureRequest(wallet, directory)) }
    }

    @Test
    fun `rejects a page that relists the scan cursor`() {
        var calls = 0
        val capture = service { method, _, _ -> mapper.readTree(when (method) {
            "getGenesisHash" -> genesis()
            "getSignaturesForAddress" -> { calls++; if (calls == 1) page(1..pageSize) { true } else "[${entry(pageSize, failed = true)}]" }
            "getTransaction" -> error("Must reject selection before details")
            else -> error("Unexpected method")
        }) }
        assertCategory("NON_PROGRESSING_PAGE") { capture.capture(WalletHistoryCaptureRequest(wallet, directory)) }
    }

    @Test
    fun `rejects a listing page larger than requested`() {
        val capture = listing(page(1..pageSize + 1) { false })
        assertCategory("SIGNATURE_PAGE") { capture.capture(WalletHistoryCaptureRequest(wallet, directory)) }
    }

    @Test
    fun `deduplicates identical successful references and counts each scanned entry`() {
        val capture = listing("[${entry(1)},${entry(1)},${entry(2)}]")
        val manifest = capture.capture(WalletHistoryCaptureRequest(wallet, directory)).manifest
        assertEquals(listOf(1, 2).map(::signature), manifest.selection.map { it.signature })
        assertEquals(3, manifest.scannedSignatures)
        assertEquals(0, manifest.skippedFailed)
    }

    private fun assertCategory(category: String, action: () -> Unit) {
        val failure = assertFailsWith<CaptureValidationException> { action() }
        assertTrue(failure.message.orEmpty().endsWith("category=$category"), failure.message)
    }

    private fun listing(json: String) = service { method, _, _ -> mapper.readTree(when (method) {
        "getGenesisHash" -> genesis()
        "getSignaturesForAddress" -> json
        "getTransaction" -> "null"
        else -> error("Unexpected method")
    }) }

    private fun service(rpc: SolanaReadRpc) = WalletHistoryCaptureService(rpc, mapper,
        Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC))
    private fun genesis() = "\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\""
    private fun page(indices: IntRange, failed: (Int) -> Boolean) = indices.map { entry(it, failed = failed(it)) }.joinToString(",", "[", "]")
    private fun slot(index: Int) = 100_000L - index
    private fun entry(index: Int, slot: Long = slot(index), failed: Boolean = false): String {
        val err = if (failed) """{"InstructionError":[0,{"Custom":7}]}""" else "null"
        return """{"signature":"${signature(index)}","slot":$slot,"err":$err,"memo":null}"""
    }
    private fun signature(index: Int): String {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var value = BigInteger.valueOf(index.toLong())
        var encoded = ""
        while (value.signum() > 0) {
            val parts = value.divideAndRemainder(BigInteger.valueOf(58))
            encoded = alphabet[parts[1].toInt()] + encoded
            value = parts[0]
        }
        val significantBytes = (BigInteger.valueOf(index.toLong()).bitLength() + 7) / 8
        return "1".repeat(64 - significantBytes) + encoded
    }
}
