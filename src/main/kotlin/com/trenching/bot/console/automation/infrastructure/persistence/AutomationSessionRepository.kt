package com.trenching.bot.console.automation.infrastructure.persistence

import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionState
import java.time.Instant
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** A session has capture issues when an item is in one of `issueStates` or a sweep entry under the plan was refused (no run). */
private const val CAPTURE_ISSUES = """
    (exists (select 1 from AutomationItemEntity i where i.sessionId = s.id and i.state in :issueStates)
      or exists (select 1 from AutomationSweepRunEntity r where r.sessionId = s.id and r.planId = :planId and r.runId is null))
"""

/**
 * The sessions page filter: a first buy in [from, to) when given; with `issues` set, sessions that have capture issues (true) or
 * none (false). The casts type the time and flag parameters, since PostgreSQL cannot infer the type of a bare null in `? is null`.
 */
private const val PAGE_FILTER = """
    from AutomationSessionEntity s
    where (cast(:from as Instant) is null or s.firstBuyAt >= :from) and (cast(:to as Instant) is null or s.firstBuyAt < :to)
      and (cast(:issues as Boolean) is null or (:issues = true and $CAPTURE_ISSUES) or (:issues = false and not $CAPTURE_ISSUES))
"""

interface AutomationSessionRepository : JpaRepository<AutomationSessionEntity, String> {
    fun findOneById(id: String): AutomationSessionEntity?

    /** The newest session of live discovery in [state]; a backfill's sessions are not the wallet's current ones. */
    fun findFirstByWalletAndBackfillIdIsNullAndStateOrderByLastBuyAtDesc(wallet: String, state: AutomationSessionState): AutomationSessionEntity?

    /**
     * The wallet's live sessions in [state] in the order they were discovered, sessions of one scan by first buy, then by id; used
     * for `OPEN`, of which a wallet has a handful. A backfill's sessions are not among them: its buys never join a live session.
     */
    fun findAllByWalletAndBackfillIdIsNullAndStateOrderByCreatedAtAscFirstBuyAtAscIdAsc(wallet: String, state: AutomationSessionState): List<AutomationSessionEntity>

    /** The backfill's sessions in [state] in the order they were discovered, as above; used for `OPEN`, of which a backfill has a handful. */
    fun findAllByBackfillIdAndStateOrderByCreatedAtAscFirstBuyAtAscIdAsc(backfillId: String, state: AutomationSessionState): List<AutomationSessionEntity>

    /**
     * The ids of the backfill's sessions a cancel still has to deal with, one page at a time: those not [closed], and those closed
     * that still hold an item not in [terminal] (the phase 0 backlog), whose items the cancel abandons.
     */
    @Query("""
        select s.id from AutomationSessionEntity s
        where s.backfillId = :backfillId
          and (s.state <> :closed or exists (select 1 from AutomationItemEntity i where i.sessionId = s.id and i.state not in :terminal))
        order by s.firstBuyAt, s.id
    """)
    fun findIdsToCancel(
        @Param("backfillId") backfillId: String,
        @Param("closed") closed: AutomationSessionState,
        @Param("terminal") terminal: Collection<AutomationItemState>,
        pageable: Pageable,
    ): List<String>

    @Query("""
        select s.id from AutomationSessionEntity s where s.wallet = :wallet and s.backfillId is null and s.state = :state and s.lastBuyAt < :before
        order by s.lastBuyAt, s.id
    """)
    fun findIdsByWalletAndStateAndLastBuyAtBefore(
        @Param("wallet") wallet: String,
        @Param("state") state: AutomationSessionState,
        @Param("before") before: Instant,
        pageable: Pageable,
    ): List<String>

    /**
     * The wallet's live sessions in [state] whose last buy is before [before] and, when [coveredBefore] is given, not before it: the
     * open sessions a scan now could show idle that no complete scan has yet.
     */
    @Query("""
        select count(s) from AutomationSessionEntity s where s.wallet = :wallet and s.backfillId is null and s.state = :state and s.lastBuyAt < :before
          and (cast(:coveredBefore as Instant) is null or s.lastBuyAt >= :coveredBefore)
    """)
    fun countWithLastBuyIn(
        @Param("wallet") wallet: String,
        @Param("state") state: AutomationSessionState,
        @Param("coveredBefore") coveredBefore: Instant?,
        @Param("before") before: Instant,
    ): Long

    /** When the wallet's first session was created, null when it has none: the nearest stored sign of when discovery began. */
    @Query("select min(s.createdAt) from AutomationSessionEntity s where s.wallet = :wallet")
    fun findEarliestCreatedAt(@Param("wallet") wallet: String): Instant?

    /**
     * The sessions in [state] whose every item is in one of [settled], oldest last buy first, one page at a time: the closing
     * sessions that can close now. Sessions still waiting for an item are not returned, so a long backfill's waiting sessions
     * never crowd the ones that can close out of the page.
     */
    @Query("""
        select s.id from AutomationSessionEntity s
        where s.state = :state
          and not exists (select 1 from AutomationItemEntity i where i.sessionId = s.id and i.state not in :settled)
        order by s.lastBuyAt, s.id
    """)
    fun findIdsByStateSettledIn(@Param("state") state: AutomationSessionState, @Param("settled") settled: Collection<AutomationItemState>, pageable: Pageable): List<String>

    /**
     * The wallet's sessions whose first buy is in [from, to), oldest first: the sessions of a span of trading days. Callers bound
     * the span, which bounds the rows; none is cut off, since a verdict must never rest on a truncated day.
     */
    @Query("""
        select s from AutomationSessionEntity s where s.wallet = :wallet and s.firstBuyAt >= :from and s.firstBuyAt < :to
        order by s.firstBuyAt, s.id
    """)
    fun findStartedIn(@Param("wallet") wallet: String, @Param("from") from: Instant, @Param("to") to: Instant): List<AutomationSessionEntity>

    @Query("select s $PAGE_FILTER order by s.createdAt desc, s.id", countQuery = "select count(s) $PAGE_FILTER")
    fun pageNewestFirst(
        @Param("from") from: Instant?,
        @Param("to") to: Instant?,
        @Param("planId") planId: String,
        @Param("issues") issues: Boolean?,
        @Param("issueStates") issueStates: Collection<AutomationItemState>,
        pageable: Pageable,
    ): Page<AutomationSessionEntity>
}
