package com.trenching.bot.marketdata.infrastructure.capture

import com.trenching.bot.chaincapture.CaptureArtifactStore
import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.Base58
import com.trenching.bot.common.MintAddress
import com.trenching.bot.marketdata.MarketCaptureWindowParameters
import com.trenching.bot.marketdata.MarketHistoryManifest
import com.trenching.bot.marketdata.MarketWindowCoverage
import com.trenching.bot.marketdata.MarketWindowCutoff
import com.trenching.bot.marketdata.MarketWindowRecord
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

class MarketHistoryArtifactStoreTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val store = MarketHistoryArtifactStore(mapper)

    @Test
    fun `a version 4 manifest round-trips its lead`() {
        val manifest = manifest(LEAD, version = 4)

        store.save(directory, manifest)

        assertEquals(manifest, store.loadManifest(directory))
        assertEquals(50, inner().path("windowParameters").path("leadSlots").asLong())
    }

    @Test
    fun `a decided capture with a lead stays version 4`() {
        val manifest = manifest(LEAD, version = 4, cutoff = cutoff(BUY_SLOT))

        store.save(directory, manifest)

        assertEquals(manifest, store.loadManifest(directory))
    }

    @Test
    fun `version 2 and 3 manifests keep their bytes without a lead and load with the legacy lead`() {
        val full = manifest(LEGACY, version = 2, fromSlot = BUY_SLOT + 1)
        store.save(directory, full)
        assertFalse(Files.readString(directory.resolve(MarketCaptureFormat.MANIFEST)).contains("leadSlots"))
        assertEquals(MarketCaptureWindowParameters.LEGACY_LEAD_SLOTS, store.loadManifest(directory)?.windowParameters?.leadSlots)

        val decided = manifest(LEGACY, version = 3, fromSlot = BUY_SLOT + 1, cutoff = cutoff(BUY_SLOT))
        store.save(directory, decided)
        assertFalse(Files.readString(directory.resolve(MarketCaptureFormat.MANIFEST)).contains("leadSlots"))
        assertEquals(decided, store.loadManifest(directory))
    }

    @Test
    fun `refuses an unknown version a version 4 manifest without a valid lead and a legacy manifest carrying one`() {
        val lead = manifest(LEAD, version = 4)
        assertCategory("MANIFEST_VERSION") { refused(lead) { put("schemaVersion", 5) } }
        assertCategory("MANIFEST_INTEGER") { refused(lead) { parameters().remove("leadSlots") } }
        assertCategory("MANIFEST_VERSION") { refused(lead) { parameters().put("leadSlots", -1) } }
        assertCategory("MANIFEST_BOUNDS") { refused(lead) { parameters().put("leadSlots", 1001) } }
        assertCategory("MANIFEST_FIELDS") { refused(manifest(LEGACY, version = 2, fromSlot = BUY_SLOT + 1)) { parameters().put("leadSlots", -1) } }
    }

    @Test
    fun `a cutoff inside the lead is invalid while one at the first buy is accepted`() {
        val decided = manifest(LEAD, version = 4, cutoff = cutoff(BUY_SLOT))
        store.save(directory, decided)
        assertEquals(decided, store.loadManifest(directory))

        assertCategory("CAPTURE_INVALID") { refused(decided) { cutoffNode().put("throughSlot", BUY_SLOT - 1) } }
    }

    @Test
    fun `a legacy cutoff may still sit on the slot before the window start`() {
        val decided = manifest(LEGACY, version = 3, fromSlot = BUY_SLOT + 1, cutoff = cutoff(BUY_SLOT))
        store.save(directory, decided)
        assertEquals(decided, store.loadManifest(directory))

        assertCategory("CAPTURE_INVALID") { refused(decided) { cutoffNode().put("throughSlot", BUY_SLOT - 1) } }
    }

    /** Saves [manifest], rewrites it with [edit] under a valid envelope hash so only the store's own checks can refuse it, and loads it. */
    private fun refused(manifest: MarketHistoryManifest, edit: ObjectNode.() -> Unit) {
        store.save(directory, manifest)
        val tree = inner().apply(edit)
        val files = CaptureArtifactStore(mapper)
        files.writeManifest(files.root(directory, create = false), MarketCaptureFormat.MANIFEST, tree)
        store.loadManifest(directory)
    }

    private fun ObjectNode.parameters(): ObjectNode = path("windowParameters") as ObjectNode

    private fun ObjectNode.cutoffNode(): ObjectNode = path("windows").path(0).path("cutoff") as ObjectNode

    private fun inner(): ObjectNode =
        mapper.readTree(Files.readString(directory.resolve(MarketCaptureFormat.MANIFEST))).path("manifest") as ObjectNode

    private fun manifest(parameters: MarketCaptureWindowParameters, version: Int, fromSlot: Long = BUY_SLOT - 50, cutoff: MarketWindowCutoff? = null) =
        MarketHistoryManifest(
            schemaVersion = version, genesisHash = GENESIS, commitment = "confirmed", capturedAt = "2026-10-22T00:00:00Z",
            windowParameters = parameters, scanPageLimit = 20, maxTransactions = 300,
            windows = listOf(MarketWindowRecord(
                baseMint = MINT, quoteMint = MintAddress.WRAPPED_SOL.value, fromSlot = fromSlot, toSlot = BUY_SLOT + 4500,
                untilSignature = BUY, coverage = MarketWindowCoverage.COMPLETE, pagesScanned = 1, scanned = 0, skippedFailed = 0,
                skippedNewer = 0, selected = 0, selection = emptyList(), scanCursor = null, cutoff = cutoff,
            )),
            records = emptyList(), acquisitionComplete = true, complete = true, requestsUsed = 2, bytesUsed = 100,
        )

    private fun cutoff(throughSlot: Long) = MarketWindowCutoff(throughSlot, "TAKE_PROFIT", "engine-v1", "a".repeat(64))

    private fun assertCategory(category: String, action: () -> Unit) {
        val failure = assertFailsWith<CaptureValidationException> { action() }
        assertTrue(failure.message.orEmpty().endsWith("category=$category"), failure.message)
    }

    private companion object {
        const val GENESIS = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"
        const val MINT = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
        const val BUY_SLOT = 459_490_743L
        val BUY: String = Base58.encode(ByteArray(64) { 7 })
        val LEAD = MarketCaptureWindowParameters(4500, 2, MarketCaptureWindowParameters.DEFAULT_LEAD_SLOTS)
        val LEGACY = MarketCaptureWindowParameters(4500, 2)
    }
}
