package com.trenching.bot.marketdata

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.Base58
import com.trenching.bot.common.Commitment
import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.marketdata.domain.CurveState
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.ObservationQuality
import com.trenching.bot.marketdata.infrastructure.capture.MarketHistoryArtifactStore
import com.trenching.bot.protocol.pumpfun.CurveReserves
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

class RecordedMarketHistoryTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val store = MarketHistoryArtifactStore(mapper)
    private val recorded = MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper)

    @Test
    fun `the recorded real buy yields exactly one LAST_TRADE observation as the ADR specifies`() {
        val record = artifact(fixture())
        val window = complete(MINT, listOf(record))
        save(listOf(window), listOf(record))

        val result = recorded.decode(directory)

        val expected = MarketObservation(
            id = "pumpfun-bonding-curve-trade/v1:$SIGNATURE:1:7:$MINT",
            pair = MarketPair.of(MintAddress.of(MINT), MintAddress.WRAPPED_SOL),
            slot = Slot.of(SLOT),
            priceSlot = Slot.of(SLOT),
            observedTime = Instant.ofEpochSecond(1791049629),
            price = BigDecimal("0.00000019736807582073671197813450558296"),
            liquidityQuote = null,
            source = "pumpfun-bonding-curve-trade/v1",
            sourceEventId = "$SIGNATURE:1:7",
            commitment = Commitment.CONFIRMED,
            quality = ObservationQuality.LAST_TRADE,
            curveComplete = false,
            curve = CurveState(
                CurveReserves(BigInteger("79829770982"), BigInteger("403233026450985"), BigInteger("49829770982"), BigInteger("123333026450985")),
                feeBps = 95,
                creatorFeeBps = 30,
            ),
        )
        assertEquals(listOf(expected), result.observations)
        assertEquals(38, result.observations.single().price.scale())
        assertEquals(MarketObservationExclusion.entries.associateWith { 0 }, result.exclusions)
        assertEquals(listOf(MarketTransactionDecodeRecord(TransactionSignature.of(SIGNATURE), Slot.of(SLOT), MarketTransactionOutcome.CAPTURED, 1, 0)),
            result.transactions)
        assertEquals(listOf(MarketWindowDecodeRecord(windowValue(MINT), MarketWindowCoverage.COMPLETE, 1, 0, true, 1)), result.windows)
        assertEquals(MarketObservationExtractorVersion.PUMPFUN_BONDING_CURVE_TRADE_V1, result.extractor)
        assertEquals(PARAMETERS, result.windowParameters)
        assertTrue(result.complete)
        assertEquals(directory.toAbsolutePath().normalize(), result.directory)
    }

    @Test
    fun `every exclusion reason is counted and an event of a mint outside every window only counts as out of window`() {
        val variants = listOf<(ObjectNode) -> Unit>(
            {},
            { rewriteEvent(it) { bytes -> writeU64(bytes, SOL_AMOUNT_OFFSET, BigInteger.ZERO) } },
            { rewriteEvent(it) { bytes -> writeU64(bytes, TOKEN_AMOUNT_OFFSET, BigInteger.ZERO) } },
            { rewriteEvent(it) { bytes -> bytes[IS_BUY_OFFSET] = 2 } },
            { truncateEvent(it, 100) },
            { rewriteEvent(it) { bytes -> writeKey(bytes, MintAddress.WRAPPED_SOL.value) } },
            { rewriteEvent(it) { bytes -> writeKey(bytes, OTHER_MINT) } },
            { it.putNull("blockTime") },
            { removeMintBalances(it) },
            { (mintBalances(it, "postTokenBalances").first().path("uiTokenAmount") as ObjectNode).put("decimals", 9) },
            { tx -> listOf("preTokenBalances", "postTokenBalances").forEach { field ->
                mintBalances(tx, field).forEach { (it.path("uiTokenAmount") as ObjectNode).put("decimals", 255) } } },
            { rewriteEvent(it) { bytes -> writeKey(bytes, OTHER_MINT); writeU64(bytes, SOL_AMOUNT_OFFSET, BigInteger.ZERO) } },
            { rewriteEvent(it) { bytes -> writeKey(bytes, OTHER_MINT); bytes[IS_BUY_OFFSET] = 2 } },
        )
        val records = variants.mapIndexed { index, mutate ->
            artifact(fixture(signature(index + 1), SLOT + index).also(mutate))
        }.reversed()
        save(listOf(complete(MINT, records)), records)

        val result = recorded.decode(directory)

        assertEquals(1, result.observations.size)
        assertEquals("${signature(1)}:1:7", result.observations.single().sourceEventId)
        assertEquals(mapOf(
            MarketObservationExclusion.ZERO_AMOUNT to 2,
            MarketObservationExclusion.MALFORMED_EVENT to 2,
            MarketObservationExclusion.QUOTE_MINT to 1,
            MarketObservationExclusion.DECIMALS_UNPROVEN to 2,
            MarketObservationExclusion.BLOCK_TIME_MISSING to 1,
            MarketObservationExclusion.OUT_OF_WINDOW to 3,
            MarketObservationExclusion.EXCEEDS_REPLAY_BOUNDS to 1,
        ), result.exclusions)
        assertEquals(MarketObservationExclusion.entries, result.exclusions.keys.toList())
        assertEquals(List(12) { 1 } + 0, result.transactions.map { it.exclusions })
    }

    @Test
    fun `several events in one transaction give distinct observations and a top-level event has a dash inner index`() {
        val transaction = fixture()
        val event = inner(transaction).path(EVENT_INNER_INDEX).deepCopy()
        inner(transaction).add(event.deepCopy())
        val topLevel = transaction.path("transaction").path("message").path("instructions") as ArrayNode
        topLevel.add(event.deepCopy())
        val record = artifact(transaction)
        save(listOf(complete(MINT, listOf(record))), listOf(record))

        val observations = recorded.decode(directory).observations

        val eventIds = listOf("$SIGNATURE:1:10", "$SIGNATURE:1:7", "$SIGNATURE:3:-")
        assertEquals(eventIds, observations.map { it.sourceEventId })
        assertEquals(eventIds.map { "pumpfun-bonding-curve-trade/v1:$it:$MINT" }, observations.map { it.id })
    }

    @Test
    fun `an event that exists only as a Program data log line is ignored`() {
        val transaction = fixture()
        assertTrue(transaction.path("meta").path("logMessages").any { it.asString().startsWith("Program data:") })
        inner(transaction).remove(EVENT_INNER_INDEX)
        val record = artifact(transaction)
        save(listOf(complete(MINT, listOf(record))), listOf(record))

        val result = recorded.decode(directory)

        assertEquals(emptyList(), result.observations)
        assertEquals(MarketObservationExclusion.entries.associateWith { 0 }, result.exclusions)
    }

    @Test
    fun `windows that are coverage incomplete or over the cap yield no observations`() {
        listOf(MarketWindowCoverage.COVERAGE_INCOMPLETE, MarketWindowCoverage.OVER_CAP).forEachIndexed { index, coverage ->
            val target = directory.resolve("case$index")
            val record = artifact(fixture(), target)
            val unusable = incomplete(MINT, coverage)
            val listedElsewhere = complete(OTHER_MINT, listOf(record))
            save(listOf(listedElsewhere, unusable), listOf(record), target)

            val result = recorded.decode(target)

            assertEquals(emptyList(), result.observations)
            assertEquals(1, result.exclusions.getValue(MarketObservationExclusion.OUT_OF_WINDOW))
            val windows = result.windows.associateBy { it.window.baseMint.value }
            assertFalse(windows.getValue(MINT).yieldsObservations)
            assertEquals(coverage, windows.getValue(MINT).coverage)
            assertFalse(result.complete)
        }
    }

    @Test
    fun `a complete window with an uncaptured in-window transaction yields no observations`() {
        val captured = artifact(fixture())
        val withheld = MarketTransactionRecord(signature(9), SLOT + 1, MarketTransactionOutcome.WITHHELD_UNSAFE_TEXT)
        val records = listOf(withheld, captured)
        save(listOf(complete(MINT, records)), records)

        val result = recorded.decode(directory)

        assertEquals(emptyList(), result.observations)
        assertEquals(MarketWindowDecodeRecord(windowValue(MINT), MarketWindowCoverage.COMPLETE, 2, 1, false, 0), result.windows.single())
        assertEquals(1, result.exclusions.getValue(MarketObservationExclusion.OUT_OF_WINDOW))
        assertTrue(result.complete)
    }

    @Test
    fun `observations are ordered by slot then id`() {
        val later = artifact(fixture(signature(1), SLOT + 4))
        val earlier = artifact(fixture(signature(2), SLOT))
        val records = listOf(later, earlier)
        save(listOf(complete(MINT, records)), records)

        val observations = recorded.decode(directory).observations

        assertEquals(listOf(SLOT, SLOT + 4), observations.map { it.slot.value })
        assertEquals(listOf(signature(2), signature(1)), observations.map { it.sourceEventId.substringBefore(':') })
    }

    @Test
    fun `decode refuses a capture whose acquisition has not finished`() {
        val pending = MarketTransactionRecord(SIGNATURE, SLOT, MarketTransactionOutcome.PENDING)
        save(listOf(complete(MINT, listOf(pending))), listOf(pending))
        assertCategory("CAPTURE_INCOMPLETE") { recorded.decode(directory) }
        assertFalse(recorded.inspect(directory).manifest.acquisitionComplete)
    }

    @Test
    fun `decoding one ready case ignores a corrupt captured sibling`() {
        val selected = artifact(fixture())
        val sibling = artifact(fixture(signature(2), SLOT + 31))
        val selectedWindow = complete(MINT, listOf(selected))
        val siblingWindow = complete(OTHER_MINT, listOf(sibling)).copy(fromSlot = FROM + 30, toSlot = TO + 30)
        save(listOf(selectedWindow, siblingWindow), listOf(selected, sibling))
        Files.writeString(directory.resolve(requireNotNull(sibling.artifact)), "corrupt sibling")

        val result = recorded.decodeCase(directory, MarketCaseId.from(windowValue(MINT)))

        assertTrue(result.ready)
        assertEquals(1, result.observations.size)
        assertCategory("ARTIFACT_BYTES") { recorded.decode(directory) }
    }

    @Test
    fun `one case can validate while its sibling discovery is pending`() {
        val record = artifact(fixture())
        val readyWindow = complete(MINT, listOf(record))
        val pendingWindow = MarketWindowRecord(OTHER_MINT, MintAddress.WRAPPED_SOL.value, FROM + 30, TO + 30, UNTIL,
            MarketWindowCoverage.PENDING, 0, 0, 0, 0, 0, emptyList(), null)
        save(listOf(readyWindow, pendingWindow), listOf(record))

        val result = recorded.decodeCase(directory, MarketCaseId.from(windowValue(MINT)))

        assertTrue(result.ready)
        assertEquals(MarketCaseStage.DISCOVERY_PENDING, recorded.inspectCases(directory).cases.last().stage)
    }

    @Test
    fun `a shared missing transaction blocks every case that selected it`() {
        val gap = MarketTransactionRecord(signature(3), SLOT, MarketTransactionOutcome.WITHHELD_UNSAFE_TEXT)
        save(listOf(complete(MINT, listOf(gap)), complete(OTHER_MINT, listOf(gap))), listOf(gap))

        val affected = listOf(MINT, OTHER_MINT).map { mint ->
            recorded.decodeCase(directory, MarketCaseId.from(windowValue(mint)))
        }

        assertTrue(affected.all { !it.ready })
        assertTrue(affected.all { it.blocker == "MARKET_DETAIL_GAPS" })
    }

    @Test
    fun `case inventory preserves the selected count when an over cap window retains no selection`() {
        save(listOf(incomplete(MINT, MarketWindowCoverage.OVER_CAP)), emptyList())

        val progress = recorded.inspectCases(directory).cases.single()

        assertEquals(5, progress.selected)
        assertEquals(0, progress.captured)
        assertEquals(5, progress.unresolved)
        assertEquals(MarketCaseStage.DISCOVERY_INCOMPLETE, progress.stage)
    }

    @Test
    fun `inspection and decoding verify manifest and artifact hashes`() {
        val record = artifact(fixture())
        save(listOf(complete(MINT, listOf(record))), listOf(record))
        assertEquals(manifest(listOf(complete(MINT, listOf(record))), listOf(record)), recorded.inspect(directory).manifest)

        val file = directory.resolve(requireNotNull(record.artifact))
        val bytes = Files.readAllBytes(file)
        bytes[bytes.indexOfFirst { it == '7'.code.toByte() }] = '8'.code.toByte()
        Files.write(file, bytes)
        assertCategory("ARTIFACT_HASH") { recorded.decode(directory) }

        val manifestFile = directory.resolve("market-manifest.json")
        Files.writeString(manifestFile, Files.readString(manifestFile).replace("\"selected\":1", "\"selected\":2"))
        assertCategory("MANIFEST_HASH") { recorded.inspect(directory) }
    }

    @Test
    fun `internally consistent forged manifests are rejected`() {
        val record = artifact(fixture())
        val window = complete(MINT, listOf(record))
        val valid = manifest(listOf(window), listOf(record))
        val otherArtifact = artifact(fixture(signature(5), SLOT + 1))
        val forgeries = mapOf(
            "schema version" to valid.copy(schemaVersion = 1),
            "genesis" to valid.copy(genesisHash = MINT),
            "selection outside window" to valid.copy(windows = listOf(window.copy(fromSlot = SLOT + 1))),
            "quote mint" to valid.copy(windows = listOf(window.copy(quoteMint = OTHER_MINT))),
            "record outside selection" to valid.copy(records = listOf(record, otherArtifact)),
            "missing record" to valid.copy(records = emptyList(), acquisitionComplete = true),
            "complete flag" to valid.copy(complete = false),
            "selected count" to valid.copy(windows = listOf(window.copy(selected = 2, scanned = 2))),
            "scanned above pages" to valid.copy(windows = listOf(window.copy(scanned = 1001))),
            "artifact path" to valid.copy(records = listOf(record.copy(artifact = "../outside.json"))),
            "artifact on gap" to valid.copy(records = listOf(record.copy(outcome = MarketTransactionOutcome.WITHHELD_UNSAFE_TEXT))),
            "over cap" to valid.copy(maxTransactions = 0),
            "overlapping windows" to valid.copy(windows = listOf(window, incomplete(MINT, MarketWindowCoverage.OVER_CAP).copy(fromSlot = SLOT + 12, toSlot = SLOT + 20))),
        )
        forgeries.forEach { (name, forged) ->
            store.save(directory, forged)
            assertFailsWith<CaptureValidationException>(name) { recorded.inspect(directory) }
        }
        Files.copy(directory.resolve(requireNotNull(otherArtifact.artifact)), directory.resolve(requireNotNull(record.artifact)),
            StandardCopyOption.REPLACE_EXISTING)
        store.save(directory, valid.copy(records = listOf(record.copy(sha256 = otherArtifact.sha256, bytes = otherArtifact.bytes))))
        assertCategory("ARTIFACT_IDENTITY") { recorded.inspect(directory) }
    }

    private fun artifact(transaction: ObjectNode, target: Path = directory): MarketTransactionRecord {
        val signature = transaction.path("transaction").path("signatures").path(0).asString()
        val written = store.writeTransaction(target, signature, transaction)
        return MarketTransactionRecord(signature, transaction.path("slot").asLong(), MarketTransactionOutcome.CAPTURED,
            written.path, written.sha256, written.bytes)
    }

    private fun complete(mint: String, records: List<MarketTransactionRecord>): MarketWindowRecord {
        val selection = records.sortedByDescending { it.slot }.map { MarketListedSignature(it.signature, it.slot) }
        return MarketWindowRecord(mint, MintAddress.WRAPPED_SOL.value, FROM, TO, UNTIL, MarketWindowCoverage.COMPLETE,
            pagesScanned = 1, scanned = selection.size, skippedFailed = 0, skippedNewer = 0, selected = selection.size,
            selection = selection, scanCursor = selection.lastOrNull())
    }

    /** A window at the page bound keeps its in-window selection for a later extension; an over-cap window keeps none. */
    private fun incomplete(mint: String, coverage: MarketWindowCoverage): MarketWindowRecord {
        val cursor = MarketListedSignature(signature(99), SLOT)
        val selection = if (coverage == MarketWindowCoverage.COVERAGE_INCOMPLETE) listOf(cursor) else emptyList()
        return MarketWindowRecord(mint, MintAddress.WRAPPED_SOL.value, FROM, TO, UNTIL, coverage,
            pagesScanned = 1, scanned = 1000, skippedFailed = 0, skippedNewer = 995, selected = if (selection.isEmpty()) 5 else selection.size,
            selection = selection, scanCursor = cursor)
    }

    private fun save(windows: List<MarketWindowRecord>, records: List<MarketTransactionRecord>, target: Path = directory) {
        store.save(target, manifest(windows, records))
    }

    private fun manifest(windows: List<MarketWindowRecord>, records: List<MarketTransactionRecord>): MarketHistoryManifest {
        val unresolved = setOf(MarketTransactionOutcome.PENDING, MarketTransactionOutcome.PROVIDER_UNAVAILABLE, MarketTransactionOutcome.INVALID_TRANSACTION)
        val acquired = windows.none { it.coverage == MarketWindowCoverage.PENDING } && records.none { it.outcome in unresolved }
        return MarketHistoryManifest(
            schemaVersion = 2, genesisHash = GENESIS, commitment = "confirmed", capturedAt = "2026-10-04T00:00:00Z",
            windowParameters = PARAMETERS, scanPageLimit = 1, maxTransactions = 300,
            windows = windows.sortedWith(compareBy({ it.baseMint }, { it.fromSlot })), records = records,
            acquisitionComplete = acquired, complete = acquired && windows.all { it.coverage == MarketWindowCoverage.COMPLETE },
            requestsUsed = records.size + 2, bytesUsed = records.sumOf { it.bytes },
        )
    }

    private fun windowValue(mint: String) =
        MarketCaptureWindow.of(MintAddress.of(mint), Slot.of(FROM), Slot.of(TO), TransactionSignature.of(UNTIL))

    private fun fixture(signature: String = SIGNATURE, slot: Long = SLOT): ObjectNode {
        val transaction = javaClass.getResourceAsStream("/walletactivity/decoder/pumpfun-buy.json")
            .use { mapper.readTree(requireNotNull(it)) } as ObjectNode
        (transaction.path("transaction").path("signatures") as ArrayNode).set(0, mapper.getNodeFactory().stringNode(signature))
        return transaction.put("slot", slot)
    }

    private fun inner(transaction: ObjectNode): ArrayNode =
        transaction.path("meta").path("innerInstructions").path(0).path("instructions") as ArrayNode

    private fun rewriteEvent(transaction: ObjectNode, change: (ByteArray) -> Unit) {
        val event = inner(transaction).path(EVENT_INNER_INDEX) as ObjectNode
        val bytes = requireNotNull(Base58.decode(event.path("data").asString()))
        change(bytes)
        event.put("data", Base58.encode(bytes))
    }

    private fun truncateEvent(transaction: ObjectNode, length: Int) {
        val event = inner(transaction).path(EVENT_INNER_INDEX) as ObjectNode
        event.put("data", Base58.encode(requireNotNull(Base58.decode(event.path("data").asString())).copyOf(length)))
    }

    private fun writeU64(bytes: ByteArray, offset: Int, value: BigInteger) {
        (0 until Long.SIZE_BYTES).forEach { index -> bytes[offset + index] = value.shiftRight(index * Byte.SIZE_BITS).toByte() }
    }

    private fun writeKey(bytes: ByteArray, key: String) {
        requireNotNull(Base58.decode(key)).copyInto(bytes, MINT_OFFSET)
    }

    private fun mintBalances(transaction: ObjectNode, field: String): List<ObjectNode> =
        transaction.path("meta").path(field).filter { it.path("mint").asString() == MINT }.map { it as ObjectNode }

    private fun removeMintBalances(transaction: ObjectNode) {
        listOf("preTokenBalances", "postTokenBalances").forEach { field ->
            val balances = transaction.path("meta").path(field) as ArrayNode
            (balances.size() - 1 downTo 0).filter { balances.path(it).path("mint").asString() == MINT }.forEach(balances::remove)
        }
    }

    private fun assertCategory(category: String, action: () -> Unit) {
        val failure = assertFailsWith<CaptureValidationException> { action() }
        assertTrue(failure.message.orEmpty().endsWith("category=$category"), failure.message)
    }

    private fun signature(index: Int): String = Base58.encode(ByteArray(64).also { it[63] = index.toByte() })

    private companion object {
        const val GENESIS = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"
        const val SIGNATURE = "35Vnpu9DSwADxy77FBQV8seGitMXuQRPB575AKXv1Jhh3NQSuZRKtuppUkiyLkYszxxi9HaKcmNN7fywqJ9R3mNA"
        const val MINT = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
        const val OTHER_MINT = "Bo82e4Upnsdg9zDeM6wPpckRyuzkLZovJucKCQ2ivUye"
        const val UNTIL = "1111111111111111111111111111111111111111111111111111111111111112"
        const val SLOT = 453004996L
        const val FROM = 453004990L
        const val TO = 453005008L
        const val EVENT_INNER_INDEX = 7
        const val MINT_OFFSET = 16
        const val SOL_AMOUNT_OFFSET = 48
        const val TOKEN_AMOUNT_OFFSET = 56
        const val IS_BUY_OFFSET = 64
        val PARAMETERS = MarketCaptureWindowParameters(horizonSlots = 4500, maxAgeSlots = 2)
    }
}
