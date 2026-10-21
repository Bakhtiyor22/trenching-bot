package com.trenching.bot.marketdata

import com.trenching.bot.chaincapture.CaptureBudgetException
import com.trenching.bot.chaincapture.CaptureProviderException
import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.chaincapture.SolanaReadRpc
import com.trenching.bot.common.Base58
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.marketdata.application.MarketHistoryCaptureService
import com.trenching.bot.marketdata.infrastructure.capture.MarketHistoryArtifactStore
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class MarketHistoryCaptureTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val configuration = MarketHistoryCaptureConfiguration()
    private val calls = mutableListOf<String>()
    private val listings = mutableListOf<Map<*, *>>()
    private val listedAddresses = mutableListOf<String>()
    private val slotOf = mutableMapOf<String, Long>()
    private val fetched = mutableListOf<String>()
    private val tipRequests = mutableListOf<List<Any>>()
    private var tip = 1_000_000L

    /** One minute of 300 slots: a 200 ms slot pace. */
    private var paceSamples = """[{"slot":1,"numSlots":300,"numTransactions":1,"samplePeriodSecs":60}]"""
    private var paceRequests = 0

    @Test
    fun `selects successful in-window signatures skipping failed and newer entries and stops below the window`() {
        val page = listOf(entry(1, 1015), entry(2, 1014, failed = true), entry(3, 1012), entry(4, 1010, failed = true),
            entry(5, 1005), entry(6, 1001), entry(7, 1000), entry(8, 999))
        val result = capture(request(listOf(window(MINT, 1001, 1012, SOURCE)))) { _, before -> if (before == null) page else error("No second page") }

        val window = result.manifest.windows.single()
        assertEquals(MarketWindowCoverage.COMPLETE, window.coverage)
        assertEquals(listOf(3, 5, 6).map(::signature), window.selection.map { it.signature })
        assertEquals(listOf(1012L, 1005L, 1001L), window.selection.map { it.slot })
        assertEquals(3, window.selected)
        assertEquals(1, window.skippedNewer)
        assertEquals(2, window.skippedFailed)
        assertEquals(6, window.scanned)
        assertEquals(1, window.pagesScanned)
        assertEquals(mapOf("commitment" to "confirmed", "limit" to 1000, "until" to signature(SOURCE)), listings.single())
        assertEquals(listOf(6, 5, 3).map(::signature), fetched)
        assertEquals(List(3) { MarketTransactionOutcome.CAPTURED }, result.manifest.records.map { it.outcome })
        assertTrue(result.manifest.complete)
        assertTrue(result.manifest.acquisitionComplete)
        assertEquals(result.manifest, recorded().inspect(directory).manifest)
    }

    @Test
    fun `holds a window whose end the confirmed tip has not reached without listing it and asks to retry after the rest of its horizon`() {
        tip = 1002
        val result = capture(request(listOf(window(MINT, 1001, 1012, SOURCE)))) { _, _ -> error("A window before its end is never listed") }

        val window = result.manifest.windows.single()
        assertEquals(MarketWindowCoverage.HORIZON_NOT_ELAPSED, window.coverage)
        assertEquals(0, window.pagesScanned)
        assertEquals(emptyList(), window.selection)
        assertEquals(listOf(listOf<Any>(mapOf("commitment" to "confirmed"))), tipRequests)
        assertEquals(emptyList(), listings)
        assertEquals(emptyList(), fetched)
        assertEquals("HORIZON_NOT_ELAPSED", result.stopReason)
        // 10 slots at the measured 200 ms, plus a tenth.
        assertEquals(Duration.ofMillis(2_200), result.stopRetryAfter)
        assertEquals(1, paceRequests)
        assertNull(window.listedAtTip)
        assertFalse(result.manifest.acquisitionComplete)
        assertFalse(result.manifest.complete)
        assertEquals(result.manifest, recorded().inspect(directory).manifest)
        val case = recorded().inspectCases(directory).cases.single()
        assertEquals(MarketCaseStage.DISCOVERY_PENDING, case.stage)
        assertEquals(MarketCaptureReasons.MARKET_HORIZON_NOT_ELAPSED, case.blocker)
        // Reference rates follow the decoded observations, so a held window keeps them from being captured too.
        assertFalse(recorded().decodeCase(directory, case.caseId).ready)
        assertCategory("CAPTURE_INCOMPLETE") { recorded().decode(directory) }
    }

    @Test
    fun `finishes a held window on resume once the confirmed tip has reached its end`() {
        val windows = listOf(window(MINT, 1001, 1012, SOURCE))
        tip = 1011
        assertEquals(MarketWindowCoverage.HORIZON_NOT_ELAPSED, capture(request(windows)) { _, _ -> error("Not listed yet") }.manifest.windows.single().coverage)

        tip = 1012
        val resumed = capture(request(windows)) { _, _ -> listOf(entry(1, 1012), entry(2, 1003), entry(3, 1000)) }

        assertEquals(MarketWindowCoverage.COMPLETE, resumed.manifest.windows.single().coverage)
        assertEquals(1012, resumed.manifest.windows.single().listedAtTip)
        assertEquals(listOf(2, 1).map(::signature), fetched)
        assertNull(resumed.stopReason)
        assertTrue(resumed.manifest.complete)
        assertEquals(resumed.manifest, recorded().inspect(directory).manifest)
    }

    @Test
    fun `completes the windows the tip has passed and holds the rest in one invocation reading the tip once`() {
        val held = window(OTHER_MINT, 1001, 1020, SOURCE)
        val elapsed = window(MINT, 1001, 1012, SOURCE)
        tip = 1015
        val result = capture(request(listOf(held, elapsed))) { address, _ ->
            assertEquals(MINT, address)
            listOf(entry(1, 1004))
        }

        assertEquals(listOf(OTHER_MINT, MINT), result.manifest.windows.map { it.baseMint })
        assertEquals(listOf(MarketWindowCoverage.HORIZON_NOT_ELAPSED, MarketWindowCoverage.COMPLETE), result.manifest.windows.map { it.coverage })
        assertEquals(1, tipRequests.size)
        assertEquals(listOf(signature(1)), fetched)
        assertEquals("HORIZON_NOT_ELAPSED", result.stopReason)
        assertEquals(Duration.ofMillis(1_100), result.stopRetryAfter)
        assertFalse(result.manifest.acquisitionComplete)
        assertEquals(result.manifest, recorded().inspect(directory).manifest)
    }

    @Test
    fun `without a slot sample the wait for a held window uses the nominal 400 ms per slot`() {
        tip = 1002
        paceSamples = "[]"

        val result = capture(request(listOf(window(MINT, 1001, 1012, SOURCE)))) { _, _ -> error("A window before its end is never listed") }

        assertEquals(Duration.ofMillis(4_400), result.stopRetryAfter)
    }

    @Test
    fun `fetches details oldest first by slot then signature while records keep the listing order and full manifests stay version 2`() {
        val page = listOf(entry(1, 1010), entry(2, 1003), entry(4, 1003), entry(3, 1003), entry(5, 1001))
        val result = capture(request(listOf(window(MINT, 1001, 1012, SOURCE)))) { _, _ -> page }

        val sameSlot = listOf(2, 4, 3).map(::signature).sorted()
        assertEquals(listOf(signature(5)) + sameSlot + signature(1), fetched)
        assertEquals(listOf(1, 2, 4, 3, 5).map(::signature), result.manifest.records.map { it.signature })
        assertEquals(2, result.manifest.schemaVersion)
        assertEquals(null, result.manifest.windows.single().cutoff)
        assertFalse(Files.readString(directory.resolve("market-manifest.json")).contains("cutoff"))
    }

    @Test
    fun `completes a window at a short page that reached the until signature`() {
        val result = capture(request(listOf(window(MINT, 1001, 1012, SOURCE)))) { _, _ -> listOf(entry(1, 1003), entry(2, 1002)) }
        assertEquals(MarketWindowCoverage.COMPLETE, result.manifest.windows.single().coverage)
        assertEquals(listOf(2, 1).map(::signature), fetched)
        assertTrue(result.manifest.complete)
    }

    @Test
    fun `scans full pages of newer entries before the cursor until the window is reached`() {
        val newer = (1..1000).map { entry(it, 5000L - it) }
        val result = capture(request(listOf(window(MINT, 1001, 1012, SOURCE)))) { _, before ->
            if (before == null) newer else listOf(entry(1001, 1004), entry(1002, 900))
        }
        val window = result.manifest.windows.single()
        assertEquals(MarketWindowCoverage.COMPLETE, window.coverage)
        assertEquals(2, window.pagesScanned)
        assertEquals(1000, window.skippedNewer)
        assertEquals(signature(1000), listings.last()["before"])
        assertEquals(listOf(signature(1001)), fetched)
    }

    @Test
    fun `marks a window coverage incomplete at the page bound and fetches none of its details`() {
        val busy = window(MINT, 1001, 1012, SOURCE)
        val quiet = window(OTHER_MINT, 1001, 1012, SOURCE)
        val result = capture(request(listOf(busy, quiet), scanPages = 2)) { address, before ->
            if (address == OTHER_MINT) listOf(entry(9001, 1002))
            else (1..1000).map { entry(it + (if (before == null) 0 else 1000), 9000L - it - (if (before == null) 0 else 1000)) }
        }
        val windows = result.manifest.windows.associateBy { it.baseMint }
        assertEquals(MarketWindowCoverage.COVERAGE_INCOMPLETE, windows.getValue(MINT).coverage)
        assertEquals(2, windows.getValue(MINT).pagesScanned)
        assertEquals(emptyList(), windows.getValue(MINT).selection)
        assertEquals(MarketWindowCoverage.COMPLETE, windows.getValue(OTHER_MINT).coverage)
        assertEquals(2, listedAddresses.count { it == MINT })
        assertEquals(listOf(signature(9001)), fetched)
        assertTrue(result.manifest.acquisitionComplete)
        assertFalse(result.manifest.complete)
    }

    @Test
    fun `extends a coverage incomplete window from its saved cursor when resumed with more scan pages`() {
        val windows = listOf(window(MINT, 1001, 1012, SOURCE))
        val listing: (String, String?) -> List<String> = { _, before ->
            when (before) {
                null -> (1..1000).map { entry(it, 9000L - it) }
                signature(1000) -> (1001..2000).map { entry(it, 9000L - it) }
                signature(2000) -> listOf(entry(2001, 1005), entry(2002, 1003, failed = true), entry(2003, 900))
                else -> error("Unexpected cursor")
            }
        }
        val bounded = capture(request(windows, scanPages = 2), listing).manifest
        assertEquals(MarketWindowCoverage.COVERAGE_INCOMPLETE, bounded.windows.single().coverage)
        assertEquals(MarketListedSignature(signature(2000), 7000), bounded.windows.single().scanCursor)

        val extended = capture(request(windows, scanPages = 4), listing).manifest
        val window = extended.windows.single()
        assertEquals(MarketWindowCoverage.COMPLETE, window.coverage)
        assertEquals(4, extended.scanPageLimit)
        assertEquals(3, window.pagesScanned)
        assertEquals(2002, window.scanned)
        assertEquals(2000, window.skippedNewer)
        assertEquals(1, window.skippedFailed)
        assertEquals(listOf(null, signature(1000), signature(2000)), listings.map { it["before"] })
        assertEquals(listOf(signature(2001)), fetched)
        assertTrue(extended.complete)
        assertEquals(extended, recorded().inspect(directory).manifest)
    }

    @Test
    fun `keeps in-window selections of a window at the page bound and admits them once extended`() {
        val windows = listOf(window(MINT, 1001, 1012, SOURCE))
        val listing: (String, String?) -> List<String> = { _, before ->
            if (before == null) (1..998).map { entry(it, 9000L - it) } + listOf(entry(999, 1010), entry(1000, 1009))
            else listOf(entry(1001, 1005), entry(1002, 900))
        }
        val bounded = capture(request(windows, scanPages = 1), listing).manifest
        assertEquals(MarketWindowCoverage.COVERAGE_INCOMPLETE, bounded.windows.single().coverage)
        assertEquals(listOf(999, 1000).map(::signature), bounded.windows.single().selection.map { it.signature })
        assertEquals(emptyList(), bounded.records)
        assertEquals(emptyList(), fetched)
        assertEquals(bounded, recorded().inspect(directory).manifest)

        val extended = capture(request(windows, scanPages = 2), listing).manifest
        assertEquals(MarketWindowCoverage.COMPLETE, extended.windows.single().coverage)
        assertEquals(listOf(999, 1000, 1001).map(::signature), extended.records.map { it.signature })
        assertEquals(listOf(1001, 1000, 999).map(::signature), fetched)
        assertTrue(extended.complete)
    }

    @Test
    fun `extends an earlier window after a later one completed and keeps records in window order`() {
        val windows = listOf(window(OTHER_MINT, 1001, 1012, SOURCE), window(MINT, 1001, 1012, SOURCE))
        val listing: (String, String?) -> List<String> = { address, before ->
            when {
                address == MINT -> listOf(entry(5001, 1004))
                before == null -> (1..1000).map { entry(it, 9000L - it) }
                else -> listOf(entry(1001, 1006), entry(1002, 900))
            }
        }
        val bounded = capture(request(windows, scanPages = 1), listing).manifest
        assertEquals(listOf(MarketWindowCoverage.COVERAGE_INCOMPLETE, MarketWindowCoverage.COMPLETE), bounded.windows.map { it.coverage })
        assertEquals(listOf(signature(5001)), fetched)

        val extended = capture(request(windows, scanPages = 2), listing).manifest
        assertEquals(List(2) { MarketWindowCoverage.COMPLETE }, extended.windows.map { it.coverage })
        assertEquals(listOf(1001, 5001).map(::signature), extended.records.map { it.signature })
        assertEquals(listOf(5001, 1001).map(::signature), fetched)
        assertEquals(1, listedAddresses.count { it == MINT })
        assertTrue(extended.complete)
        assertEquals(extended, recorded().inspect(directory).manifest)
    }

    @Test
    fun `an extended window whose kept selection exceeds the remaining cap is over the cap and the capture stays loadable`() {
        val windows = listOf(window(OTHER_MINT, 1001, 1012, SOURCE), window(MINT, 1001, 1012, SOURCE))
        val listing: (String, String?) -> List<String> = { address, before ->
            when {
                address == MINT -> listOf(entry(5001, 1006), entry(5002, 1004))
                before == null -> (1..998).map { entry(it, 9000L - it) } + listOf(entry(999, 1010), entry(1000, 1009))
                else -> listOf(entry(1001, 900))
            }
        }
        val bounded = capture(request(windows, scanPages = 1, maxTransactions = 3), listing).manifest
        assertEquals(listOf(MarketWindowCoverage.COVERAGE_INCOMPLETE, MarketWindowCoverage.COMPLETE), bounded.windows.map { it.coverage })

        val extended = capture(request(windows, scanPages = 2, maxTransactions = 3), listing).manifest

        assertEquals(listOf(MarketWindowCoverage.OVER_CAP, MarketWindowCoverage.COMPLETE), extended.windows.map { it.coverage })
        assertEquals(listOf(5001, 5002).map(::signature), extended.records.map { it.signature })
        assertEquals(listOf(5002, 5001).map(::signature), fetched)
        assertEquals(extended, recorded().inspect(directory).manifest)
    }

    @Test
    fun `accepts up to 500 scan pages per window`() {
        val result = capture(request(listOf(window(MINT, 1001, 1012, SOURCE)), scanPages = 500)) { _, _ -> emptyList() }
        assertEquals(500, result.manifest.scanPageLimit)
        assertTrue(result.manifest.complete)
    }

    @Test
    fun `records a window over the transaction cap without fetching any of its details`() {
        val first = window(OTHER_MINT, 1001, 1012, SOURCE)
        val second = window(MINT, 1001, 1012, SOURCE)
        val result = capture(request(listOf(first, second), maxTransactions = 3)) { address, _ ->
            if (address == OTHER_MINT) listOf(entry(1, 1010), entry(2, 1009))
            else listOf(entry(3, 1008), entry(4, 1007), entry(5, 1006))
        }
        val windows = result.manifest.windows.associateBy { it.baseMint }
        assertEquals(MarketWindowCoverage.COMPLETE, windows.getValue(OTHER_MINT).coverage)
        assertEquals(MarketWindowCoverage.OVER_CAP, windows.getValue(MINT).coverage)
        assertEquals(emptyList(), windows.getValue(MINT).selection)
        assertEquals(2, windows.getValue(MINT).selected)
        assertEquals(listOf(2, 1).map(::signature), fetched)
        assertEquals(listOf(1, 2).map(::signature), result.manifest.records.map { it.signature })
        assertTrue(result.manifest.acquisitionComplete)
        assertFalse(result.manifest.complete)
        assertEquals(result.manifest, recorded().inspect(directory).manifest)
    }

    @Test
    fun `fetches a transaction shared by two windows once and counts it against the cap once`() {
        val windows = listOf(window(MINT, 1001, 1012, SOURCE), window(OTHER_MINT, 1001, 1012, SOURCE))
        val result = capture(request(windows, maxTransactions = 2)) { address, _ ->
            if (address == MINT) listOf(entry(1, 1010), entry(2, 1009)) else listOf(entry(2, 1009))
        }
        assertEquals(List(2) { MarketWindowCoverage.COMPLETE }, result.manifest.windows.map { it.coverage })
        assertEquals(listOf(OTHER_MINT, MINT), result.manifest.windows.map { it.baseMint })
        assertEquals(listOf(2, 1).map(::signature), fetched)
        assertTrue(result.manifest.complete)
    }

    @Test
    fun `checkpoints a completed window before listing the next one`() {
        val windows = listOf(window(MINT, 1001, 1012, SOURCE), window(OTHER_MINT, 1001, 1012, SOURCE))
        val ordered = windows.sortedBy { it.baseMint.value }
        var laterListingSawCapturedDetails = false

        capture(request(windows)) { address, _ ->
            if (address == ordered[1].baseMint.value) {
                val saved = recorded().inspect(directory).manifest
                assertEquals(MarketTransactionOutcome.CAPTURED, saved.records.single().outcome)
                laterListingSawCapturedDetails = true
            }
            listOf(entry(if (address == ordered[0].baseMint.value) 1 else 2, 1010))
        }

        assertTrue(laterListingSawCapturedDetails)
    }

    @Test
    fun `rejects listing pages out of order with conflicting duplicates or repeating the cursor`() {
        val cases = mapOf(
            "SIGNATURE_ORDER" to listOf(entry(1, 1003), entry(2, 1004)),
            "CONFLICTING_SIGNATURE" to listOf(entry(1, 1004), entry(1, 1003)),
        )
        cases.entries.forEachIndexed { index, (category, page) ->
            assertCategory(category) {
                capture(request(listOf(window(MINT, 1001, 1012, SOURCE)), directory.resolve("case$index"))) { _, _ -> page }
            }
        }
        val full = (1..1000).map { entry(it, 5000L - it) }
        assertCategory("NON_PROGRESSING_PAGE") {
            capture(request(listOf(window(MINT, 1001, 1012, SOURCE)), directory.resolve("cursor"))) { _, before ->
                if (before == null) full else listOf(entry(1000, 4000), entry(2000, 1002))
            }
        }
        assertEquals(emptyList(), fetched)
    }

    @Test
    fun `resumes an interrupted window from its cursor without admitting newer signatures`() {
        val newer = (1..1000).map { entry(it, 5000L - it) }
        var failSecondPage = true
        val service = service { method, params, _ ->
            calls += method
            when (method) {
                "getGenesisHash" -> genesis()
                "getSignaturesForAddress" -> {
                    val options = params[1] as Map<*, *>
                    listings += options
                    when {
                        options["before"] == null -> page(newer)
                        failSecondPage -> throw CaptureProviderException("UNAVAILABLE")
                        else -> page(listOf(entry(1001, 1004), entry(1002, 900)))
                    }
                }
                "getTransaction" -> transaction((params[0] as String), 1004)
                else -> error("Unexpected method")
            }
        }
        val request = request(listOf(window(MINT, 1001, 1012, SOURCE)))
        val interrupted = service.capture(request).manifest
        assertEquals(MarketWindowCoverage.PENDING, interrupted.windows.single().coverage)
        assertEquals(MarketListedSignature(signature(1000), 4000), interrupted.windows.single().scanCursor)
        assertFalse(interrupted.acquisitionComplete)
        assertEquals(interrupted, recorded().inspect(directory).manifest)

        failSecondPage = false
        val resumed = service.capture(request).manifest
        assertEquals(1, listings.count { it["before"] == null })
        assertEquals(signature(1000), listings.last()["before"])
        assertEquals(MarketWindowCoverage.COMPLETE, resumed.windows.single().coverage)
        assertEquals(listOf(signature(1001)), resumed.records.map { it.signature })
        assertTrue(resumed.complete)
    }

    @Test
    fun `refuses a resumed page that is newer than the saved cursor`() {
        val newer = (1..1000).map { entry(it, 5000L - it) }
        var resumed = false
        val service = service { method, params, _ ->
            when (method) {
                "getGenesisHash" -> genesis()
                "getSignaturesForAddress" -> when {
                    (params[1] as Map<*, *>)["before"] == null -> page(newer)
                    !resumed -> throw CaptureProviderException("UNAVAILABLE")
                    else -> page(listOf(entry(3000, 4500), entry(3001, 1003)))
                }
                else -> error("Unexpected method")
            }
        }
        val request = request(listOf(window(MINT, 1001, 1012, SOURCE)))
        service.capture(request)
        resumed = true
        assertCategory("SIGNATURE_ORDER") { service.capture(request) }
    }

    @Test
    fun `refetches only unresolved transaction details when resumed`() {
        var available = false
        val service = service { method, params, _ ->
            when (method) {
                "getGenesisHash" -> genesis()
                "getSignaturesForAddress" -> page(listOf(entry(1, 1003), entry(2, 1002), entry(3, 1001)))
                "getTransaction" -> {
                    val signature = params[0] as String
                    fetched += signature
                    if (signature == signature(2) && !available) throw CaptureProviderException("UNAVAILABLE", Duration.ofSeconds(17))
                    transaction(signature, slotOf.getValue(signature))
                }
                else -> error("Unexpected method")
            }
        }
        val request = request(listOf(window(MINT, 1001, 1012, SOURCE)))
        val stopped = service.capture(request)
        val first = stopped.manifest
        assertEquals(listOf(MarketTransactionOutcome.PENDING, MarketTransactionOutcome.PROVIDER_UNAVAILABLE, MarketTransactionOutcome.CAPTURED),
            first.records.map { it.outcome })
        assertEquals(Duration.ofSeconds(17), stopped.stopRetryAfter)
        available = true
        val second = service.capture(request).manifest
        assertEquals(listOf(3, 2, 2, 1).map(::signature), fetched)
        assertTrue(second.complete)
    }

    @Test
    fun `stops at the request budget and keeps cumulative usage across resumes`() {
        val service = service { method, params, budget ->
            budget.request()
            when (method) {
                "getGenesisHash" -> genesis()
                "getSignaturesForAddress" -> page(listOf(entry(1, 1003), entry(2, 1002)))
                "getTransaction" -> transaction(params[0] as String, slotOf.getValue(params[0] as String))
                else -> error("Unexpected method")
            }
        }
        val windows = listOf(window(MINT, 1001, 1012, SOURCE))
        val limited = service.capture(request(windows, requestBudget = 4)).manifest
        assertEquals(4, limited.requestsUsed)
        assertEquals(listOf(MarketTransactionOutcome.PENDING, MarketTransactionOutcome.CAPTURED), limited.records.map { it.outcome })
        assertFalse(limited.acquisitionComplete)
        val resumed = service.capture(request(windows, requestBudget = 4)).manifest
        assertEquals(6, resumed.requestsUsed)
        assertTrue(resumed.complete)
    }

    @Test
    fun `classifies details by listing identity structure and unsafe text without any wallet membership`() {
        val cases = listOf(
            "null" to MarketTransactionOutcome.UNAVAILABLE,
            transactionJson(signature(1), 1003) to MarketTransactionOutcome.CAPTURED,
            transactionJson(signature(1), 1004) to MarketTransactionOutcome.INVALID_TRANSACTION,
            transactionJson(signature(1), 1003, err = "\"AccountInUse\"") to MarketTransactionOutcome.INVALID_TRANSACTION,
            transactionJson(signature(1), 1003, logs = "[\"Program log: visit https://spam.invalid\"]") to MarketTransactionOutcome.WITHHELD_UNSAFE_TEXT,
            transactionJson(signature(1), 1003, version = "2") to MarketTransactionOutcome.UNSUPPORTED_VERSION,
        )
        cases.forEachIndexed { index, (json, outcome) ->
            val target = directory.resolve("case$index")
            val manifest = service { method, _, _ -> when (method) {
                "getGenesisHash" -> genesis()
                "getSignaturesForAddress" -> page(listOf(entry(1, 1003)))
                "getTransaction" -> mapper.readTree(json)
                else -> error("Unexpected method")
            } }.capture(request(listOf(window(MINT, 1001, 1012, SOURCE)), target)).manifest
            val record = manifest.records.single()
            assertEquals(outcome, record.outcome, json)
            assertEquals(outcome == MarketTransactionOutcome.CAPTURED, record.artifact != null)
            assertEquals(record.artifact != null, Files.exists(target.resolve("transactions/${signature(1)}.json")))
            assertEquals(outcome != MarketTransactionOutcome.INVALID_TRANSACTION, manifest.acquisitionComplete)
            assertEquals(manifest, recorded().inspect(target).manifest)
        }
    }

    @Test
    fun `records an unsupported version reported by the provider as a resolved gap`() {
        val manifest = service { method, _, _ -> when (method) {
            "getGenesisHash" -> genesis()
            "getSignaturesForAddress" -> page(listOf(entry(1, 1003)))
            "getTransaction" -> throw CaptureProviderException("UNSUPPORTED_VERSION")
            else -> error("Unexpected method")
        } }.capture(request(listOf(window(MINT, 1001, 1012, SOURCE)))).manifest
        assertEquals(MarketTransactionOutcome.UNSUPPORTED_VERSION, manifest.records.single().outcome)
        assertTrue(manifest.complete)
    }

    @Test
    fun `reports a deadline stop as its own category and resumes on the next invocation`() {
        var deadline = true
        val service = service { method, params, budget ->
            if (method == "getTransaction" && deadline) throw CaptureBudgetException("DEADLINE")
            budget.request()
            when (method) {
                "getGenesisHash" -> genesis()
                "getSignaturesForAddress" -> page(listOf(entry(1, 1003), entry(2, 1002)))
                "getTransaction" -> transaction(params[0] as String, slotOf.getValue(params[0] as String))
                else -> error("Unexpected method")
            }
        }
        val request = request(listOf(window(MINT, 1001, 1012, SOURCE))).copy(operationTimeout = Duration.ofMinutes(30))

        val stopped = service.capture(request)
        assertEquals("DEADLINE", stopped.stopReason)
        assertFalse(stopped.manifest.acquisitionComplete)
        deadline = false
        val resumed = service.capture(request)
        assertNull(resumed.stopReason)
        assertTrue(resumed.manifest.complete)
    }

    @Test
    fun `a request budget stop reports its own category`() {
        val service = service { method, params, budget ->
            budget.request()
            when (method) {
                "getGenesisHash" -> genesis()
                "getSignaturesForAddress" -> page(listOf(entry(1, 1003), entry(2, 1002)))
                "getTransaction" -> transaction(params[0] as String, slotOf.getValue(params[0] as String))
                else -> error("Unexpected method")
            }
        }

        assertEquals("REQUESTS", service.capture(request(listOf(window(MINT, 1001, 1012, SOURCE)), requestBudget = 3)).stopReason)
    }

    @Test
    fun `validates request bounds and windows before any provider request`() {
        val valid = listOf(window(MINT, 1001, 1012, SOURCE))
        val invalid = listOf(
            request(valid, scanPages = 0), request(valid, scanPages = 501),
            request(valid, maxTransactions = 0), request(valid, maxTransactions = 50_001),
            request(valid, requestBudget = 0), request(valid, requestBudget = 60_001),
            request(valid, byteBudget = 0), request(valid, byteBudget = 4L * 1024 * 1024 * 1024 + 1),
            request(emptyList()),
            request(listOf(window(MINT, 1001, 1012, SOURCE), window(MINT, 1012, 1020, SOURCE))),
            request(listOf(window(MINT, 1001, 1012, SOURCE), window(MINT, 1013, 1020, SOURCE))),
            request((1..101).map { window(MINT, it * 100L, it * 100L + 12, SOURCE) }),
            request(valid, parameters = PARAMETERS.copy(horizonSlots = 0)),
            request(valid, parameters = PARAMETERS.copy(horizonSlots = 4501)),
            request(valid, parameters = PARAMETERS.copy(maxAgeSlots = -1)),
            request(valid).copy(operationTimeout = Duration.ofSeconds(30)),
            request(valid).copy(operationTimeout = Duration.ofMinutes(31)),
            request(valid).copy(operationTimeout = Duration.ofSeconds(90)),
        )
        val service = service { method, _, _ -> calls += method; error("Must not call provider") }
        invalid.forEachIndexed { index, request ->
            val failure = assertFailsWith<CaptureValidationException>("case $index") { service.capture(request) }
            assertTrue(failure.message.orEmpty().endsWith("category=REQUEST_BOUNDS"), failure.message)
        }
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `stores windows in canonical order with the window parameters and refuses a different resume identity`() {
        val windows = listOf(window(OTHER_MINT, 2001, 2012, SOURCE), window(MINT, 1001, 1012, SOURCE))
        val first = capture(request(windows)) { _, _ -> emptyList() }.manifest
        assertEquals(listOf(MINT, OTHER_MINT).sorted(), first.windows.map { it.baseMint })
        assertEquals(PARAMETERS, first.windowParameters)
        assertEquals(List(2) { MintAddress.WRAPPED_SOL.value }, first.windows.map { it.quoteMint })
        assertEquals(2, first.schemaVersion)
        assertEquals(20, first.scanPageLimit)
        assertEquals(300, first.maxTransactions)
        val callsBefore = calls.size
        listOf(
            request(windows.take(1)),
            request(windows, scanPages = 3),
            request(windows, maxTransactions = 30),
            request(windows, parameters = PARAMETERS.copy(horizonSlots = 4499)),
        ).forEach { changed -> assertCategory("RESUME_IDENTITY") { capture(changed) { _, _ -> emptyList() } } }
        assertEquals(callsBefore, calls.size)
        assertTrue(capture(request(windows.reversed())) { _, _ -> emptyList() }.manifest.complete)
    }

    @Test
    fun `requires mainnet genesis before discovery`() {
        val service = service { method, _, _ -> calls += method; mapper.readTree("\"different-network\"") }
        assertCategory("MAINNET_REQUIRED") { service.capture(request(listOf(window(MINT, 1001, 1012, SOURCE)))) }
        assertEquals(listOf("getGenesisHash"), calls)
    }

    @Test
    fun `refuses a concurrent capture into the same directory before any provider call`() {
        val request = request(listOf(window(MINT, 1001, 1012, SOURCE)))
        FileChannel.open(directory.resolve(".capture.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { assertCategory("CAPTURE_IN_PROGRESS") { capture(request) { _, _ -> emptyList() } } }
        }
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `an edited manifest is refused for inspection and resume before any provider call`() {
        val request = request(listOf(window(MINT, 1001, 1012, SOURCE)))
        capture(request) { _, _ -> listOf(entry(1, 1003)) }
        val manifestFile = directory.resolve("market-manifest.json")
        Files.writeString(manifestFile, Files.readString(manifestFile).replace("\"complete\":true", "\"complete\":false"))
        val callsBefore = calls.size
        assertCategory("MANIFEST_HASH") { recorded().inspect(directory) }
        assertCategory("MANIFEST_HASH") { capture(request) { _, _ -> emptyList() } }
        assertEquals(callsBefore, calls.size)
    }

    @Test
    fun `checkpoint observes persisted evidence and distinguishes invocation usage on resume`() {
        val checkpoints = mutableListOf<com.trenching.bot.chaincapture.CaptureCheckpoint>()
        val rpc = SolanaReadRpc { method, params, budget ->
            budget.request()
            when (method) {
                "getGenesisHash" -> genesis()
                "getSignaturesForAddress" -> page(listOf(entry(1, 1003), entry(2, 1002)))
                "getTransaction" -> transaction(params[0] as String, slotOf.getValue(params[0] as String))
                else -> error("Unexpected method")
            }
        }
        val service = MarketHistoryCaptureService(withTip(rpc), MarketHistoryArtifactStore(mapper), CLOCK) { checkpoint ->
            assertEquals(checkpoint.cumulativeRequests, recorded().inspect(directory).manifest.requestsUsed)
            checkpoints += checkpoint
        }
        val request = request(listOf(window(MINT, 1001, 1012, SOURCE)), requestBudget = 3)
        service.capture(request)
        assertEquals(3, checkpoints.last().operationRequests)
        assertEquals(3, checkpoints.last().cumulativeRequests)
        service.capture(request)
        assertEquals(3, checkpoints.last().operationRequests)
        assertEquals(6, checkpoints.last().cumulativeRequests)
        assertEquals("MARKET_COMPLETE", checkpoints.last().phase)
    }

    @Test
    fun `observer failure surfaces after persisted checkpoint and leaves evidence resumable`() {
        val rpc = SolanaReadRpc { method, _, budget ->
            budget.request()
            assertEquals("getGenesisHash", method)
            genesis()
        }
        val service = MarketHistoryCaptureService(withTip(rpc), MarketHistoryArtifactStore(mapper), CLOCK) { error("progress unavailable") }
        assertFailsWith<IllegalStateException> { service.capture(request(listOf(window(MINT, 1001, 1012, SOURCE)))) }
        assertEquals(1, recorded().inspect(directory).manifest.requestsUsed)
    }
    private fun capture(request: MarketHistoryCaptureRequest, listing: (String, String?) -> List<String>): MarketHistoryCaptureResult =
        service { method, params, _ ->
            calls += method
            when (method) {
                "getGenesisHash" -> genesis()
                "getSignaturesForAddress" -> {
                    val options = params[1] as Map<*, *>
                    listings += options
                    listedAddresses += params[0] as String
                    page(listing(params[0] as String, options["before"] as String?))
                }
                "getTransaction" -> {
                    val signature = params[0] as String
                    fetched += signature
                    transaction(signature, slotOf.getValue(signature))
                }
                else -> error("Unexpected method")
            }
        }.capture(request)

    private fun service(rpc: SolanaReadRpc): MarketHistoryCapture = MarketHistoryCaptureService(withTip(rpc), MarketHistoryArtifactStore(mapper), CLOCK)

    /**
     * Answers the confirmed tip read with [tip] and the slot pace read with [paceSamples], each charged like any request, and
     * passes every other call to [rpc].
     */
    private fun withTip(rpc: SolanaReadRpc) = SolanaReadRpc { method, params, budget ->
        when (method) {
            "getSlot" -> {
                budget.request()
                tipRequests += params
                mapper.readTree(tip.toString())
            }
            "getRecentPerformanceSamples" -> {
                budget.request()
                paceRequests++
                mapper.readTree(paceSamples)
            }
            else -> rpc.call(method, params, budget)
        }
    }

    private fun recorded(): RecordedMarketHistory = configuration.recordedMarketHistory(mapper)

    private fun request(
        windows: List<MarketCaptureWindow>,
        target: Path = directory,
        scanPages: Int = 20,
        maxTransactions: Int = 300,
        requestBudget: Int = 400,
        byteBudget: Long = 104857600,
        parameters: MarketCaptureWindowParameters = PARAMETERS,
    ) = MarketHistoryCaptureRequest(target, windows, parameters, scanPages, maxTransactions, requestBudget, byteBudget)

    private fun window(mint: String, from: Long, to: Long, until: Int) =
        MarketCaptureWindow.of(MintAddress.of(mint), Slot.of(from), Slot.of(to), TransactionSignature.of(signature(until)))

    private fun entry(index: Int, slot: Long, failed: Boolean = false): String {
        slotOf[signature(index)] = slot
        val err = if (failed) """{"InstructionError":[0,{"Custom":6001}]}""" else "null"
        return """{"signature":"${signature(index)}","slot":$slot,"err":$err,"memo":null,"blockTime":1791049629}"""
    }

    private fun page(entries: List<String>): JsonNode = mapper.readTree(entries.joinToString(",", "[", "]"))

    private fun genesis(): JsonNode = mapper.readTree("\"$GENESIS\"")

    private fun transaction(signature: String, slot: Long): JsonNode = mapper.readTree(transactionJson(signature, slot))

    private fun transactionJson(signature: String, slot: Long, err: String = "null", version: String = "1", logs: String = "[]") =
        """{"slot":$slot,"blockTime":1791049629,"version":$version,"meta":{"err":$err,"fee":5000,"preBalances":[1000000],"postBalances":[995000],"logMessages":$logs},""" +
            """"transaction":{"signatures":["$signature"],"message":{"accountKeys":[{"pubkey":"$OTHER_MINT","signer":true,"writable":true}],""" +
            """"instructions":[{"programId":"$OTHER_MINT","accounts":[],"data":""}],"recentBlockhash":"$OTHER_MINT"}}}"""

    private fun assertCategory(category: String, action: () -> Unit) {
        val failure = assertFailsWith<CaptureValidationException> { action() }
        assertTrue(failure.message.orEmpty().endsWith("category=$category"), failure.message)
    }

    private fun signature(index: Int): String =
        Base58.encode(ByteArray(64).also { bytes -> bytes[61] = (index shr 16).toByte(); bytes[62] = (index shr 8).toByte(); bytes[63] = index.toByte() })

    private companion object {
        const val GENESIS = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"
        const val MINT = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
        const val OTHER_MINT = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
        const val SOURCE = 77_777
        val PARAMETERS = MarketCaptureWindowParameters(horizonSlots = 4500, maxAgeSlots = 2)
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC)
    }
}
