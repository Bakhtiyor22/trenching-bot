package com.trenching.bot.console.capture

import com.trenching.bot.console.api.dto.CaptureAttachment
import com.trenching.bot.console.api.dto.CaptureAttemptRequest
import com.trenching.bot.console.api.dto.CaptureBudget
import com.trenching.bot.console.api.dto.CaptureCreateRequest
import com.trenching.bot.console.api.dto.CaptureMarketSettings
import com.trenching.bot.console.api.dto.CaptureOperationRequest
import com.trenching.bot.console.api.dto.CaptureWalletSettings
import com.trenching.bot.replay.CaptureStepCommand
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CaptureSessionServiceTest {
    @TempDir lateinit var root: Path

    private fun conflict(code: String, block: () -> Unit) = assertEquals(code, assertFailsWith<CaptureRequestException> { block() }.code)

    @Test
    fun `creating a session allocates managed attempts without starting any work`() {
        val fixture = CaptureConsoleFixture(root)
        val session = fixture.create()

        assertEquals("Sample", session.name)
        assertNotNull(session.marketAttempt)
        assertEquals(CaptureMarketSettings(), session.marketAttempt?.market)
        assertEquals(session.marketAttempt?.id, session.ratesAttempt?.marketAttemptId)
        assertNull(session.operation)
        assertTrue(fixture.executor.queued.isEmpty())
        assertEquals(0, fixture.workflow.inspections)
        assertEquals("console/session-1/wallet", fixture.store.attempt("session-1", session.walletAttemptId).directory)
    }

    @Test
    fun `the same request id returns the existing session and different settings conflict`() {
        val fixture = CaptureConsoleFixture(root)
        val first = fixture.create()

        assertEquals(first.id, fixture.create().id)
        assertEquals(1, fixture.sessions.page(0, 20).total)
        conflict("REQUEST_CONFLICT") {
            fixture.sessions.create(CaptureCreateRequest("session-1", "Sample", wallet = CaptureWalletSettings(CaptureConsoleFixture.WALLET, limit = 2)))
        }
    }

    @Test
    fun `invalid wallet settings are rejected before anything is written`() {
        val fixture = CaptureConsoleFixture(root)

        assertEquals(400, assertFailsWith<CaptureRequestException> {
            fixture.sessions.create(CaptureCreateRequest("bad", "Sample", wallet = CaptureWalletSettings("not-a-wallet")))
        }.status)
        assertEquals(400, assertFailsWith<CaptureRequestException> {
            fixture.sessions.create(CaptureCreateRequest("bad", "Sample", wallet = CaptureWalletSettings(CaptureConsoleFixture.WALLET, limit = 101)))
        }.status)
        assertEquals(0, fixture.sessions.page(0, 20).total)
    }

    @Test
    fun `attaching existing evidence queues one offline inspection and leaves the directories untouched`() {
        val fixture = CaptureConsoleFixture(root)
        Files.createDirectories(root.resolve("decu-wallet"))

        val session = fixture.sessions.create(CaptureCreateRequest("attached", "Decu", attach = CaptureAttachment("decu-wallet", "decu-markets")))
        assertNull(session.ratesAttempt)
        assertEquals(true, session.marketAttempt?.attached)
        fixture.executor.runAll()

        val inspect = fixture.workflow.executed.single() as CaptureStepCommand.Inspect
        assertEquals(root.resolve("decu-wallet"), inspect.paths.wallet)
        assertEquals(root.resolve("decu-markets"), inspect.paths.market)
        assertFalse(Files.exists(root.resolve("decu-markets")))
        assertEquals(0, Files.list(root.resolve("decu-wallet")).use { it.count() })
    }

    @Test
    fun `attachment rejects traversal overlap managed directories and rates without a market`() {
        val fixture = CaptureConsoleFixture(root)
        fun attach(value: CaptureAttachment) = fixture.sessions.create(CaptureCreateRequest("attached", "Decu", attach = value))

        assertEquals("INVALID_PATH", assertFailsWith<CaptureRequestException> { attach(CaptureAttachment("../outside")) }.code)
        assertEquals("INVALID_PATH", assertFailsWith<CaptureRequestException> { attach(CaptureAttachment("wallet", "wallet/markets")) }.code)
        assertEquals(400, assertFailsWith<CaptureRequestException> { attach(CaptureAttachment("console/other")) }.status)
        assertEquals(400, assertFailsWith<CaptureRequestException> { attach(CaptureAttachment("wallet", rates = "rates")) }.status)
        assertTrue(fixture.executor.queued.isEmpty())
    }

    @Test
    fun `a new market attempt keeps wallet evidence and earlier attempts and clears every downstream result`() {
        val fixture = CaptureConsoleFixture(root)
        val original = fixture.create()
        withDownstreamResults(fixture)

        val updated = fixture.sessions.newAttempt("session-1", CaptureAttemptRequest("market-2", CaptureNames.MARKET, CaptureMarketSettings(80, 1000)))

        assertEquals(original.walletAttemptId, updated.walletAttemptId)
        assertEquals("market-2", updated.marketAttempt?.id)
        assertEquals(CaptureMarketSettings(80, 1000), updated.marketAttempt?.market)
        assertEquals("console/session-1/markets/market-2", fixture.store.attempt("session-1", "market-2").directory)
        assertNull(updated.ratesAttempt)
        assertNull(updated.inspection)
        assertFalse(updated.datasetReady)
        assertFalse(updated.calibrationReady)
        assertNull(updated.datasetId)
        assertEquals(4, fixture.sessions.attempts("session-1", 0, 20).total)
        assertTrue(fixture.executor.queued.isEmpty())
    }

    @Test
    fun `a new rates attempt keeps calibration but clears export and dataset`() {
        val fixture = CaptureConsoleFixture(root)
        val original = fixture.create()
        withDownstreamResults(fixture)

        val updated = fixture.sessions.newAttempt("session-1", CaptureAttemptRequest("rates-2", CaptureNames.RATES))

        assertEquals(original.marketAttempt?.id, updated.marketAttempt?.id)
        assertEquals(original.marketAttempt?.id, updated.ratesAttempt?.marketAttemptId)
        assertTrue(updated.calibrationReady)
        assertFalse(updated.datasetReady)
        assertNull(updated.datasetId)
    }

    @Test
    fun `attempts cannot change while an operation is running`() {
        val fixture = CaptureConsoleFixture(root)
        val session = fixture.create()
        fixture.operations.start("session-1", CaptureOperationRequest("inspect-1", CaptureNames.INSPECT, session.marketAttempt?.id))

        conflict("CAPTURE_BUSY") { fixture.sessions.newAttempt("session-1", CaptureAttemptRequest("market-2", CaptureNames.MARKET)) }
        assertNull(fixture.store.attemptOrNull("session-1", "market-2"))
    }

    @Test
    fun `invalid market attempt settings and paging bounds are rejected`() {
        val fixture = CaptureConsoleFixture(root)
        fixture.create()

        assertEquals(400, assertFailsWith<CaptureRequestException> {
            fixture.sessions.newAttempt("session-1", CaptureAttemptRequest("market-2", CaptureNames.MARKET, CaptureMarketSettings(501, 1)))
        }.status)
        assertEquals(400, assertFailsWith<CaptureRequestException> { fixture.sessions.page(0, 101) }.status)
        assertEquals(404, assertFailsWith<CaptureRequestException> { fixture.sessions.find("missing") }.status)
    }

    @Test
    fun `deleting a session removes its managed files and nothing it only references`() {
        val fixture = CaptureConsoleFixture(root)
        Files.createDirectories(root.resolve("decu-wallet"))
        val attachedFile = Files.writeString(root.resolve("decu-wallet/manifest.json"), "{\"evidence\":true}")
        fixture.sessions.create(CaptureCreateRequest("attached", "Decu", attach = CaptureAttachment("decu-wallet")))
        fixture.executor.runAll()
        fixture.create()
        val managed = Files.createDirectories(root.resolve("console/session-1/wallet"))
        Files.writeString(managed.resolve("manifest.json"), "{}")

        fixture.sessions.delete("session-1")
        fixture.sessions.delete("attached")

        assertFalse(Files.exists(root.resolve("console/session-1")))
        assertFalse(Files.exists(root.resolve("console/attached")))
        assertEquals("{\"evidence\":true}", Files.readString(attachedFile))
        assertEquals(0, fixture.sessions.page(0, 20).total)
        assertEquals(listOf("worker.lock"), Files.list(root.resolve("console")).use { stream -> stream.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `deletion removes a link inside the session without following it`() {
        val fixture = CaptureConsoleFixture(root)
        fixture.create()
        val outside = Files.writeString(Files.createDirectories(root.resolve("outside")).resolve("keep.json"), "keep")
        val link = root.resolve("console/session-1/linked")
        link(link, root.resolve("outside"))

        fixture.sessions.delete("session-1")

        assertFalse(Files.exists(root.resolve("console/session-1")))
        assertEquals("keep", Files.readString(outside))
    }

    @Test
    fun `a session with a running operation cannot be deleted but another session can`() {
        val fixture = CaptureConsoleFixture(root)
        val running = fixture.create()
        fixture.create("session-2")
        fixture.operations.start("session-1", CaptureOperationRequest("inspect-1", CaptureNames.INSPECT, running.marketAttempt?.id))

        conflict("CAPTURE_BUSY") { fixture.sessions.delete("session-1") }
        fixture.sessions.delete("session-2")

        assertTrue(Files.exists(root.resolve("console/session-1/session.json")))
        assertFalse(Files.exists(root.resolve("console/session-2")))
    }

    @Test
    fun `deleting an unknown or already deleted session is not found and an invalid id is rejected`() {
        val fixture = CaptureConsoleFixture(root)
        fixture.create()
        fixture.sessions.delete("session-1")

        assertEquals(404, assertFailsWith<CaptureRequestException> { fixture.sessions.delete("session-1") }.status)
        assertEquals(404, assertFailsWith<CaptureRequestException> { fixture.sessions.delete("missing") }.status)
        assertEquals(400, assertFailsWith<CaptureRequestException> { fixture.sessions.delete("../escape") }.status)
    }

    @Test
    fun `reusing a deleted session's request id creates a fresh session`() {
        val fixture = CaptureConsoleFixture(root)
        fixture.create()
        fixture.sessions.delete("session-1")

        val recreated = fixture.create()

        assertEquals("session-1", recreated.id)
        assertNull(recreated.inspection)
        assertEquals(1, fixture.sessions.page(0, 20).total)
    }

    @Test
    fun `a deletion interrupted after its rename is finished at the next start and never listed`() {
        val fixture = CaptureConsoleFixture(root)
        fixture.create()
        val leftover = Files.createDirectories(root.resolve("console/.deleted-old-1/wallet"))
        Files.writeString(leftover.resolve("manifest.json"), "{}")
        assertEquals(1, fixture.sessions.page(0, 20).total)
        fixture.store.close()

        CaptureFileStore(CapturePathPolicy(root), fixture.json).use { it.initialize() }

        assertFalse(Files.exists(root.resolve("console/.deleted-old-1")))
        assertTrue(Files.exists(root.resolve("console/session-1/session.json")))
    }

    /** A symbolic link, or on Windows without that privilege a directory junction, which needs none. */
    private fun link(link: Path, target: Path) {
        try { Files.createSymbolicLink(link, target); return } catch (_: Exception) { }
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "links unavailable")
        val process = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start()
        assumeTrue(process.waitFor() == 0 && Files.exists(link.resolve("keep.json")), "junction unavailable")
    }

    /** Records a successful export, calibration and imported dataset against the current attempts. */
    private fun withDownstreamResults(fixture: CaptureConsoleFixture) {
        val session = fixture.store.session("session-1")
        listOf("export-1" to CaptureNames.EXPORT, "calibrate-1" to CaptureNames.CALIBRATE).forEach { (id, step) ->
            fixture.store.save("session-1", CaptureOperationRecord(id, id, id, step, session.marketAttemptId, CaptureNames.SUCCEEDED,
                FakeCaptureWorkflow.AT, FakeCaptureWorkflow.AT, FakeCaptureWorkflow.AT, CaptureBudget(), null,
                walletAttemptId = session.walletAttemptId, marketAttemptId = session.marketAttemptId, ratesAttemptId = session.ratesAttemptId), true)
        }
        fixture.store.save(session.copy(exportOperationId = "export-1", calibrationOperationId = "calibrate-1", datasetId = "dataset-1"))
    }
}
