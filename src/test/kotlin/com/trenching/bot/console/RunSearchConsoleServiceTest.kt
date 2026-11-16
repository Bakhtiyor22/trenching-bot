package com.trenching.bot.console

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.PageResult
import com.trenching.bot.console.application.RunSearchConsoleService
import com.trenching.bot.console.domain.InvalidConsoleRequestException
import com.trenching.bot.console.domain.RunSearchParams
import com.trenching.bot.replay.SimulationRuns
import com.trenching.bot.replay.domain.RunAggregates
import com.trenching.bot.replay.domain.RunSearchQuery
import com.trenching.bot.replay.domain.RunSearchResult
import com.trenching.bot.replay.domain.RunStart
import com.trenching.bot.replay.domain.RunStatus
import com.trenching.bot.replay.domain.RunStorage
import com.trenching.bot.replay.domain.RunSummary
import com.trenching.bot.strategy.domain.SimulationConfig
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class RunSearchConsoleServiceTest {
    private val runs = RecordingRuns()
    private val service = RunSearchConsoleService(runs, Clock.fixed(NOW, ZoneOffset.UTC))

    @Test
    fun `without filters every status matches and the snapshot is five seconds ago, which a run still being written cannot cross`() {
        val page = service.search(params())

        val query = checkNotNull(runs.query)
        assertEquals(emptySet(), query.statuses)
        assertEquals(SETTLED, query.asOf)
        assertEquals(SETTLED, page.asOf)
        assertNull(query.mint)
        assertNull(query.text)
    }

    @Test
    fun `a requested snapshot is kept and echoed`() {
        val page = service.search(params(asOf = EARLIER))

        assertEquals(EARLIER, checkNotNull(runs.query).asOf)
        assertEquals(EARLIER, page.asOf)
    }

    @Test
    fun `a requested snapshot later than five seconds ago is clamped to it, and the page says which one it used`() {
        listOf(NOW, NOW.plusSeconds(3600), SETTLED.plusNanos(1_000)).forEach { requested ->
            val page = service.search(params(asOf = requested))

            assertEquals(SETTLED, checkNotNull(runs.query).asOf, requested.toString())
            assertEquals(SETTLED, page.asOf, requested.toString())
        }
        assertEquals(SETTLED, service.search(params(asOf = SETTLED)).asOf, "five seconds ago itself is kept")
    }

    @Test
    fun `filters are passed on as typed values, with the text trimmed and blank ids left out`() {
        service.search(params(statuses = listOf("LOSS", "OPEN", "LOSS"), mint = MINT, text = "  alpha  ", datasetId = " ", from = EARLIER, to = NOW))

        val query = checkNotNull(runs.query)
        assertEquals(setOf(RunStatus.LOSS, RunStatus.OPEN), query.statuses)
        assertEquals(MintAddress.of(MINT), query.mint)
        assertEquals("alpha", query.text)
        assertNull(query.datasetId)
        assertEquals(EARLIER, query.anchorFrom)
        assertEquals(NOW, query.createdTo)
    }

    @Test
    fun `an unknown status is refused naming the status field`() {
        assertEquals("status", assertFailsWith<InvalidConsoleRequestException> { service.search(params(statuses = listOf("WON"))) }.field)
        assertEquals("status", assertFailsWith<InvalidConsoleRequestException> { service.search(params(statuses = listOf("loss"))) }.field)
    }

    @Test
    fun `a mint that is not an address is refused naming the mint field`() {
        assertEquals("mint", assertFailsWith<InvalidConsoleRequestException> { service.search(params(mint = "not-a-mint")) }.field)
    }

    @Test
    fun `a range whose start is not before its end is refused naming its end`() {
        assertEquals("anchorTo", assertFailsWith<InvalidConsoleRequestException> { service.search(params(anchorFrom = NOW, anchorTo = NOW)) }.field)
        assertEquals("createdTo", assertFailsWith<InvalidConsoleRequestException> { service.search(params(createdFrom = NOW, createdTo = EARLIER)) }.field)
    }

    @Test
    fun `text outside 2 to 100 characters once trimmed is refused naming q`() {
        listOf("a", "  a  ", "", "x".repeat(101)).forEach { text ->
            assertEquals("q", assertFailsWith<InvalidConsoleRequestException> { service.search(params(text = text)) }.field, text)
        }
        service.search(params(text = "ab"))
        service.search(params(text = "x".repeat(100)))
    }

    @Test
    fun `a negative page or a non positive size is refused`() {
        assertEquals("page", assertFailsWith<InvalidConsoleRequestException> { service.search(params(page = -1)) }.field)
        assertEquals("size", assertFailsWith<InvalidConsoleRequestException> { service.search(params(size = 0)) }.field)
    }

    @Suppress("LongParameterList")
    private fun params(
        page: Int = 0, size: Int = 20, datasetId: String? = null, statuses: List<String> = emptyList(), mint: String? = null,
        from: Instant? = null, to: Instant? = null, anchorFrom: Instant? = from, anchorTo: Instant? = to,
        createdFrom: Instant? = from, createdTo: Instant? = to, text: String? = null, asOf: Instant? = null,
    ) = RunSearchParams(page, size, datasetId, statuses, mint, anchorFrom, anchorTo, createdFrom, createdTo, text, asOf)

    private class RecordingRuns : SimulationRuns {
        var query: RunSearchQuery? = null

        override fun search(query: RunSearchQuery): RunSearchResult {
            this.query = query
            return RunSearchResult(PageResult(emptyList(), query.page, query.size, 0), RunStatus.entries.associateWith { 0L })
        }

        override fun start(datasetId: String, config: SimulationConfig, storage: RunStorage): RunStart = error("not used")
        override fun page(page: Int, size: Int, datasetId: String?): PageResult<RunSummary> = error("not used")
        override fun find(runId: String): RunSummary? = error("not used")
        override fun report(runId: String): String = error("not used")
        override fun totals(createdSince: Instant): RunAggregates = error("not used")
    }

    private companion object {
        const val MINT = "11111111111111111111111111111112"
        val NOW: Instant = Instant.parse("2026-11-16T07:00:00.123456Z")
        val EARLIER: Instant = Instant.parse("2026-11-15T07:00:00Z")
        val SETTLED: Instant = NOW.minusSeconds(5)
    }
}
