package com.trenching.bot.console.automation

import com.trenching.bot.console.application.RunOutcomes
import com.trenching.bot.console.application.RunSearchConsoleService
import com.trenching.bot.console.application.RunVariationLabels
import com.trenching.bot.console.automation.AutomationFixtures.EVENING
import com.trenching.bot.console.automation.AutomationRows.item
import com.trenching.bot.console.automation.AutomationRows.sweepRun
import com.trenching.bot.console.automation.application.AutomationRunVariationLookup
import com.trenching.bot.console.automation.application.AutomationVariationViews
import com.trenching.bot.console.domain.InvalidConsoleRequestException
import com.trenching.bot.console.domain.RunSearchPage
import com.trenching.bot.console.domain.RunSearchParams
import com.trenching.bot.replay.SimulationRuns
import com.trenching.bot.replay.infrastructure.persistence.ReplayDatasetRepository
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunRepository
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunTokenRepository
import com.trenching.bot.support.RunSearchFixtures
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * `GET /api/runs?variation=` from stored sweep rows to the listed runs, through the real lookup and the real run search: the runs a variation's
 * sweep rows carry the configuration hashes of are listed, with any other run of those hashes, and another variation's run is not.
 */
class RunVariationFilterEndToEndTest : AutomationDatabaseTest() {
    @Autowired private lateinit var runs: SimulationRuns
    @Autowired private lateinit var outcomes: RunOutcomes
    @Autowired private lateinit var runRepository: SimulationRunRepository
    @Autowired private lateinit var tokenRepository: SimulationRunTokenRepository
    @Autowired private lateinit var datasetRepository: ReplayDatasetRepository

    private val runIds = listOf("e2e-v03", "e2e-v04", "e2e-manual", "e2e-other")
    private val createdAt = Instant.parse("2026-11-26T07:00:00Z")

    @BeforeEach
    fun storeRuns() {
        removeRuns()
        val fixtures = RunSearchFixtures(runRepository, tokenRepository, datasetRepository)
        listOf("e2e-v03" to "hash-v03", "e2e-v04" to "hash-v04", "e2e-manual" to "hash-v03", "e2e-other" to "hash-elsewhere").forEachIndexed { index, (id, hash) ->
            fixtures.dataset("dataset-$id")
            fixtures.run(id, createdAt.plusSeconds(index.toLong()), datasetId = "dataset-$id", configurationHash = hash)
        }
        items.save(item(1, "s1", blockTime = EVENING))
        sweepRuns.saveAll(listOf(
            sweepRun("e2e-r1", "item-1", "V03", runId = "e2e-v03").apply { configurationHash = "hash-v03" },
            sweepRun("e2e-r2", "item-1", "V04", runId = "e2e-v04").apply { configurationHash = "hash-v04" },
        ))
    }

    @AfterEach
    fun removeRuns() {
        runRepository.deleteAllById(runIds)
        datasetRepository.deleteAllById(runIds.map { "dataset-$it" })
    }

    @Test
    fun `a variation lists the runs its sweep rows carry the hashes of, with a manual run of the same settings, and not another variation's run`() {
        val page = search("V03")

        assertEquals(listOf("e2e-manual", "e2e-v03"), ids(page))
        assertEquals(2L, page.result.page.total)
        assertEquals(mapOf("e2e-manual" to emptyList(), "e2e-v03" to listOf("V03")), page.variations)
    }

    @Test
    fun `another variation lists only its own run`() {
        val page = search("V04")

        assertEquals(listOf("e2e-v04"), ids(page))
        assertEquals(1L, page.result.page.total)
        assertEquals(mapOf("e2e-v04" to listOf("V04")), page.variations)
    }

    @Test
    fun `a variation that has not run lists nothing, and an unknown label is refused`() {
        val page = search("V05")

        assertEquals(emptyList(), ids(page))
        assertEquals(0L, page.result.page.total)
        assertEquals("variation", assertFailsWith<InvalidConsoleRequestException> { search("V99") }.field)
    }

    private fun search(variation: String): RunSearchPage = RunSearchConsoleService(
        runs,
        RunVariationLabels(AutomationRunVariationLookup(sweepRuns, variationRows, AutomationVariationViews(variationRows, variationMapper), AutomationFixtures.properties())),
        outcomes,
        Clock.fixed(createdAt.plusSeconds(60), ZoneOffset.UTC),
    ).search(RunSearchParams(0, 20, null, emptyList(), null, null, null, null, null, null, null, variation))

    private fun ids(page: RunSearchPage) = page.result.page.items.map { it.id }
}
