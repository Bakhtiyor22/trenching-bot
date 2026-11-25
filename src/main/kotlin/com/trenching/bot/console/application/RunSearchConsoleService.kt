package com.trenching.bot.console.application

import com.trenching.bot.common.MintAddress
import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.domain.InvalidConsoleRequestException
import com.trenching.bot.console.domain.RunSearchPage
import com.trenching.bot.console.domain.RunSearchParams
import com.trenching.bot.replay.SimulationRuns
import com.trenching.bot.replay.domain.RunSearchQuery
import com.trenching.bot.replay.domain.RunStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/** Validates the run search parameters and fixes the snapshot; the filtering itself belongs to `SimulationRuns.search`. */
@Service
class RunSearchConsoleService(
    private val runs: SimulationRuns,
    private val variations: RunVariationLabels,
    private val outcomes: RunOutcomes,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
) {
    fun search(params: RunSearchParams): RunSearchPage {
        requirePaging(params.page, params.size)
        requireOrdered("anchorFrom", params.anchorFrom, "anchorTo", params.anchorTo)
        requireOrdered("createdFrom", params.createdFrom, "createdTo", params.createdTo)
        val asOf = snapshot(params.asOf)
        val query = RunSearchQuery(
            statuses = params.statuses.map(::status).toSet(),
            mint = params.mint?.takeIf { it.isNotBlank() }?.let(::mint),
            anchorFrom = params.anchorFrom,
            anchorTo = params.anchorTo,
            createdFrom = params.createdFrom,
            createdTo = params.createdTo,
            datasetId = params.datasetId?.takeIf { it.isNotBlank() },
            text = params.text?.let(ConsoleSearchText::validated),
            asOf = asOf,
            page = params.page,
            size = params.size,
        )
        val result = runs.search(query)
        val runIds = result.page.items.map { it.id }
        return RunSearchPage(result, asOf, variations.of(runIds), outcomes.of(runIds))
    }

    /**
     * A run stamps its `created_at` before it commits, so a snapshot taken at the very moment could miss a run that then appears
     * with an earlier time and shift a later page. A snapshot is therefore never later than [SNAPSHOT_SETTLE_TIME] before now: the
     * default, and a later time a client asks for, which the response returns as used.
     */
    private fun snapshot(requested: Instant?): Instant {
        val latest = clock.instant().truncatedTo(ChronoUnit.MICROS).minus(SNAPSHOT_SETTLE_TIME)
        return if (requested == null || requested.isAfter(latest)) latest else requested
    }

    /** This endpoint alone accepts a size of 0, which asks for the counts and totals without a page. */
    private fun requirePaging(page: Int, size: Int) {
        if (size == 0 && page >= 0) return
        ConsolePaging.require(page, size)
    }

    private fun status(value: String): RunStatus =
        RunStatus.entries.firstOrNull { it.name == value }
            ?: throw InvalidConsoleRequestException("status", "must be one of ${RunStatus.entries.joinToString()}")

    private fun mint(value: String): MintAddress =
        ConsoleAddresses.mintOrNull(value) ?: throw InvalidConsoleRequestException("mint", "must be a 32-byte base58 address")

    private fun requireOrdered(fromName: String, from: Instant?, toName: String, to: Instant?) {
        if (from != null && to != null && !from.isBefore(to)) throw InvalidConsoleRequestException(toName, "must be after $fromName")
    }

    companion object {
        /** How long a run may take between stamping its creation time and committing, with a margin. */
        val SNAPSHOT_SETTLE_TIME: Duration = Duration.ofSeconds(5)
    }
}
