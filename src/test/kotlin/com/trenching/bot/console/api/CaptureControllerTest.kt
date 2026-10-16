package com.trenching.bot.console.api

import com.trenching.bot.common.PageResult
import com.trenching.bot.console.api.dto.CaptureCreateRequest
import com.trenching.bot.console.api.dto.CaptureRenameRequest
import com.trenching.bot.console.api.dto.CaptureWalletSettings
import com.trenching.bot.console.capture.CaptureCatalogService
import com.trenching.bot.console.capture.CaptureConsoleFixture
import com.trenching.bot.console.capture.CaptureCaseService
import com.trenching.bot.console.capture.CaptureRequestException
import com.trenching.bot.console.capture.CaptureSetupService
import com.trenching.bot.console.capture.CaptureSuggestedWallets
import com.trenching.bot.console.capture.CaptureTokenFixtures
import com.trenching.bot.console.capture.CaptureValidation
import com.trenching.bot.console.capture.CaptureWalletSuggestion
import com.trenching.bot.console.capture.ManualExecutor
import com.trenching.bot.console.capture.MutableClock
import com.trenching.bot.console.mapper.CaptureResponseMapperImpl
import com.trenching.bot.console.mapper.ConsoleErrorMapperImpl
import com.trenching.bot.console.mapper.DatasetResponseMapperImpl
import com.trenching.bot.console.mapper.RecommendationResponseMapperImpl
import com.trenching.bot.replay.ReplayDatasetCatalog
import com.trenching.bot.replay.domain.DatasetRegistration
import com.trenching.bot.replay.domain.DatasetSummary
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.SampleDataset
import java.io.InputStream
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode

class CaptureControllerTest {
    @TempDir lateinit var root: Path

    private object UnusedCatalog : ReplayDatasetCatalog {
        override fun register(name: String, json: InputStream): DatasetRegistration = error("unused")
        override fun importSample(sample: SampleDataset): DatasetRegistration = error("unused")
        override fun page(page: Int, size: Int): PageResult<DatasetSummary> = error("unused")
        override fun find(id: String): DatasetSummary? = error("unused")
        override fun load(id: String): ReplayDataset = error("unused")
        override fun delete(id: String): Boolean = error("unused")
    }

    private val decu = CaptureSuggestedWallets(listOf(CaptureWalletSuggestion("Decu", CaptureConsoleFixture.WALLET)))
    private val clock = MutableClock(Instant.parse("2026-10-16T20:13:00Z"))

    private fun controller(fixture: CaptureConsoleFixture, ratesConfigured: Boolean = false): CaptureController {
        val mapper = CaptureResponseMapperImpl()
        val cases = CaptureCaseService(fixture.store, fixture.workflow, mapper, DatasetResponseMapperImpl(), UnusedCatalog, fixture.clock, ManualExecutor())
        return CaptureController(fixture.sessions, CaptureSetupService(fixture.store, false, ratesConfigured, decu), fixture.operations, cases,
            CaptureCatalogService(fixture.store, fixture.operations, UnusedCatalog), mapper, DatasetResponseMapperImpl(), RecommendationResponseMapperImpl())
    }

    private fun CaptureConsoleFixture.tree(value: Any): JsonNode = json.readTree(json.writeValueAsString(value))

    @Test
    fun `the capture config always sends the wallet-token defaults and their budget`() {
        val fixture = CaptureConsoleFixture(root)

        val config = fixture.tree(controller(fixture).config())

        assertEquals(fixture.json.readTree("""{"wallet":"","scope":"wallet-token","limit":1000,"scanPages":20,"mint":""}"""), config["walletTokenDefaults"])
        assertEquals(fixture.json.readTree("""{"requestBudget":1000,"byteBudget":104857600,"operationTimeoutMinutes":10}"""), config["walletTokenBudgetDefaults"])
        assertEquals(fixture.json.readTree("""{"wallet":"","scope":"pumpfun-user-buys","limit":1,"scanPages":20}"""), config["walletDefaults"])
        assertEquals(fixture.json.readTree("""{"requestBudget":400,"byteBudget":104857600,"operationTimeoutMinutes":10}"""), config["budgetDefaults"])
        assertEquals(4500, config["windowSlots"].asLong())
    }

    @Test
    fun `the capture config suggests the configured wallets and lists recent wallets newest first`() {
        val fixture = CaptureConsoleFixture(root, clock = clock, suggested = decu)
        val other = CaptureTokenFixtures.MINT
        listOf("first" to CaptureConsoleFixture.WALLET, "second" to other, "third" to CaptureConsoleFixture.WALLET).forEach { (id, wallet) ->
            fixture.sessions.create(CaptureCreateRequest(id, wallet = CaptureWalletSettings(wallet)))
            fixture.settle()
            clock.now = clock.now.plusSeconds(60)
        }

        val config = fixture.tree(controller(fixture).config())

        assertEquals(fixture.json.readTree("""[{"label":"Decu","address":"${CaptureConsoleFixture.WALLET}","reasonCode":"AGREED_STRATEGY_WALLET"}]"""), config["suggestedWallets"])
        assertEquals(fixture.json.readTree("""[{"address":"${CaptureConsoleFixture.WALLET}","label":"Decu","lastUsedAt":"2026-10-16T20:15:00Z"},""" +
            """{"address":"$other","label":null,"lastUsedAt":"2026-10-16T20:14:00Z"}]"""), config["recentWallets"])
    }

    @Test
    fun `every listed session carries its display name and summary and the page carries status counts`() {
        val fixture = CaptureConsoleFixture(root, clock = clock, suggested = decu)
        fixture.create()

        val page = fixture.tree(controller(fixture).page(0, 20, "createdAt,desc", null))
        val item = page["items"][0]

        assertEquals(10, page["statusCounts"].size())
        assertEquals(1, page["statusCounts"]["NOT_STARTED"].asLong())
        assertEquals(1, page["total"].asLong())
        assertEquals("Sample session-1", item["displayName"].asString())
        assertEquals(fixture.json.readTree("""{"status":"NOT_STARTED","reasonCode":null,"currentStep":{"index":1,"stage":"WALLET"},""" +
            """"steps":[{"stage":"WALLET","state":"TODO"},{"stage":"MARKETS","state":"TODO"},{"stage":"RATES","state":"TODO"},{"stage":"DATASET","state":"TODO"}],""" +
            """"progress":null,"nextAction":{"kind":"START_WALLET","stage":"WALLET","attemptId":"id-1","caseId":null,"maxRequests":400},""" +
            """"cases":{"total":0,"ready":0,"blocked":0,"downloading":0},"lastProgressAt":null}"""), item["summary"])
    }

    @Test
    fun `the name check and rename endpoints answer in the contract shape`() {
        val fixture = CaptureConsoleFixture(root, clock = clock, suggested = decu)
        fixture.create()
        val controller = controller(fixture)

        assertEquals(fixture.json.readTree("""{"available":false,"normalized":"Sample session-1","suggestion":"Sample session-1 (2)"}"""),
            fixture.tree(controller.checkName("  Sample   session-1")))
        assertEquals(fixture.json.readTree("""{"available":true,"normalized":"Decu run","suggestion":null}"""), fixture.tree(controller.checkName("Decu run")))
        val renamed = fixture.tree(controller.rename("session-1", CaptureRenameRequest("rename-1", "Decu run")))
        assertEquals("Decu run", renamed["name"].asString())
        assertEquals("Decu run", renamed["displayName"].asString())
    }

    @Test
    fun `capture recommendations answer in the shared recommendation shape`() {
        val fixture = CaptureConsoleFixture(root)

        val recommendations = fixture.tree(controller(fixture, ratesConfigured = true).recommendations(CaptureConsoleFixture.WALLET, "wallet-references", null))

        assertEquals(listOf("SCOPE_WALLET_TOKEN", "CUTOFF_DECIDED", "ENABLE_RECOVERY", "DECU_PUMPFUN_SCOPE"), recommendations.map { it["id"].asString() })
        assertEquals(fixture.json.readTree("""{"id":"CUTOFF_DECIDED","appliesTo":"CAPTURE","setting":{"field":"cutoffMode","value":"DECIDED"},""" +
            """"facts":{"dipPercent":"15","takeProfitPercent":"30","stopLossPercent":"15"},"caveatCode":"CUTOFF_USES_LEGACY_DEFAULTS"}"""), recommendations[1])
        assertEquals(fixture.json.readTree("""{"requests":1200,"bytes":314572800,"attempts":3}"""), recommendations[2]["facts"])
    }

    @Test
    fun `a taken name answers 409 with the suggestion and other capture errors keep the plain error body`() {
        val fixture = CaptureConsoleFixture(root)
        val handler = CaptureExceptionHandler(ConsoleErrorMapperImpl(), CaptureResponseMapperImpl())
        val taken = runCatching { CaptureValidation.nameTaken("runitback (2)") }.exceptionOrNull() as CaptureRequestException
        val invalid = runCatching { CaptureValidation.invalid("sort", "unsupported") }.exceptionOrNull() as CaptureRequestException

        val conflict = handler.capture(taken)
        val plain = fixture.tree(requireNotNull(handler.capture(invalid).body))

        assertEquals(409, conflict.statusCode.value())
        assertEquals(fixture.json.readTree("""{"code":"NAME_TAKEN","message":"Capture name is already taken","fields":{"name":"is already taken"},"suggestion":"runitback (2)"}"""),
            fixture.tree(requireNotNull(conflict.body)))
        assertFalse(plain.has("suggestion"))
        assertEquals(fixture.json.readTree("""{"sort":"unsupported"}"""), plain["fields"])
    }
}
