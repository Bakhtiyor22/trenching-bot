package com.trenching.bot.console.capture

import com.trenching.bot.console.domain.CaptureSource
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CaptureSourceLookupTest {
    @TempDir lateinit var root: Path

    @Test
    fun `finds the capture whose own export registered the dataset`() {
        val fixture = CaptureConsoleFixture(root)
        val session = fixture.create()
        fixture.store.save(fixture.store.session(session.id).copy(datasetId = "dataset-1"))

        assertEquals(CaptureSource(session.id, "Sample session-1", null), CaptureSourceLookup(fixture.store).forDataset("dataset-1"))
    }

    @Test
    fun `finds the case a dataset was exported from, even one left behind by a newer attempt`() {
        val fixture = CaptureConsoleFixture(root)
        val session = fixture.create()
        fixture.store.save(session.id, CaptureCaseRecord(caseId = "case-old", walletAttemptId = "wallet-old", marketAttemptId = "market-old", ratesAttemptId = null, datasetId = "dataset-2"))

        assertEquals(CaptureSource(session.id, "Sample session-1", "case-old"), CaptureSourceLookup(fixture.store).forDataset("dataset-2"))
    }

    @Test
    fun `the earliest capture wins when two registered the same dataset`() {
        val clock = MutableClock(FIRST)
        val fixture = CaptureConsoleFixture(root, clock = clock)
        val first = fixture.create("session-1")
        clock.now = FIRST.plusSeconds(60)
        val second = fixture.create("session-2")
        fixture.store.save(fixture.store.session(second.id).copy(datasetId = "dataset-3"))
        fixture.store.save(fixture.store.session(first.id).copy(datasetId = "dataset-3"))

        assertEquals(CaptureSource(first.id, "Sample session-1", null), CaptureSourceLookup(fixture.store).forDataset("dataset-3"))
    }

    @Test
    fun `answers nothing for a dataset no capture registered or whose capture was deleted`() {
        val fixture = CaptureConsoleFixture(root)
        val session = fixture.create()
        fixture.store.save(fixture.store.session(session.id).copy(datasetId = "dataset-4"))
        fixture.sessions.delete(session.id)

        assertNull(CaptureSourceLookup(fixture.store).forDataset("dataset-4"))
        assertNull(CaptureSourceLookup(fixture.store).forDataset("dataset-unknown"))
    }

    private companion object {
        val FIRST: Instant = Instant.parse("2026-10-16T20:13:00Z")
    }
}
