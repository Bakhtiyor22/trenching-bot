package com.trenching.bot.console.automation.infrastructure.persistence

import com.trenching.bot.console.automation.domain.EvaluationStatus
import java.time.Instant
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * The scoreboard filter: each given value must match, and [from, to) bounds the evaluated session's first buy. The casts type
 * the time parameters, since PostgreSQL cannot infer the type of a bare null in `? is null`.
 */
private const val SCOREBOARD_FILTER = """
    from AutomationEvaluationEntity e
    where (:planId is null or e.planId = :planId) and (:label is null or e.label = :label) and (:status is null or e.status = :status)
      and exists (select 1 from AutomationSessionEntity s where s.id = e.sessionId
        and (cast(:from as Instant) is null or s.firstBuyAt >= :from) and (cast(:to as Instant) is null or s.firstBuyAt < :to))
"""

interface AutomationEvaluationRepository : JpaRepository<AutomationEvaluationEntity, String> {
    /** Bounded by the page of sessions and the plan's entries. */
    fun findAllBySessionIdInAndPlanId(sessionIds: Collection<String>, planId: String): List<AutomationEvaluationScoreRow>

    /** One session's evaluations under a plan, one per entry. */
    fun findAllBySessionIdAndPlanIdOrderByLabelAsc(sessionId: String, planId: String): List<AutomationEvaluationEntity>

    /** Best status first (SUCCESS, NO_SUCCESS, NO_RESULT), then the highest worst-case P&L, then the newest. */
    @Query("select e $SCOREBOARD_FILTER order by $RANK_OF_E, e.worstCasePnl desc, e.evaluatedAt desc, e.label, e.id",
        countQuery = "select count(e) $SCOREBOARD_FILTER")
    fun pageByStatus(
        @Param("planId") planId: String?,
        @Param("label") label: String?,
        @Param("status") status: EvaluationStatus?,
        @Param("from") from: Instant?,
        @Param("to") to: Instant?,
        pageable: Pageable,
    ): Page<AutomationEvaluationEntity>

    @Query("select e $SCOREBOARD_FILTER order by e.evaluatedAt desc, e.label, e.id", countQuery = "select count(e) $SCOREBOARD_FILTER")
    fun pageNewestFirst(
        @Param("planId") planId: String?,
        @Param("label") label: String?,
        @Param("status") status: EvaluationStatus?,
        @Param("from") from: Instant?,
        @Param("to") to: Instant?,
        pageable: Pageable,
    ): Page<AutomationEvaluationEntity>
}
