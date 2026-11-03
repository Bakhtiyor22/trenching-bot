package com.trenching.bot.console.automation.infrastructure.persistence

import com.trenching.bot.console.automation.domain.BackfillState
import java.time.Instant
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AutomationBackfillRepository : JpaRepository<AutomationBackfillEntity, String> {
    fun findOneById(id: String): AutomationBackfillEntity?

    /** The wallet's backfill that holds its active slot, if any: a wallet has at most one. */
    fun findOneByActiveWallet(activeWallet: String): AutomationBackfillEntity?

    /** The backfills in [states], oldest first; the active states hold at most one per wallet, so a handful. */
    fun findAllByStateInOrderByCreatedAtAscIdAsc(states: Collection<BackfillState>): List<AutomationBackfillEntity>

    fun findAllByWalletOrderByCreatedAtDescIdDesc(wallet: String, pageable: Pageable): Page<AutomationBackfillEntity>

    /**
     * The wallet's capturing backfills whose range meets [from, to) and that block a new one (plan Rule 35, amended 2026-11-03):
     * an active backfill and a `DONE` unfiltered one. A `STOPPED_BUDGET` or `CANCELLED` backfill does not, nor does a filtered one:
     * a new backfill may read their range again and take over their items that produced nothing.
     */
    @Query("""
        select b from AutomationBackfillEntity b
        where b.wallet = :wallet and b.dryRun = false and b.rangeStart < :to and b.rangeEnd > :from
          and (b.state in (com.trenching.bot.console.automation.domain.BackfillState.QUEUED,
              com.trenching.bot.console.automation.domain.BackfillState.SCANNING,
              com.trenching.bot.console.automation.domain.BackfillState.CAPTURING)
            or (b.state = com.trenching.bot.console.automation.domain.BackfillState.DONE and b.filtered = false))
        order by b.createdAt, b.id
    """)
    fun findCapturingOverlapping(
        @Param("wallet") wallet: String,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
        pageable: Pageable,
    ): List<AutomationBackfillEntity>

    /**
     * The ranges the wallet's ended unfiltered capturing backfills discovered (plan Rule 32, amended 2026-11-03): from where the scan reached
     * (the range start for a scan that finished) to the range end, whether the backfill is done, stopped by its budget or
     * cancelled. A `DONE` row stored without a stop point counts from its range start. A backfill still running counts nothing.
     * Oldest first, one page at a time.
     */
    @Query("""
        select coalesce(b.scannedTo, b.rangeStart) as rangeStart, b.rangeEnd as rangeEnd from AutomationBackfillEntity b
        where b.wallet = :wallet and b.dryRun = false and b.filtered = false
          and (b.state = com.trenching.bot.console.automation.domain.BackfillState.DONE
            or (b.state in (com.trenching.bot.console.automation.domain.BackfillState.STOPPED_BUDGET,
                com.trenching.bot.console.automation.domain.BackfillState.CANCELLED) and b.scannedTo is not null))
          and coalesce(b.scannedTo, b.rangeStart) < b.rangeEnd
        order by coalesce(b.scannedTo, b.rangeStart), b.id
    """)
    fun findDiscoveredRanges(@Param("wallet") wallet: String, pageable: Pageable): List<AutomationBackfillRangeRow>
}
