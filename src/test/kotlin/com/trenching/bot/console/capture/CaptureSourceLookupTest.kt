package com.trenching.bot.console.capture

import com.trenching.bot.console.domain.CaptureSource
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

class CaptureSourceLookupTest {
    @TempDir lateinit var root: Path

    private lateinit var store: CaptureFileStore
    private lateinit var lookup: CaptureSourceLookup

    @BeforeEach
    fun open() {
        store = CaptureFileStore(CapturePathPolicy(root), JsonMapper.builder().addModule(kotlinModule()).build()).also { it.initialize() }
        lookup = CaptureFileSourceLookup(store)
    }

    @AfterEach
    fun close() = store.close()

    @Test
    fun `a dataset registered from a session export names that session and no case`() {
        session("s1", "Decu · Pump.fun buys · 10 Oct 10:00", "2026-10-10T10:00:00Z", datasetId = "dataset-1")

        assertEquals(CaptureSource("s1", "Decu · Pump.fun buys · 10 Oct 10:00", null), lookup.find("dataset-1"))
    }

    @Test
    fun `a dataset registered from a case export names its session and case`() {
        session("s1", "Decu · 7mup…pump · 16 Oct 20:13", "2026-10-16T20:13:00Z")
        case("s1", "case-a", datasetId = "dataset-a")
        case("s1", "case-b", datasetId = "dataset-b")

        assertEquals(CaptureSource("s1", "Decu · 7mup…pump · 16 Oct 20:13", "case-b"), lookup.find("dataset-b"))
    }

    @Test
    fun `the capture is named by its display name, numbered among sessions sharing a stored name`() {
        session("s1", "runitback", "2026-10-10T10:00:00Z")
        session("s2", "runitback", "2026-10-11T10:00:00Z", datasetId = "dataset-2")

        assertEquals("runitback (2)", lookup.find("dataset-2")?.displayName)
    }

    @Test
    fun `a dataset no capture exported has no capture source`() {
        session("s1", "runitback", "2026-10-10T10:00:00Z", datasetId = "dataset-1")
        case("s1", "case-a", datasetId = "dataset-a")
        case("s1", "case-b", datasetId = null)

        assertNull(lookup.find("uploaded-dataset"))
    }

    @Test
    fun `a deleted capture no longer names the dataset it exported`() {
        session("s1", "runitback", "2026-10-10T10:00:00Z")
        case("s1", "case-a", datasetId = "dataset-a")
        assertEquals("s1", lookup.find("dataset-a")?.captureId)

        store.remove("s1", "trash-1")

        assertNull(lookup.find("dataset-a"))
    }

    @Test
    fun `a dataset exported by two captures names the oldest`() {
        session("s-new", "later", "2026-10-12T10:00:00Z", datasetId = "dataset-1")
        session("s-old", "earlier", "2026-10-11T10:00:00Z")
        case("s-old", "case-a", datasetId = "dataset-1")

        assertEquals(CaptureSource("s-old", "earlier", "case-a"), lookup.find("dataset-1"))
    }

    @Test
    fun `without the capture console no dataset has a capture`() {
        assertNull(CaptureSourceLookup.ABSENT.find("dataset-1"))
    }

    private fun session(id: String, name: String, createdAt: String, datasetId: String? = null) = store.save(
        CaptureSessionRecord(id = id, requestId = id, fingerprint = id, name = name, createdAt = Instant.parse(createdAt), wallet = null,
            walletAttemptId = "w-$id", marketAttemptId = "m-$id", ratesAttemptId = null, datasetId = datasetId), create = true)

    private fun case(session: String, caseId: String, datasetId: String?) =
        store.save(session, CaptureCaseRecord(caseId = caseId, walletAttemptId = "w-$session", marketAttemptId = "m-$session", ratesAttemptId = null,
            datasetId = datasetId))
}
