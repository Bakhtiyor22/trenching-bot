package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationLimits
import com.trenching.bot.console.automation.domain.BackfillState
import com.trenching.bot.console.automation.domain.CoverageRanges
import com.trenching.bot.console.automation.domain.TimeRange
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationBackfillEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationBackfillRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationWatermarkRepository
import com.trenching.bot.console.automation.mapper.AutomationRecordMapper
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Where a wallet's buys have been discovered without gaps (plan Rule 32). Live discovery covers `covered_from` up to the
 * instant its last complete scan reached ([com.trenching.bot.console.automation.domain.DiscoveryWatermark.discoveredThrough]);
 * nothing is covered until a first catch-up completes. An ended unfiltered capturing backfill covers what its scan read, from where
 * it reached (its range start once it finished) to its range end, which merges with the live range where they touch; also one
 * stopped by its budget or cancelled. A filtered backfill covers nothing. Two different things are kept apart: coverage, the union
 * of these ranges, which says whether a day was discovered; and `covered_from`, the live floor a new backfill is clipped to, which
 * only a done unfiltered backfill moves. This is the one place that knows the sources of coverage.
 */
@Service
@ConditionalOnAutomationConsole
class AutomationCoverage(
    private val watermarks: AutomationWatermarkRepository,
    private val backfills: AutomationBackfillRepository,
    private val mapper: AutomationRecordMapper,
) {
    private val log = LoggerFactory.getLogger(AutomationCoverage::class.java)

    @Transactional(readOnly = true)
    fun of(wallet: String): CoverageRanges = CoverageRanges(listOfNotNull(live(wallet)) + endedBackfills(wallet))

    /**
     * Moves the wallet's live floor, `covered_from`, back to the start of the `DONE` unfiltered capturing [backfill] when its range
     * meets it (plan Key Decision 6, amended 2026-11-03). `covered_from` is where coverage is contiguous from the live start, and
     * it is the bound a new capturing backfill is clipped to, so only a backfill that read its whole range and found every buy
     * the capture filter takes may move it. A stopped, cancelled or filtered backfill never does; a stopped or cancelled one
     * still counts through [of], and a new backfill may read its range again. Runs inside the caller's transaction, which ends the
     * backfill.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun extend(backfill: AutomationBackfillEntity) {
        if (backfill.state != BackfillState.DONE || backfill.filtered || backfill.dryRun) return
        val reached = backfill.scannedTo ?: backfill.rangeStart
        val watermark = watermarks.findOneByWallet(backfill.wallet) ?: return
        val coveredFrom = watermark.coveredFrom ?: return
        if (backfill.rangeEnd.isBefore(coveredFrom) || !reached.isBefore(coveredFrom)) return
        watermark.coveredFrom = reached
        log.info("automation coverage extended wallet={} backfillId={} coveredFrom={} previousCoveredFrom={}", backfill.wallet, backfill.id, reached, coveredFrom)
    }

    /** The ranges the wallet's ended capturing backfills read (plan Rule 32). */
    private fun endedBackfills(wallet: String): List<TimeRange> =
        backfills.findDiscoveredRanges(wallet, PageRequest.of(0, AutomationLimits.MAX_COVERING_BACKFILLS)).map { TimeRange(it.rangeStart, it.rangeEnd) }

    private fun live(wallet: String): TimeRange? {
        val watermark = watermarks.findOneByWallet(wallet)?.let(mapper::toDiscoveryWatermark) ?: return null
        val from = watermark.coveredFrom ?: return null
        val through = watermark.discoveredThrough ?: return null
        return if (through.isBefore(from)) null else TimeRange(from, through)
    }
}
