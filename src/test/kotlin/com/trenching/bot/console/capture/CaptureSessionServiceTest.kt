package com.trenching.bot.console.capture

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.console.api.dto.CaptureAttachment
import com.trenching.bot.console.api.dto.CaptureAttemptRequest
import com.trenching.bot.console.api.dto.CaptureBudget
import com.trenching.bot.console.api.dto.CaptureCreateRequest
import com.trenching.bot.console.api.dto.CaptureMarketSettings
import com.trenching.bot.console.api.dto.CaptureOperationRequest
import com.trenching.bot.console.api.dto.CaptureRenameRequest
import com.trenching.bot.console.api.dto.CaptureWalletSettings
import com.trenching.bot.replay.CaptureStepCommand
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
    fun `creating a session allocates managed attempts and queues only the offline inspection`() {
        val fixture = CaptureConsoleFixture(root)
        val session = fixture.sessions.create(CaptureCreateRequest("session-1", "Sample", wallet = CaptureWalletSettings(CaptureConsoleFixture.WALLET)))

        assertEquals("Sample", session.name)
        assertNotNull(session.marketAttempt)
        assertEquals(CaptureMarketSettings(cutoffMode = "FULL"), session.marketAttempt?.market)
        assertEquals(session.marketAttempt?.id, session.ratesAttempt?.marketAttemptId)
        assertEquals(CaptureNames.INSPECT, session.operation?.step)
        assertEquals("console/session-1/wallet", fixture.store.attempt("session-1", session.walletAttemptId).directory)
        fixture.executor.runAll()

        assertTrue(fixture.workflow.executed.single() is CaptureStepCommand.Inspect)
        assertEquals(CaptureNames.SUCCEEDED, fixture.sessions.find("session-1").operation?.state)
        assertNotNull(fixture.sessions.find("session-1").inspection)
    }

    @Test
    fun `a session created while another capture runs skips the inspection instead of failing`() {
        val fixture = CaptureConsoleFixture(root)
        val running = fixture.create()
        fixture.operations.start("session-1", CaptureOperationRequest("inspect-1", CaptureNames.INSPECT, running.marketAttempt?.id))

        val session = fixture.create("session-2")

        assertNull(session.operation)
        assertNull(session.inspection)
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
        assertEquals(CaptureMarketSettings(80, 1000, "FULL"), updated.marketAttempt?.market)
        assertEquals("console/session-1/markets/market-2", fixture.store.attempt("session-1", "market-2").directory)
        assertNull(updated.inspection)
        assertFalse(updated.datasetReady)
        assertFalse(updated.calibrationReady)
        assertNull(updated.datasetId)
        assertEquals(5, fixture.sessions.attempts("session-1", 0, 20).total)
    }

    @Test
    fun `a new market attempt brings its own empty rates attempt and queues only the offline inspection`() {
        val fixture = CaptureConsoleFixture(root)
        val original = fixture.create()

        val updated = fixture.sessions.newAttempt("session-1", CaptureAttemptRequest("market-2", CaptureNames.MARKET))
        val rates = assertNotNull(updated.ratesAttempt)

        assertEquals("market-2", rates.marketAttemptId)
        assertEquals(original.walletAttemptId, rates.walletAttemptId)
        assertEquals("console/session-1/rates/${rates.id}", rates.directory)
        assertFalse(rates.attached)
        assertEquals(CaptureNames.INSPECT, updated.operation?.step)
        assertEquals("market-2", updated.operation?.attemptId)
        fixture.executor.runAll()

        val inspect = fixture.workflow.executed.single() as CaptureStepCommand.Inspect
        assertEquals(root.resolve("console/session-1/markets/market-2"), inspect.paths.market)
        assertEquals(root.resolve(rates.directory), inspect.paths.rates)
        assertNotNull(fixture.sessions.find("session-1").inspection)
    }

    @Test
    fun `replaying a new attempt request returns the session without a second attempt or inspection`() {
        val fixture = CaptureConsoleFixture(root)
        fixture.create()
        val first = fixture.sessions.newAttempt("session-1", CaptureAttemptRequest("market-2", CaptureNames.MARKET))
        fixture.settle()

        val replayed = fixture.sessions.newAttempt("session-1", CaptureAttemptRequest("market-2", CaptureNames.MARKET))

        assertEquals(first.ratesAttempt?.id, replayed.ratesAttempt?.id)
        assertEquals(5, fixture.sessions.attempts("session-1", 0, 20).total)
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
        assertEquals(3, fixture.sessions.attempts("session-1", 0, 20).total)
        assertEquals(1, fixture.store.operations("session-1").size)
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

    @Test
    fun `a wallet-token session's identity includes its mint and settings without a mint keep their legacy form`() {
        val fixture = CaptureConsoleFixture(root)
        val token = CaptureWalletSettings(CaptureConsoleFixture.WALLET, "wallet-token", 100, 5, CaptureTokenFixtures.MINT)
        fixture.sessions.create(CaptureCreateRequest("session-1", "Token", wallet = token))
        fixture.settle()

        conflict("REQUEST_CONFLICT") {
            fixture.sessions.create(CaptureCreateRequest("session-1", "Token", wallet = token.copy(mint = CaptureConsoleFixture.WALLET)))
        }
        assertEquals(token, fixture.sessions.create(CaptureCreateRequest("session-1", "Token", wallet = token)).wallet)
        assertEquals("""{"wallet":"${CaptureConsoleFixture.WALLET}","scope":"pumpfun-user-buys","limit":1,"scanPages":20}""",
            fixture.json.writeValueAsString(CaptureWalletSettings(CaptureConsoleFixture.WALLET)))
    }

    @Test
    fun `session and operation files written before the mint and the verdict existed load unchanged`() {
        val fixture = CaptureConsoleFixture(root)
        val stage = """{"state":"READY","reason":null,"canStart":false,"canResume":false,"requiresNewAttempt":false}"""
        val inspection = """{"wallet":$stage,"market":$stage,"rates":$stage,"canCalibrate":false,"canExport":false,"walletSummary":{},"marketSummary":{},"ratesSummary":{},"marketWindows":[]}"""
        val wallet = """{"wallet":"${CaptureConsoleFixture.WALLET}","scope":"wallet-references","limit":5,"scanPages":2}"""
        Files.createDirectories(root.resolve("console/legacy-1"))
        Files.writeString(root.resolve("console/legacy-1/session.json"), """{"version":1,"id":"legacy-1","requestId":"legacy-1","fingerprint":"f","name":"Old",""" +
            """"createdAt":"2026-10-09T09:00:00Z","wallet":$wallet,"walletAttemptId":"w1","marketAttemptId":null,"ratesAttemptId":null,"inspection":$inspection,""" +
            """"operationId":"op-1","exportOperationId":null,"calibrationOperationId":null,"datasetId":null}""")
        Files.writeString(root.resolve("console/legacy-1/operation-op-1.json"), """{"id":"op-1","requestId":"op-1","fingerprint":"f","step":"INSPECT",""" +
            """"attemptId":null,"state":"SUCCEEDED","startedAt":"2026-10-09T09:00:00Z","updatedAt":"2026-10-09T09:00:00Z","endedAt":"2026-10-09T09:00:00Z",""" +
            """"budget":{"requestBudget":400,"byteBudget":104857600,"operationTimeoutMinutes":10},"scanPages":null,"progress":null,"code":null,"message":null,""" +
            """"walletAttemptId":"w1","marketAttemptId":null,"ratesAttemptId":null,"inspection":$inspection,"walletSettings":$wallet,"artifact":null,"checksum":null,"calibration":null}""")

        val session = fixture.sessions.find("legacy-1")

        assertEquals(CaptureWalletSettings(CaptureConsoleFixture.WALLET, "wallet-references", 5, 2), session.wallet)
        assertNull(session.qualification)
        assertNull(session.inspection?.targetToken)
        assertNull(session.operation?.qualification)
        assertEquals(CaptureNames.SUCCEEDED, session.operation?.state)
    }

    @Test
    fun `attaching a wallet + token capture shows its scope, its manifest mint and its inspection verdict`() {
        val fixture = CaptureConsoleFixture(root)
        fixture.workflow.inspection = CaptureTokenFixtures.exportReady()

        fixture.sessions.create(CaptureCreateRequest("session-1", "Attached", attach = CaptureAttachment("token-wallet")))
        fixture.executor.runAll()

        val session = fixture.sessions.find("session-1")
        assertEquals(CaptureWalletSettings(CaptureConsoleFixture.WALLET, "wallet-token", 100, 5, CaptureTokenFixtures.MINT), session.wallet)
        assertEquals(CaptureTokenFixtures.MINT, session.inspection?.targetToken?.mint)
        assertEquals("INCOMPLETE_CAPTURE", session.qualification?.classification)
        assertEquals(CaptureTokenFixtures.exportReady() to false, fixture.workflow.verdicts.single())
    }

    @Test
    fun `before export only a wallet-token session shows a verdict, from inspection alone`() {
        val fixture = CaptureConsoleFixture(root)

        assertEquals("NOT_RUN", fixture.createToken("session-1").qualification?.replay?.state)
        assertNull(fixture.create("session-2").qualification)
        assertEquals(1, fixture.workflow.verdicts.size)
    }

    @Test
    fun `a wallet-token market attempt decides early by default and every other scope captures in full`() {
        val fixture = CaptureConsoleFixture(root)

        assertEquals("DECIDED", fixture.createToken("session-1").marketAttempt?.market?.cutoffMode)
        assertEquals("FULL", fixture.create("session-2").marketAttempt?.market?.cutoffMode)
        val references = fixture.sessions.create(CaptureCreateRequest("session-3", "References",
            wallet = CaptureWalletSettings(CaptureConsoleFixture.WALLET, "wallet-references", 4, 8)))
        assertEquals("FULL", references.marketAttempt?.market?.cutoffMode)
        fixture.settle()
        assertEquals("DECIDED", fixture.sessions.newAttempt("session-1", CaptureAttemptRequest("market-2", CaptureNames.MARKET)).marketAttempt?.market?.cutoffMode)
        fixture.settle()
        assertEquals("FULL", fixture.sessions.newAttempt("session-2", CaptureAttemptRequest("market-3", CaptureNames.MARKET)).marketAttempt?.market?.cutoffMode)
    }

    @Test
    fun `an explicit cutoff mode replaces the scope default and an unknown one is a per-field error`() {
        val fixture = CaptureConsoleFixture(root)
        val token = CaptureWalletSettings(CaptureConsoleFixture.WALLET, "wallet-token", 100, 5, CaptureTokenFixtures.MINT)

        assertEquals("FULL", fixture.sessions.create(CaptureCreateRequest("session-1", "Token", wallet = token, cutoffMode = "FULL")).marketAttempt?.market?.cutoffMode)
        fixture.settle()
        assertEquals("FULL", fixture.sessions.newAttempt("session-1",
            CaptureAttemptRequest("market-2", CaptureNames.MARKET, CaptureMarketSettings(cutoffMode = "FULL"))).marketAttempt?.market?.cutoffMode)
        fixture.settle()
        val unknown = assertFailsWith<CaptureRequestException> {
            fixture.sessions.newAttempt("session-1", CaptureAttemptRequest("market-3", CaptureNames.MARKET, CaptureMarketSettings(cutoffMode = "SOMETIMES")))
        }
        assertEquals(400, unknown.status)
        assertEquals(setOf("cutoffMode"), unknown.fields.keys)
        assertEquals(400, assertFailsWith<CaptureRequestException> {
            fixture.sessions.create(CaptureCreateRequest("session-2", "Token", wallet = token, cutoffMode = "decided"))
        }.status)
        assertEquals(1, fixture.sessions.page(0, 20).total)
    }

    @Test
    fun `without reference rates a wallet-token attempt captures in full and an explicit DECIDED attempt is refused`() {
        val fixture = CaptureConsoleFixture(root, ratesConfigured = false)
        val token = CaptureWalletSettings(CaptureConsoleFixture.WALLET, "wallet-token", 100, 5, CaptureTokenFixtures.MINT)

        assertEquals("FULL", fixture.createToken().marketAttempt?.market?.cutoffMode)
        conflict("RATES_NOT_CONFIGURED") {
            fixture.sessions.newAttempt("session-1", CaptureAttemptRequest("market-2", CaptureNames.MARKET, CaptureMarketSettings(cutoffMode = "DECIDED")))
        }
        conflict("RATES_NOT_CONFIGURED") {
            fixture.sessions.create(CaptureCreateRequest("session-2", "Token", wallet = token, cutoffMode = "DECIDED"))
        }
        assertEquals(1, fixture.sessions.page(0, 20).total)
        assertEquals(3, fixture.sessions.attempts("session-1", 0, 20).total)
    }

    @Test
    fun `a market attempt saved before cutoff modes existed loads without one and its identity keeps the legacy form`() {
        val fixture = CaptureConsoleFixture(root)

        assertEquals(CaptureMarketSettings(50, 50000, null), fixture.json.readValue("""{"scanPages":50,"maxTransactions":50000}""", CaptureMarketSettings::class.java))
        assertEquals("""{"scanPages":50,"maxTransactions":50000}""", fixture.json.writeValueAsString(CaptureMarketSettings()))
        assertFalse("cutoffMode" in fixture.json.writeValueAsString(CaptureCreateRequest("r", "n")))
    }

    private val decu = CaptureSuggestedWallets(listOf(CaptureWalletSuggestion("Decu", CaptureConsoleFixture.WALLET)))
    private val decuToken = CaptureWalletSettings(CaptureConsoleFixture.WALLET, "wallet-token", 100, 5, "7mupUtwu17wevEfnyrE6XsWa1X9WWntAPeqdckd2pump")
    private val decuMinute: Instant = Instant.parse("2026-10-16T20:13:00Z")
    private val decuName = "Decu · 7mup…pump · 16 Oct 20:13"

    @Test
    fun `a blank name is generated, a repeat is numbered and a typed taken name is refused with the next free one`() {
        val fixture = CaptureConsoleFixture(root, clock = Clock.fixed(decuMinute, ZoneOffset.UTC), suggested = decu)

        val first = fixture.sessions.create(CaptureCreateRequest("decu-1", wallet = decuToken))
        val second = fixture.sessions.create(CaptureCreateRequest("decu-2", " ", wallet = decuToken))
        val taken = assertFailsWith<CaptureRequestException> {
            fixture.sessions.create(CaptureCreateRequest("decu-3", "decu · 7MUP…pump · 16 Oct 20:13 (2)", wallet = decuToken))
        }

        assertEquals(decuName, first.name)
        assertEquals("$decuName (2)", second.name)
        assertEquals("decu · 7mup…pump · 16 oct 20:13", fixture.store.session("decu-1").nameKey)
        assertEquals(409, taken.status)
        assertEquals("NAME_TAKEN", taken.code)
        assertEquals("$decuName (3)", taken.suggestion)
        assertFalse(Files.exists(root.resolve("console/decu-3")))
    }

    @Test
    fun `retrying a blank-name create later returns the session it created`() {
        val clock = MutableClock(decuMinute)
        val fixture = CaptureConsoleFixture(root, clock = clock, suggested = decu)
        val first = fixture.sessions.create(CaptureCreateRequest("decu-1", wallet = decuToken))
        clock.now = decuMinute.plusSeconds(600)

        val retried = fixture.sessions.create(CaptureCreateRequest("decu-1", "", wallet = decuToken))

        assertEquals(first.name, retried.name)
        assertEquals(1, fixture.sessions.page(0, 20).total)
    }

    @Test
    fun `the generated time uses the request's zone and an unknown zone is a field error`() {
        val fixture = CaptureConsoleFixture(root, clock = Clock.fixed(decuMinute, ZoneOffset.UTC), suggested = decu)

        assertEquals("Decu · 7mup…pump · 16 Oct 22:13", fixture.sessions.create(CaptureCreateRequest("berlin", wallet = decuToken, timeZone = "Europe/Berlin")).name)
        val error = assertFailsWith<CaptureRequestException> { fixture.sessions.create(CaptureCreateRequest("mars", wallet = decuToken, timeZone = "Mars/Olympus")) }
        assertEquals(400, error.status)
        assertEquals(setOf("timeZone"), error.fields.keys)
    }

    @Test
    fun `the list is newest first by default with ties by id and the counts cover every session`() {
        val clock = MutableClock(Instant.parse("2026-10-10T09:00:00Z"))
        val fixture = CaptureConsoleFixture(root, clock = clock)
        fixture.create("old")
        clock.now = decuMinute
        fixture.create("new-b")
        fixture.create("new-a")

        val page = fixture.sessions.page(0, 20)

        assertEquals(listOf("new-a", "new-b", "old"), page.items.map { it.id })
        assertEquals(3, page.total)
        assertEquals(CaptureSummaryStatus.entries.toSet(), page.statusCounts.keys)
        assertEquals(page.total, page.statusCounts.values.sum())
        assertEquals(3, page.statusCounts[CaptureSummaryStatus.NOT_STARTED])
    }

    @Test
    fun `each sort orders the list and the status filter keeps only matching sessions while counts cover all`() {
        val clock = MutableClock(Instant.parse("2026-10-10T09:00:00Z"))
        val fixture = CaptureConsoleFixture(root, clock = clock)
        listOf("b-session", "a-session", "c-session").forEach { id -> fixture.create(id); clock.now = clock.now.plusSeconds(60) }
        fixture.workflow.failure = CaptureValidationException("BROKEN")
        fixture.operations.start("a-session", CaptureOperationRequest("inspect-x", CaptureNames.INSPECT, fixture.sessions.find("a-session").marketAttempt?.id))
        fixture.executor.runAll()
        fun ids(sort: String, status: String? = null) = fixture.sessions.page(0, 20, sort, status).items.map { it.id }

        assertEquals(listOf("b-session", "a-session", "c-session"), ids("createdAt,asc"))
        assertEquals(listOf("a-session", "b-session", "c-session"), ids("name,asc"))
        assertEquals(listOf("a-session", "c-session", "b-session"), ids("attention"))
        val attention = fixture.sessions.page(0, 20, "createdAt,desc", "NEEDS_ATTENTION")
        assertEquals(listOf("a-session"), attention.items.map { it.id })
        assertEquals(1, attention.total)
        assertEquals(2, attention.statusCounts[CaptureSummaryStatus.NOT_STARTED])
        assertEquals(listOf("a-session"), fixture.sessions.page(1, 1).items.map { it.id })
        assertEquals(setOf("sort"), assertFailsWith<CaptureRequestException> { fixture.sessions.page(0, 20, "size,desc") }.fields.keys)
        assertEquals(setOf("status"), assertFailsWith<CaptureRequestException> { fixture.sessions.page(0, 20, status = "LOST") }.fields.keys)
    }

    @Test
    fun `renaming is idempotent per request id and follows the same uniqueness rules`() {
        val fixture = CaptureConsoleFixture(root)
        fixture.create("session-1")
        fixture.create("session-2")

        val renamed = fixture.sessions.rename("session-1", CaptureRenameRequest("rename-1", "  Decu   first "))
        val taken = assertFailsWith<CaptureRequestException> { fixture.sessions.rename("session-2", CaptureRenameRequest("rename-2", "DECU FIRST")) }

        assertEquals("Decu first", renamed.name)
        assertEquals("Decu first", renamed.displayName)
        assertEquals("decu first", fixture.store.session("session-1").nameKey)
        assertEquals("Decu first", fixture.sessions.rename("session-1", CaptureRenameRequest("rename-1", "Decu first")).name)
        conflict("REQUEST_CONFLICT") { fixture.sessions.rename("session-1", CaptureRenameRequest("rename-1", "Other")) }
        assertEquals("NAME_TAKEN", taken.code)
        assertEquals("DECU FIRST (2)", taken.suggestion)
        assertEquals("decu FIRST", fixture.sessions.rename("session-1", CaptureRenameRequest("rename-3", "decu FIRST")).name)
        assertEquals(setOf("name"), assertFailsWith<CaptureRequestException> { fixture.sessions.rename("session-2", CaptureRenameRequest("rename-4", "  ")) }.fields.keys)
    }

    @Test
    fun `a name check reports the stored form and the next free name when it is taken`() {
        val fixture = CaptureConsoleFixture(root)
        fixture.create("session-1")

        assertEquals(CaptureNameCheck(true, "Fresh name", null), fixture.sessions.checkName("  Fresh   name "))
        assertEquals(CaptureNameCheck(false, "sample SESSION-1", "sample SESSION-1 (2)"), fixture.sessions.checkName("sample SESSION-1"))
        assertEquals(400, assertFailsWith<CaptureRequestException> { fixture.sessions.checkName("a".repeat(121)) }.status)
    }

    @Test
    fun `legacy duplicate names are numbered on read and their files stay byte-identical`() {
        val fixture = CaptureConsoleFixture(root)
        fun legacy(id: String, createdAt: String): Path {
            val file = root.resolve("console/$id/session.json")
            Files.createDirectories(file.parent)
            Files.writeString(file, """{"version":1,"id":"$id","requestId":"$id","fingerprint":"f","name":"runitback","createdAt":"$createdAt",""" +
                """"wallet":null,"walletAttemptId":"w-$id","marketAttemptId":null,"ratesAttemptId":null,"inspection":null,"operationId":null}""")
            return file
        }
        val files = listOf(legacy("legacy-b", "2026-10-10T09:00:00Z"), legacy("legacy-a", "2026-10-10T09:00:00Z"), legacy("legacy-c", "2026-10-09T09:00:00Z"))
        val before = files.map(Files::readAllBytes)

        val listed = fixture.sessions.page(0, 20, "createdAt,asc").items.map { it.id to it.displayName }
        val found = fixture.sessions.find("legacy-b").displayName
        val taken = assertFailsWith<CaptureRequestException> { fixture.sessions.create(CaptureCreateRequest("new", "runitback (2)", wallet = CaptureWalletSettings(CaptureConsoleFixture.WALLET))) }

        assertEquals(listOf("legacy-c" to "runitback", "legacy-a" to "runitback (2)", "legacy-b" to "runitback (3)"), listed)
        assertEquals("runitback (3)", found)
        assertEquals("runitback (4)", taken.suggestion)
        assertTrue(before.zip(files.map(Files::readAllBytes)).all { (old, now) -> old.contentEquals(now) })
    }

    @Test
    fun `the summary counts cases from their records and ignores records of earlier attempts`() {
        val fixture = CaptureConsoleFixture(root)
        val session = fixture.store.session(fixture.create().id)
        val at = FakeCaptureWorkflow.AT
        fixture.store.save(session.id, CaptureOperationRecord("export-a", "export-a", "f", CaptureNames.EXPORT, session.marketAttemptId, CaptureNames.SUCCEEDED, at, at, at,
            CaptureBudget(), null, walletAttemptId = session.walletAttemptId, marketAttemptId = session.marketAttemptId, ratesAttemptId = session.ratesAttemptId,
            artifact = "console/${session.id}/generated/cases/case-a/dataset.json", caseId = "case-a"), true)
        fixture.store.save(session.id, CaptureCaseRecord(caseId = "case-a", walletAttemptId = session.walletAttemptId, marketAttemptId = requireNotNull(session.marketAttemptId),
            ratesAttemptId = session.ratesAttemptId, operationId = "export-a", exportOperationId = "export-a"))
        fixture.store.save(session.id, CaptureCaseRecord(caseId = "case-old", walletAttemptId = session.walletAttemptId, marketAttemptId = "market-old", ratesAttemptId = null, datasetId = "dataset-old"))

        val summary = fixture.sessions.find(session.id).summary

        assertEquals(CaptureSummaryStatus.READY_TO_REPLAY, summary.status)
        assertEquals(CaptureNextAction(CaptureNextActionKind.REGISTER_DATASET, CaptureStepStage.DATASET, null, "case-a", null), summary.nextAction)
        assertEquals(CaptureCaseCounts(total = 1, ready = 1, blocked = 0, downloading = 0), summary.cases)
    }
}
