package com.trenching.bot.console.capture

import com.trenching.bot.common.PageResult
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.console.api.dto.CaptureBudget
import com.trenching.bot.replay.ReplayDatasetCatalog
import com.trenching.bot.replay.domain.DatasetRegistration
import com.trenching.bot.replay.domain.DatasetSummary
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.SampleDataset
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CaptureCatalogServiceTest {
    @TempDir lateinit var root: Path

    private val exported = """{"schemaVersion":3}""".toByteArray()

    /** Keyed by content, like the real checksum-keyed catalog; can be told to fail once. */
    private class FakeCatalog : ReplayDatasetCatalog {
        val stored = mutableMapOf<String, ByteArray>()
        var failNext = false
        override fun register(name: String, json: InputStream): DatasetRegistration {
            if (failNext) { failNext = false; throw IllegalStateException("database unavailable") }
            val bytes = json.readAllBytes()
            val id = bytes.contentHashCode().toString()
            val created = stored.putIfAbsent(id, bytes) == null
            return DatasetRegistration(DatasetSummary(id, name, WalletAddress.of(CaptureConsoleFixture.WALLET), 3, 1, 1, null, null, emptyList(), FakeCaptureWorkflow.AT), created)
        }
        override fun importSample(sample: SampleDataset) = error("unused")
        override fun page(page: Int, size: Int): PageResult<DatasetSummary> = error("unused")
        override fun find(id: String): DatasetSummary? = error("unused")
        override fun load(id: String): ReplayDataset = error("unused")
        override fun delete(id: String): Boolean = error("unused")
    }

    private fun exportedSession(fixture: CaptureConsoleFixture) {
        val session = fixture.store.session("session-1")
        val relative = "console/session-1/generated/${session.marketAttemptId}/${session.ratesAttemptId}/dataset.json"
        Files.createDirectories(root.resolve(relative).parent)
        Files.write(root.resolve(relative), exported)
        val checksum = MessageDigest.getInstance("SHA-256").digest(exported).joinToString("") { "%02x".format(it) }
        fixture.store.save("session-1", CaptureOperationRecord("export-1", "export-1", "export-1", CaptureNames.EXPORT, session.marketAttemptId,
            CaptureNames.SUCCEEDED, FakeCaptureWorkflow.AT, FakeCaptureWorkflow.AT, FakeCaptureWorkflow.AT, CaptureBudget(), null,
            walletAttemptId = session.walletAttemptId, marketAttemptId = session.marketAttemptId, ratesAttemptId = session.ratesAttemptId,
            artifact = relative, checksum = checksum), true)
        fixture.store.save(session.copy(exportOperationId = "export-1"))
    }

    @Test
    fun `importing the same export twice registers one dataset and links the session`() {
        val fixture = CaptureConsoleFixture(root).apply { create() }
        exportedSession(fixture)
        val catalog = FakeCatalog()
        val service = CaptureCatalogService(fixture.store, fixture.operations, catalog)

        val first = service.register("session-1")
        val second = service.register("session-1")

        assertTrue(first.created)
        assertFalse(second.created)
        assertEquals(first.dataset.id, second.dataset.id)
        assertEquals(1, catalog.stored.size)
        assertEquals(first.dataset.id, fixture.sessions.find("session-1").datasetId)
        assertTrue(fixture.workflow.executed.isEmpty())
    }

    @Test
    fun `a failed registration keeps the export and can be retried without acquiring again`() {
        val fixture = CaptureConsoleFixture(root).apply { create() }
        exportedSession(fixture)
        val catalog = FakeCatalog().apply { failNext = true }
        val service = CaptureCatalogService(fixture.store, fixture.operations, catalog)

        assertFailsWith<IllegalStateException> { service.register("session-1") }
        assertNull(fixture.sessions.find("session-1").datasetId)
        assertContentEquals(exported, fixture.operations.artifact("session-1", "dataset"))

        assertTrue(service.register("session-1").created)
        assertTrue(fixture.workflow.executed.isEmpty())
        assertTrue(fixture.executor.queued.isEmpty())
    }

    @Test
    fun `a tampered export is refused`() {
        val fixture = CaptureConsoleFixture(root).apply { create() }
        exportedSession(fixture)
        val session = fixture.store.session("session-1")
        Files.writeString(root.resolve("console/session-1/generated/${session.marketAttemptId}/${session.ratesAttemptId}/dataset.json"), "{}")

        assertEquals("ARTIFACT_INVALID", assertFailsWith<CaptureRequestException> {
            CaptureCatalogService(fixture.store, fixture.operations, FakeCatalog()).register("session-1")
        }.code)
    }
}
