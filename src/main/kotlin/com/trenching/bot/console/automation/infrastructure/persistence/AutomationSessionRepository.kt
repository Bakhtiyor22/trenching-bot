package com.trenching.bot.console.automation.infrastructure.persistence

import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.EvaluationStatus
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
 * The sessions page filter: a first buy in [from, to) when given; with [status] a best evaluation of that status under the
 * plan, meaning one with it and none with a status in `better`; with `issues` set, sessions that have capture issues (true) or
 * none (false). The casts type the time and flag parameters, since PostgreSQL cannot infer the type of a bare null in `? is null`.
 */
private const val PAGE_FILTER = """
    from AutomationSessionEntity s
    where (cast(:from as Instant) is null or s.firstBuyAt >= :from) and (cast(:to as Instant) is null or s.firstBuyAt < :to)
      and (:status is null or (
        exists (select 1 from AutomationEvaluationEntity e where e.sessionId = s.id and e.planId = :planId and e.status = :status)
        and not exists (select 1 from AutomationEvaluationEntity b where b.sessionId = s.id and b.planId = :planId and b.status in :better)))
      and (cast(:issues as Boolean) is null or (:issues = true and $CAPTURE_ISSUES) or (:issues = false and not $CAPTURE_ISSUES))
"""

/**
 * A closed session evaluable under the plan (Rule 24, clarified 2026-11-03): its items have sweep rows under that plan, so a
 * phase-0 session or one swept under another plan is never evaluated under it; and every earlier session of its wallet, by first
 * buy then id, is closed, so its window is complete.
 */
private const val EVALUABLE = """
    s.state = com.trenching.bot.console.automation.domain.AutomationSessionState.CLOSED
    and exists (select 1 from AutomationSweepRunEntity r where r.sessionId = s.id and r.planId = :planId)
    and not exists (select 1 from AutomationSessionEntity p where p.wallet = s.wallet
      and p.state <> com.trenching.bot.console.automation.domain.AutomationSessionState.CLOSED
      and (p.firstBuyAt < s.firstBuyAt or p.firstBuyAt = s.firstBuyAt and p.id < s.id))
"""

interface AutomationSessionRepository : JpaRepository<AutomationSessionEntity, String> {
    fun findOneById(id: String): AutomationSessionEntity?

    fun findFirstByWalletAndStateOrderByLastBuyAtDesc(wallet: String, state: AutomationSessionState): AutomationSessionEntity?

    /**
     * The wallet's sessions in [state] in the order they were discovered, sessions of one scan by first buy, then by id; used for
     * `OPEN`, of which a wallet has a handful.
     */
    fun findAllByWalletAndStateOrderByCreatedAtAscFirstBuyAtAscIdAsc(wallet: String, state: AutomationSessionState): List<AutomationSessionEntity>

    @Query("""
        select s.id from AutomationSessionEntity s where s.wallet = :wallet and s.state = :state and s.lastBuyAt < :before
        order by s.lastBuyAt, s.id
    """)
    fun findIdsByWalletAndStateAndLastBuyAtBefore(
        @Param("wallet") wallet: String,
        @Param("state") state: AutomationSessionState,
        @Param("before") before: Instant,
        pageable: Pageable,
    ): List<String>

    /**
     * The wallet's sessions in [state] whose last buy is before [before] and, when [coveredBefore] is given, not before it: the
     * open sessions a scan now could show idle that no complete scan has yet.
     */
    @Query("""
        select count(s) from AutomationSessionEntity s where s.wallet = :wallet and s.state = :state and s.lastBuyAt < :before
          and (cast(:coveredBefore as Instant) is null or s.lastBuyAt >= :coveredBefore)
    """)
    fun countWithLastBuyIn(
        @Param("wallet") wallet: String,
        @Param("state") state: AutomationSessionState,
        @Param("coveredBefore") coveredBefore: Instant?,
        @Param("before") before: Instant,
    ): Long

    @Query("select s.id from AutomationSessionEntity s where s.state = :state order by s.lastBuyAt, s.id")
    fun findIdsByState(@Param("state") state: AutomationSessionState, pageable: Pageable): List<String>

    /** Evaluable sessions with no evaluation under the plan yet, oldest first buy first, so a window finds its earlier sessions scored. */
    @Query("""
        select s.id from AutomationSessionEntity s where $EVALUABLE
          and not exists (select 1 from AutomationEvaluationEntity e where e.sessionId = s.id and e.planId = :planId)
        order by s.firstBuyAt, s.id
    """)
    fun findIdsToEvaluate(@Param("planId") planId: String, pageable: Pageable): List<String>

    @Query("select count(s) from AutomationSessionEntity s where s.id = :id and $EVALUABLE")
    fun countEvaluable(@Param("id") id: String, @Param("planId") planId: String): Long

    /** The wallet's closed sessions before [firstBuyAt] (then [id]) that have evaluations under the plan, newest first. */
    @Query("""
        select s from AutomationSessionEntity s
        where s.wallet = :wallet and s.state = com.trenching.bot.console.automation.domain.AutomationSessionState.CLOSED
          and (s.firstBuyAt < :firstBuyAt or s.firstBuyAt = :firstBuyAt and s.id < :id)
          and exists (select 1 from AutomationEvaluationEntity e where e.sessionId = s.id and e.planId = :planId)
        order by s.firstBuyAt desc, s.id desc
    """)
    fun findEvaluatedBefore(
        @Param("wallet") wallet: String,
        @Param("planId") planId: String,
        @Param("firstBuyAt") firstBuyAt: Instant,
        @Param("id") id: String,
        pageable: Pageable,
    ): List<AutomationSessionEntity>

    @Query("select s $PAGE_FILTER order by s.createdAt desc, s.id", countQuery = "select count(s) $PAGE_FILTER")
    fun pageNewestFirst(
        @Param("from") from: Instant?,
        @Param("to") to: Instant?,
        @Param("planId") planId: String,
        @Param("status") status: EvaluationStatus?,
        @Param("better") better: Collection<EvaluationStatus>,
        @Param("issues") issues: Boolean?,
        @Param("issueStates") issueStates: Collection<AutomationItemState>,
        pageable: Pageable,
    ): Page<AutomationSessionEntity>

    /**
     * The best evaluation under the plan first: its status rank, then its worst-case P&L descending; sessions with no evaluation
     * last; then newest first.
     */
    @Query("""
        select s $PAGE_FILTER
        order by coalesce((select min($RANK_OF_E) from AutomationEvaluationEntity e where e.sessionId = s.id and e.planId = :planId), $UNRANKED),
          (select max(b.worstCasePnl) from AutomationEvaluationEntity b where b.sessionId = s.id and b.planId = :planId
            and $RANK_OF_B = (select min($RANK_OF_E) from AutomationEvaluationEntity e where e.sessionId = s.id and e.planId = :planId)) desc nulls last,
          s.createdAt desc, s.id
    """, countQuery = "select count(s) $PAGE_FILTER")
    fun pageByBestEvaluation(
        @Param("from") from: Instant?,
        @Param("to") to: Instant?,
        @Param("planId") planId: String,
        @Param("status") status: EvaluationStatus?,
        @Param("better") better: Collection<EvaluationStatus>,
        @Param("issues") issues: Boolean?,
        @Param("issueStates") issueStates: Collection<AutomationItemState>,
        pageable: Pageable,
    ): Page<AutomationSessionEntity>
}
