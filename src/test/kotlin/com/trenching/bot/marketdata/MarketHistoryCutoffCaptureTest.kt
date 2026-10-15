package com.trenching.bot.marketdata

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.chaincapture.SolanaReadRpc
import com.trenching.bot.common.Base58
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.marketdata.application.MarketHistoryCaptureService
import com.trenching.bot.marketdata.infrastructure.capture.MarketHistoryArtifactStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

class MarketHistoryCutoffCaptureTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val fixture = javaClass.getResourceAsStream("/walletactivity/decoder/pumpfun-buy.json").use { mapper.readTree(requireNotNull(it)) } as ObjectNode

    @Test
    fun `a decided capture fetches oldest first through the batch holding the decision slot and needs no later record`() {
        val chain = Chain(MINT to (1001..1250))
        val cutoff = DecideAtSlot(1040)

        val result = capture(chain, cutoff = cutoff)

        assertEquals((1001L..1100L).map(::signature), chain.fetched)
        val manifest = result.manifest
        assertEquals(MarketWindowCutoff(1040, OUTCOME, IDENTITY.engineVersion, IDENTITY.configurationHash), manifest.windows.single().cutoff)
        assertEquals(3, manifest.schemaVersion)
        assertEquals((1001L..1040L).toList(), manifest.records.filter { it.outcome == MarketTransactionOutcome.CAPTURED && it.artifact != null }.map { it.slot }.sorted())
        val later = manifest.records.filter { it.slot > 1040 }
        assertEquals(210, later.size)
        assertTrue(later.all { it.outcome == MarketTransactionOutcome.NOT_NEEDED && it.artifact == null && it.sha256 == null && it.bytes == 0L })
        assertTrue(Files.exists(directory.resolve("transactions/${signature(1100)}.json")), "a fetched later record is left unreferenced")
        assertTrue(manifest.acquisitionComplete && manifest.complete)
        assertNull(result.stopReason)
        assertEquals(manifest, recorded().inspect(directory).manifest)
        assertEquals(listOf(1000L, 1100L), cutoff.evaluations.map { it.observedThrough })
        assertEquals(listOf(emptyList(), (1001L..1100L).toList()), cutoff.evaluations.map { it.observationSlots })
    }

    @Test
    fun `a decided window decodes only through its cutoff slot`() {
        capture(Chain(MINT to (1001..1250)), cutoff = DecideAtSlot(1040))

        val decoded = recorded().decode(directory)

        val window = decoded.windows.single()
        assertEquals(Slot.of(1040), window.observedThrough)
        assertTrue(window.yieldsObservations)
        assertEquals(0, window.detailGaps)
        assertEquals((1001L..1040L).toList(), decoded.observations.map { it.slot.value })
        assertEquals(40, window.observations)
        val case = recorded().decodeCase(directory, MarketCaseId.from(window.window))
        assertTrue(case.ready)
        assertEquals(Slot.of(1040), case.progress.observedThrough)
        assertEquals(40, case.progress.captured)
        assertEquals(0, case.progress.unresolved)
        assertEquals(MarketCaseStage.READY_TO_VALIDATE, case.progress.stage)
        assertEquals(40, case.observations.size)
    }

    @Test
    fun `a gap below the decision slot leaves the window undecided and it is fetched in full with the gap blocking`() {
        val chain = Chain(MINT to (1001..1250)).apply { unavailable += signature(1020) }
        val cutoff = DecideAtSlot(1040)

        val manifest = capture(chain, cutoff = cutoff).manifest

        assertEquals(250, chain.fetched.size)
        assertNull(manifest.windows.single().cutoff)
        assertEquals(2, manifest.schemaVersion)
        assertEquals(listOf(1000L, 1019L), cutoff.evaluations.map { it.observedThrough })
        val window = recorded().decode(directory).windows.single()
        assertEquals(Slot.of(1500), window.observedThrough)
        assertEquals(1, window.detailGaps)
        assertTrue(!window.yieldsObservations)
    }

    @Test
    fun `a gap above the decision slot is not needed and the decided window yields its observations`() {
        val chain = Chain(MINT to (1001..1250)).apply { unavailable += signature(1060) }

        val manifest = capture(chain, cutoff = DecideAtSlot(1040)).manifest

        assertEquals(1040, manifest.windows.single().cutoff?.throughSlot)
        assertEquals(MarketTransactionOutcome.NOT_NEEDED, manifest.records.single { it.signature == signature(1060) }.outcome)
        val window = recorded().decode(directory).windows.single()
        assertTrue(window.yieldsObservations)
        assertEquals(40, window.observations)
    }

    @Test
    fun `a cutoff that decides before any fetch makes no detail request`() {
        val chain = Chain(MINT to (1001..1250))

        val manifest = capture(chain, cutoff = DecideAtSlot(1000)).manifest

        assertEquals(emptyList(), chain.fetched)
        assertEquals(1000, manifest.windows.single().cutoff?.throughSlot)
        assertTrue(manifest.records.all { it.outcome == MarketTransactionOutcome.NOT_NEEDED })
        assertTrue(manifest.complete)
        val window = recorded().decode(directory).windows.single()
        assertTrue(window.yieldsObservations)
        assertEquals(0, window.observations)
    }

    @Test
    fun `an empty selection completes without a detail request or an evaluation`() {
        val chain = Chain(MINT to IntRange.EMPTY)
        val cutoff = DecideAtSlot(1000)

        val manifest = capture(chain, cutoff = cutoff).manifest

        assertEquals(emptyList(), chain.fetched)
        assertEquals(emptyList(), cutoff.evaluations)
        assertNull(manifest.windows.single().cutoff)
        assertTrue(manifest.complete)
    }

    @Test
    fun `a decision beyond the captured prefix is refused as a programming error`() {
        val chain = Chain(MINT to (1001..1250))
        val beyond = MarketCaptureCutoff { _, _, _ -> CutoffDecision.Decided(Slot.of(1200), OUTCOME) }

        assertCategory("CUTOFF_DECISION") { capture(chain, cutoff = beyond) }
        assertEquals(emptyList(), chain.fetched)
    }

    @Test
    fun `decided mode without a cutoff or an identity is refused before any provider request`() {
        val chain = Chain(MINT to (1001..1250))
        val invalid = listOf(
            request().copy(cutoffMode = MarketCutoffMode.DECIDED, cutoffIdentity = IDENTITY),
            request().copy(cutoffMode = MarketCutoffMode.DECIDED, cutoff = DecideAtSlot(1040)),
            request().copy(cutoffMode = MarketCutoffMode.DECIDED, cutoff = DecideAtSlot(1040), cutoffIdentity = IDENTITY.copy(engineVersion = " ")),
            request().copy(cutoffMode = MarketCutoffMode.DECIDED, cutoff = DecideAtSlot(1040), cutoffIdentity = IDENTITY.copy(configurationHash = "")),
        )

        invalid.forEach { request -> assertCategory("REQUEST_BOUNDS") { service(chain).capture(request) } }
        assertEquals(emptyList(), chain.methods)
    }

    @Test
    fun `extending a decided capture in full mode lists nothing, fetches only the not needed records and ends as a full capture`() {
        capture(Chain(MINT to (1001..1250)), cutoff = DecideAtSlot(1040))
        val extension = Chain(MINT to (1001..1250))

        val extended = capture(extension, mode = MarketCutoffMode.FULL).manifest

        assertEquals(listOf("getGenesisHash") + List(210) { "getTransaction" }, extension.methods)
        assertEquals((1041L..1250L).map(::signature), extension.fetched)
        val full = capture(Chain(MINT to (1001..1250)), target = directory.resolve("full"), mode = MarketCutoffMode.FULL).manifest
        assertEquals(full.copy(requestsUsed = 0, bytesUsed = 0), extended.copy(requestsUsed = 0, bytesUsed = 0))
    }

    @Test
    fun `a decided capture asked again with the same identity changes nothing and with another identity is reopened and decided again`() {
        val first = capture(Chain(MINT to (1001..1250)), cutoff = DecideAtSlot(1040)).manifest
        val same = Chain(MINT to (1001..1250))
        assertEquals(first.copy(requestsUsed = 0), capture(same, cutoff = DecideAtSlot(1040)).manifest.copy(requestsUsed = 0))
        assertEquals(emptyList(), same.fetched)

        val other = Chain(MINT to (1001..1250))
        val identity = CutoffIdentity("engine-v2", "b".repeat(64))
        val reopened = capture(other, cutoff = DecideAtSlot(1150), identity = identity).manifest

        assertEquals(listOf("getGenesisHash") + List(200) { "getTransaction" }, other.methods)
        assertEquals((1041L..1240L).map(::signature), other.fetched)
        assertEquals(MarketWindowCutoff(1150, OUTCOME, identity.engineVersion, identity.configurationHash), reopened.windows.single().cutoff)
        assertEquals(150, reopened.records.count { it.outcome == MarketTransactionOutcome.CAPTURED })
    }

    @Test
    fun `a resumed decided capture rebuilds the prefix from stored artifacts and ends as the uninterrupted capture`() {
        val reference = capture(Chain(MINT to (1001..1250)), target = directory.resolve("reference"), cutoff = DecideAtSlot(1150)).manifest
        val interrupted = Chain(MINT to (1001..1250))
        val stopped = capture(interrupted, cutoff = DecideAtSlot(1150), requestBudget = 52)
        assertEquals("REQUESTS", stopped.stopReason)
        assertEquals(50, interrupted.fetched.size)

        val cutoff = DecideAtSlot(1150)
        val resumed = capture(Chain(MINT to (1001..1250)), cutoff = cutoff).manifest

        assertEquals(1050L, cutoff.evaluations.first().observedThrough)
        assertEquals((1001L..1050L).toList(), cutoff.evaluations.first().observationSlots)
        assertEquals(reference.copy(requestsUsed = 0, bytesUsed = 0), resumed.copy(requestsUsed = 0, bytesUsed = 0))
    }

    @Test
    fun `a record shared by a decided and an undecided window stays and is fetched`() {
        val shared = 1060
        val chain = Chain(OTHER_MINT to listOf(1200, shared), MINT to (1001..1100).toList())
        val decidesOtherFirst = MarketCaptureCutoff { window, _, _ ->
            if (window.baseMint.value == OTHER_MINT) CutoffDecision.Decided(Slot.of(1000), OUTCOME) else CutoffDecision.Undecided
        }

        val manifest = capture(chain, windows = listOf(window(MINT), window(OTHER_MINT)), cutoff = decidesOtherFirst).manifest

        val windows = manifest.windows.associateBy { it.baseMint }
        assertEquals(1000, windows.getValue(OTHER_MINT).cutoff?.throughSlot)
        assertNull(windows.getValue(MINT).cutoff)
        val records = manifest.records.associateBy { it.slot }
        assertEquals(MarketTransactionOutcome.NOT_NEEDED, records.getValue(1200).outcome)
        assertEquals(MarketTransactionOutcome.CAPTURED, records.getValue(shared.toLong()).outcome)
        assertEquals(1, chain.fetched.count { it == signature(shared.toLong()) })
        assertEquals(100, chain.fetched.size)
        assertTrue(manifest.complete)
        assertEquals(manifest, recorded().inspect(directory).manifest)
    }

    private fun capture(
        chain: Chain,
        target: Path = directory,
        windows: List<MarketCaptureWindow> = listOf(window(MINT)),
        mode: MarketCutoffMode = MarketCutoffMode.DECIDED,
        cutoff: MarketCaptureCutoff? = null,
        identity: CutoffIdentity = IDENTITY,
        requestBudget: Int = 1000,
    ): MarketHistoryCaptureResult = service(chain).capture(request(target, windows, requestBudget).copy(cutoffMode = mode, cutoff = cutoff,
        cutoffIdentity = identity.takeIf { cutoff != null }))

    private fun service(chain: Chain): MarketHistoryCapture = MarketHistoryCaptureService(chain.rpc(), MarketHistoryArtifactStore(mapper), CLOCK)

    private fun request(target: Path = directory, windows: List<MarketCaptureWindow> = listOf(window(MINT)), requestBudget: Int = 1000) =
        MarketHistoryCaptureRequest(target, windows, PARAMETERS, 20, 1000, requestBudget, 104_857_600)

    private fun recorded(): RecordedMarketHistory = MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper)

    private fun window(mint: String) = MarketCaptureWindow.of(MintAddress.of(mint), Slot.of(1001), Slot.of(1500), TransactionSignature.of(signature(1)))

    private fun assertCategory(category: String, action: () -> Unit) {
        val failure = assertFailsWith<CaptureValidationException> { action() }
        assertTrue(failure.message.orEmpty().endsWith("category=$category"), failure.message)
    }

    /** One successful Pump.fun trade of [MINT] per listed slot; listings are newest first and complete in one short page. */
    private inner class Chain(vararg listings: Pair<String, Iterable<Int>>) {
        private val slots = listings.associate { (mint, slots) -> mint to slots.map(Int::toLong) }
        private val slotOf = slots.values.flatten().associateBy(::signature)
        val methods = mutableListOf<String>()
        val fetched = mutableListOf<String>()
        val unavailable = mutableSetOf<String>()

        fun rpc() = SolanaReadRpc { method, params, budget ->
            budget.request()
            synchronized(this) { methods += method }
            when (method) {
                "getGenesisHash" -> mapper.readTree("\"$GENESIS\"")
                "getSignaturesForAddress" -> mapper.readTree(slots.getValue(params[0] as String).sortedDescending()
                    .joinToString(",", "[", "]") { """{"signature":"${signature(it)}","slot":$it,"err":null,"memo":null,"blockTime":1791049629}""" })
                "getTransaction" -> {
                    val signature = params[0] as String
                    synchronized(this) { fetched += signature }
                    if (signature in unavailable) mapper.nullNode() else trade(signature, slotOf.getValue(signature))
                }
                else -> error("Unexpected method $method")
            }
        }
    }

    private fun trade(signature: String, slot: Long): JsonNode = fixture.deepCopy().apply {
        (path("transaction").path("signatures") as ArrayNode).set(0, mapper.nodeFactory.stringNode(signature))
        put("slot", slot)
    }

    private fun signature(slot: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return Base58.encode(digest.digest("a$slot".toByteArray()) + digest.digest("b$slot".toByteArray()))
    }

    private companion object {
        const val GENESIS = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"
        const val MINT = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
        const val OTHER_MINT = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
        const val OUTCOME = "TAKE_PROFIT"
        val IDENTITY = CutoffIdentity("engine-v1", "a".repeat(64))
        val PARAMETERS = MarketCaptureWindowParameters(horizonSlots = 4500, maxAgeSlots = 2)
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC)
    }
}
