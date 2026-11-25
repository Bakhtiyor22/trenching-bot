package com.trenching.bot.replay

import com.trenching.bot.common.MintAddress
import com.trenching.bot.console.application.RunSearchConsoleService
import com.trenching.bot.console.application.RunVariationLabels
import com.trenching.bot.console.domain.RunVariationLookup
import com.trenching.bot.console.domain.RunSearchParams
import com.trenching.bot.replay.domain.RunQuoteTotal
import com.trenching.bot.replay.domain.RunSearchQuery
import com.trenching.bot.replay.domain.RunStatus
import com.trenching.bot.replay.infrastructure.persistence.ReplayDatasetRepository
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunRepository
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunTokenRepository
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import com.trenching.bot.support.RunSearchFixtures
import com.trenching.bot.support.StubOutcomeStores
import com.trenching.bot.support.RunSearchFixtures.Companion.MINT_A
import com.trenching.bot.support.RunSearchFixtures.Companion.MINT_B
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/** A context of its own; every test starts from empty tables and writes its runs straight to them. */
@SpringBootTest(properties = ["spring.application.name=simulation-run-search"])
@ActiveProfiles("test")
@Tag("integration")
@Import(PostgresTestDatabaseConfiguration::class)
class SimulationRunSearchIntegrationTest {
    @Autowired private lateinit var runs: SimulationRuns
    @Autowired private lateinit var catalog: ReplayDatasetCatalog
    @Autowired private lateinit var runRepository: SimulationRunRepository
    @Autowired private lateinit var tokenRepository: SimulationRunTokenRepository
    @Autowired private lateinit var datasetRepository: ReplayDatasetRepository
    @Autowired private lateinit var dataSource: DataSource

    private lateinit var fixtures: RunSearchFixtures

    @BeforeEach
    fun emptyTables() {
        RunSearchFixtures.emptyTables(dataSource)
        fixtures = RunSearchFixtures(runRepository, tokenRepository, datasetRepository)
        fixtures.dataset("dataset-a", "Alpha Sweep")
    }

    @Test
    fun `a loss filter finds every loss run even when they sit on a page the first page never reaches`() {
        (0 until TOTAL_RUNS).forEach { index ->
            when {
                index < 3 -> fixtures.run("loss-$index", at(index), pnl = "-1")
                index % 5 == 0 -> fixtures.run("other-$index", at(index), filled = 0)
                else -> fixtures.run("other-$index", at(index), pnl = if (index % 2 == 0) "1" else "0")
            }
        }
        // newest first, the three oldest runs are the last three of 45: the end of page 3 of 20
        assertEquals(listOf("loss-2", "loss-1", "loss-0"), runs.search(query(page = 2)).page.items.takeLast(3).map { it.id })

        val result = runs.search(query(statuses = setOf(RunStatus.LOSS)))

        assertEquals(setOf("loss-0", "loss-1", "loss-2"), result.page.items.map { it.id }.toSet())
        assertEquals(3L, result.page.total)
        assertEquals(3L, result.statusCounts.getValue(RunStatus.LOSS))
        assertTrue(result.page.items.all { it.status == RunStatus.LOSS })
    }

    @Test
    fun `a page without a snapshot of its own leaves out a run created two seconds ago, which may still be committing`() {
        val now = Instant.parse("2026-11-16T07:00:00Z")
        fixtures.run("settled", now.minusSeconds(10))
        fixtures.run("committing", now.minusSeconds(2))
        val console = RunSearchConsoleService(runs, RunVariationLabels(RunVariationLookup.ABSENT), StubOutcomeStores().outcomes(), Clock.fixed(now, ZoneOffset.UTC))

        val page = console.search(RunSearchParams(0, 20, null, emptyList(), null, null, null, null, null, null, null))

        assertEquals(listOf("settled"), page.result.page.items.map { it.id })
        assertEquals(now.minusSeconds(5), page.asOf)
    }

    @Test
    fun `the sql status and the kotlin status agree for every combination of totals`() {
        val combinations = listOf(
            Triple(0, 0, "0"), Triple(0, 2, "3"), Triple(0, 0, "-3"), Triple(2, 1, "3"), Triple(2, 1, "-3"),
            Triple(2, 0, "0.000000000001"), Triple(2, 0, "-0.000000000001"), Triple(2, 0, "0"),
        )
        combinations.forEachIndexed { index, (filled, open, pnl) ->
            fixtures.run("combo-$index", at(index), filled = filled, open = open, pnl = pnl)
        }

        RunStatus.entries.forEach { status ->
            val expected = combinations.indices
                .filter { RunStatus.of(combinations[it].first, combinations[it].second, BigDecimal(combinations[it].third)) == status }
                .map { "combo-$it" }.toSet()
            assertEquals(expected, runs.search(query(statuses = setOf(status))).page.items.map { it.id }.toSet(), status.name)
            assertEquals(expected.size.toLong(), runs.search(query()).statusCounts.getValue(status), "$status count")
        }
    }

    @Test
    fun `status counts apply every filter but the status, sum to the total and always have all five keys`() {
        fixtures.dataset("dataset-b", "Beta")
        (0 until 6).forEach { fixtures.run("a-profit-$it", at(it), pnl = "1") }
        (0 until 4).forEach { fixtures.run("a-loss-$it", at(10 + it), pnl = "-1") }
        (0 until 2).forEach { fixtures.run("b-loss-$it", at(20 + it), datasetId = "dataset-b", pnl = "-1") }

        val unfiltered = runs.search(query())
        val inDatasetA = runs.search(query(datasetId = "dataset-a", statuses = setOf(RunStatus.LOSS)))

        assertEquals(RunStatus.entries.toSet(), unfiltered.statusCounts.keys)
        assertEquals(12L, unfiltered.page.total)
        assertEquals(unfiltered.page.total, unfiltered.statusCounts.values.sum())
        assertEquals(
            mapOf(RunStatus.PROFIT to 6L, RunStatus.LOSS to 4L, RunStatus.FLAT to 0L, RunStatus.NO_ENTRY to 0L, RunStatus.OPEN to 0L),
            inDatasetA.statusCounts,
        )
        assertEquals(4L, inDatasetA.page.total)
    }

    @Test
    fun `totals sum the realized pnl of the query's statuses per quote mint, ordered by quote and exact at scale 12`() {
        fixtures.run("sol-profit-1", at(1), pnl = "1.000000000001")
        fixtures.run("sol-profit-2", at(2), pnl = "2")
        fixtures.run("sol-loss", at(3), pnl = "-0.5")
        fixtures.run("usdc-profit", at(4), pnl = "7", quote = USDC)
        fixtures.run("sol-no-entry", at(5), filled = 0)

        val profit = runs.search(query(statuses = setOf(RunStatus.PROFIT)))
        val everything = runs.search(query())

        assertEquals(
            listOf(RunQuoteTotal(USDC, 1, BigDecimal("7.000000000000")), RunQuoteTotal(MintAddress.WRAPPED_SOL.value, 2, BigDecimal("3.000000000001"))),
            profit.totals,
        )
        assertEquals(profit.page.total, profit.totals.sumOf { it.runs })
        assertEquals(12, profit.totals.first().realizedPnl.scale())
        assertEquals(everything.page.total, everything.totals.sumOf { it.runs })
        assertEquals(BigDecimal("2.500000000001"), everything.totals.single { it.quoteMint == MintAddress.WRAPPED_SOL.value }.realizedPnl)
    }

    @Test
    fun `totals follow every other filter, the snapshot included, and are empty when nothing matches`() {
        fixtures.dataset("dataset-b", "Beta")
        fixtures.run("a-early", at(1), pnl = "1")
        fixtures.run("b-early", at(2), datasetId = "dataset-b", pnl = "10")
        fixtures.run("a-late", at(50), pnl = "100")

        val inDatasetA = runs.search(query(datasetId = "dataset-a", asOf = at(10)))
        val none = runs.search(query(statuses = setOf(RunStatus.LOSS)))

        assertEquals(listOf(RunQuoteTotal(MintAddress.WRAPPED_SOL.value, 1, BigDecimal("1.000000000000"))), inDatasetA.totals)
        assertEquals(emptyList(), none.totals)
    }

    @Test
    fun `a size of zero returns no items and the same total, status counts and totals as any other size`() {
        (0 until 4).forEach { fixtures.run("profit-$it", at(it), pnl = "1.5") }
        (0 until 2).forEach { fixtures.run("loss-$it", at(10 + it), pnl = "-1", quote = USDC) }
        fixtures.run("none", at(20), filled = 0)

        listOf(emptySet(), setOf(RunStatus.PROFIT), setOf(RunStatus.LOSS, RunStatus.NO_ENTRY)).forEach { statuses ->
            val counted = runs.search(query(statuses = statuses, size = 0))
            val paged = runs.search(query(statuses = statuses, size = 20))

            assertEquals(emptyList(), counted.page.items)
            assertEquals(0, counted.page.size)
            assertEquals(paged.page.total, counted.page.total)
            assertEquals(paged.statusCounts, counted.statusCounts)
            assertEquals(paged.totals, counted.totals)
        }
    }

    @Test
    fun `for a fixed snapshot paging forward while runs are inserted never repeats or skips a run`() {
        val asOf = at(100)
        (0 until 23).forEach { fixtures.run("run-%02d".format(it), at(it)) }
        val seen = mutableListOf<String>()

        var page = 0
        while (true) {
            val result = runs.search(query(asOf = asOf, page = page, size = 5))
            seen += result.page.items.map { it.id }
            fixtures.run("after-$page", at(101 + page))
            if (result.page.items.size < 5) break
            page++
        }

        assertEquals((22 downTo 0).map { "run-%02d".format(it) }, seen)
        assertEquals(seen.size, seen.toSet().size)
        assertEquals(23L, runs.search(query(asOf = asOf)).page.total)
    }

    @Test
    fun `runs created at the same instant are ordered by id so no page boundary splits them unpredictably`() {
        (0 until 9).forEach { fixtures.run("tie-$it", at(0)) }

        val pages = (0 until 3).flatMap { runs.search(query(page = it, size = 3)).page.items.map { run -> run.id } }

        assertEquals((0 until 9).map { "tie-$it" }, pages)
    }

    @Test
    fun `a run created exactly at the snapshot is included and one a microsecond later is not`() {
        val asOf = Instant.parse("2026-11-10T00:00:00.000001Z")
        fixtures.run("at", asOf)
        fixtures.run("after", asOf.plus(Duration.ofNanos(1_000)))

        assertEquals(listOf("at"), runs.search(query(asOf = asOf)).page.items.map { it.id })
    }

    @Test
    fun `filtering by mint matches a run on any page`() {
        (0 until 30).forEach {
            fixtures.run("run-%02d".format(it), at(it), mints = if (it % 10 == 0) listOf(MINT_A, MINT_B) else listOf(MINT_B))
        }

        val result = runs.search(query(mint = MINT_A))

        assertEquals(setOf("run-00", "run-10", "run-20"), result.page.items.map { it.id }.toSet())
        assertEquals(3L, result.page.total)
        assertEquals(30L, runs.search(query(mint = MINT_B)).page.total)
        assertEquals(listOf(MINT_A, MINT_B).sortedBy { it.value }, result.page.items.first().mints)
    }

    @Test
    fun `anchor and created bounds are half open and a run without an anchor never matches an anchor bound`() {
        val from = Instant.parse("2026-11-10T07:00:00Z")
        val to = Instant.parse("2026-11-11T07:00:00Z")
        fixtures.run("before", at(0), anchor = from.minusNanos(1_000))
        fixtures.run("first", at(1), anchor = from)
        fixtures.run("last", at(2), anchor = to.minusNanos(1_000))
        fixtures.run("excluded", at(3), anchor = to)
        fixtures.run("none", at(4), anchor = null)

        assertEquals(setOf("first", "last"), runs.search(query(anchorFrom = from, anchorTo = to)).page.items.map { it.id }.toSet())
        assertEquals(setOf("first", "last", "excluded"), runs.search(query(anchorFrom = from)).page.items.map { it.id }.toSet())
        assertEquals(setOf("before", "first", "last"), runs.search(query(anchorTo = to)).page.items.map { it.id }.toSet())
        assertEquals(5L, runs.search(query()).page.total)
        assertEquals(setOf("before", "first"), runs.search(query(createdFrom = at(0), createdTo = at(2))).page.items.map { it.id }.toSet())
        assertEquals(Instant.parse("2026-11-10T07:00:00Z"), runs.find("first")?.anchorBlockTime)
    }

    @Test
    fun `text matches id, dataset id and configuration hash by prefix of four characters and dataset name by substring`() {
        fixtures.dataset("deadbeefcafe0000", "Gamma Sweep")
        fixtures.run("abcd1234", at(0), configurationHash = "99887766")
        fixtures.run("other", at(1), datasetId = "deadbeefcafe0000", configurationHash = "55443322")
        fixtures.run("third", at(2), configurationHash = "33221100")

        fun ids(text: String) = runs.search(query(text = text)).page.items.map { it.id }.toSet()

        assertEquals(setOf("abcd1234"), ids("abcd"))
        assertEquals(setOf("abcd1234"), ids("abcd1234"))
        assertEquals(emptySet(), ids("abc"))
        assertEquals(setOf("abcd1234"), ids("9988"))
        assertEquals(setOf("other"), ids("dead"))
        assertEquals(setOf("other"), ids("gamma"))
        assertEquals(setOf("abcd1234", "third"), ids("ALPHA SW"))
        assertEquals(emptySet(), ids("%"))
        assertEquals(emptySet(), ids("a_"))
    }

    @Test
    fun `with 150 datasets every run row carries its dataset name, on every page and from every read`() {
        (0 until 150).forEach { fixtures.dataset("ds-%03d".format(it), "Name %03d".format(it)) }
        (0 until 150).forEach { fixtures.run("run-%03d".format(it), at(it), datasetId = "ds-%03d".format(it)) }

        val searched = (0 until 2).flatMap { runs.search(query(page = it, size = 100)).page.items }.map { it.datasetName }
        val paged = (0 until 2).flatMap { runs.page(it, 100, null).items }.map { it.datasetName }

        assertEquals(150, searched.size)
        assertTrue(searched.none { it == null })
        assertEquals(searched.toSet(), paged.toSet())
        assertEquals("Name 149", runs.find("run-149")?.datasetName)
    }

    @Test
    fun `a run whose dataset left the catalog is listed with a null name`() {
        fixtures.run("orphan", at(0), datasetId = "gone")

        assertEquals(listOf(null), runs.search(query()).page.items.map { it.datasetName })
    }

    @Test
    fun `the page size is capped at 100 and the dataset filter narrows the search`() {
        fixtures.dataset("dataset-b", "Beta")
        (0 until 120).forEach { fixtures.run("run-%03d".format(it), at(it)) }
        fixtures.run("beta", at(500), datasetId = "dataset-b")

        assertEquals(100, runs.search(query(size = 500)).page.items.size)
        assertEquals(listOf("beta"), runs.search(query(datasetId = "dataset-b")).page.items.map { it.id })
    }

    @Test
    fun `a dataset search matches a name substring ignoring case or an id prefix`() {
        fixtures.dataset("feedface01", "Sweep Alpha")
        fixtures.dataset("cafebabe02", "Sweep Beta")

        fun ids(text: String) = catalog.search(text, 0, 20).items.map { it.id }.toSet()

        assertEquals(setOf("dataset-a", "feedface01", "cafebabe02"), ids("sweep"))
        assertEquals(setOf("cafebabe02"), ids("BETA"))
        assertEquals(setOf("feedface01"), ids("feedf"))
        assertEquals(emptySet(), ids("%"))
        assertEquals(3L, catalog.search("sweep", 0, 1).total)
        assertEquals(1, catalog.search("sweep", 0, 1).items.size)
    }

    private fun at(minute: Int): Instant = BASE.plus(Duration.ofMinutes(minute.toLong()))

    @Suppress("LongParameterList")
    private fun query(
        statuses: Set<RunStatus> = emptySet(), mint: MintAddress? = null, anchorFrom: Instant? = null, anchorTo: Instant? = null,
        createdFrom: Instant? = null, createdTo: Instant? = null, datasetId: String? = null, text: String? = null,
        asOf: Instant = FAR_FUTURE, page: Int = 0, size: Int = 20,
    ) = RunSearchQuery(statuses, mint, anchorFrom, anchorTo, createdFrom, createdTo, datasetId, text, asOf, page, size)

    private companion object {
        const val TOTAL_RUNS = 45
        const val USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        val BASE: Instant = Instant.parse("2026-11-10T00:00:00Z")
        val FAR_FUTURE: Instant = Instant.parse("2030-01-01T00:00:00Z")
    }
}
