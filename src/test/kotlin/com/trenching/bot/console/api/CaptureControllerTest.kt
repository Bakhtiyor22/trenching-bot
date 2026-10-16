package com.trenching.bot.console.api

import com.trenching.bot.common.PageResult
import com.trenching.bot.console.capture.CaptureCatalogService
import com.trenching.bot.console.capture.CaptureConsoleFixture
import com.trenching.bot.console.capture.CaptureCaseService
import com.trenching.bot.console.capture.ManualExecutor
import com.trenching.bot.console.config.SolanaRpcProperties
import com.trenching.bot.console.mapper.CaptureResponseMapperImpl
import com.trenching.bot.console.mapper.DatasetResponseMapperImpl
import com.trenching.bot.marketdata.ReferenceRateProperties
import com.trenching.bot.replay.ReplayDatasetCatalog
import com.trenching.bot.replay.domain.DatasetRegistration
import com.trenching.bot.replay.domain.DatasetSummary
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.SampleDataset
import java.io.InputStream
import java.nio.file.Path
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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

    @Test
    fun `the capture config always sends the wallet-token defaults and their budget`() {
        val fixture = CaptureConsoleFixture(root)
        val mapper = CaptureResponseMapperImpl()
        val cases = CaptureCaseService(fixture.store, fixture.workflow, mapper, DatasetResponseMapperImpl(), UnusedCatalog, fixture.clock, ManualExecutor())
        val controller = CaptureController(fixture.sessions, fixture.operations, cases, CaptureCatalogService(fixture.store, fixture.operations, UnusedCatalog),
            mapper, DatasetResponseMapperImpl(), SolanaRpcProperties(), ReferenceRateProperties())

        val config = fixture.json.readTree(fixture.json.writeValueAsString(controller.config()))

        assertEquals(fixture.json.readTree("""{"wallet":"","scope":"wallet-token","limit":1000,"scanPages":20,"mint":""}"""), config["walletTokenDefaults"])
        assertEquals(fixture.json.readTree("""{"requestBudget":1000,"byteBudget":104857600,"operationTimeoutMinutes":10}"""), config["walletTokenBudgetDefaults"])
        assertEquals(fixture.json.readTree("""{"wallet":"","scope":"pumpfun-user-buys","limit":1,"scanPages":20}"""), config["walletDefaults"])
        assertEquals(fixture.json.readTree("""{"requestBudget":400,"byteBudget":104857600,"operationTimeoutMinutes":10}"""), config["budgetDefaults"])
    }
}
