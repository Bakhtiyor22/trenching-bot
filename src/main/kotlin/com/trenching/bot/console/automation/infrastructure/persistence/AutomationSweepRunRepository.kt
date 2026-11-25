package com.trenching.bot.console.automation.infrastructure.persistence

import java.time.Instant
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * The sweep run list filter: each given value must match; the mint and block time are the replayed buy's, [from] inclusive and
 * [to] exclusive. A buy without a block time fails a given bound, as a null compares unknown.
 */
private const val SWEEP_RUN_FILTER = """
    from AutomationSweepRunEntity r join AutomationItemEntity i on i.id = r.itemId
    where (:sessionId is null or r.sessionId = :sessionId) and (:mint is null or i.mint = :mint)
      and (:label is null or r.label = :label) and (:outcome is null or r.outcome = :outcome)
      and (:planId is null or r.planId = :planId)
      and (cast(:from as Instant) is null or i.blockTime >= :from) and (cast(:to as Instant) is null or i.blockTime < :to)
"""

/**
 * The status of a sweep row, as [com.trenching.bot.replay.domain.RunStatus.of] gives it for the one-token run the row points at:
 * no position is `NO_ENTRY`, an open one `OPEN`, a closed one `PROFIT`, `LOSS` or `FLAT` by the sign of its realized P&L. A test
 * holds the two together.
 */
private const val SWEEP_RUN_STATUS = """
    case when r.positionState is null then 'NO_ENTRY'
         when r.positionState = com.trenching.bot.portfolio.domain.PositionState.OPEN then 'OPEN'
         when r.realizedPnl > 0 then 'PROFIT' when r.realizedPnl < 0 then 'LOSS' else 'FLAT' end
"""

interface AutomationSweepRunRepository : JpaRepository<AutomationSweepRunEntity, String> {
    fun findAllByItemIdOrderByPlanIdAscLabelAsc(itemId: String, pageable: Pageable): List<AutomationSweepRunEntity>

    /** How many sweep rows an item has, a row for each entry of its plan, and how many of them have a run; one aggregate over at most the plan's entries. */
    @Query("select count(r) as entries, count(r.runId) as ran from AutomationSweepRunEntity r where r.itemId = :itemId")
    fun tally(@Param("itemId") itemId: String): AutomationSweepTallyRow

    /** The labels an item already has a row for under a plan; bounded by the plan's entries. */
    @Query("select r.label from AutomationSweepRunEntity r where r.itemId = :itemId and r.planId = :planId")
    fun findLabels(@Param("itemId") itemId: String, @Param("planId") planId: String): List<String>

    /** The label of each entry that got one of [runIds] under [planId]; a refused entry has no run and never matches. At most one row per run, session and entry. */
    @Query("select r.runId as runId, r.label as label from AutomationSweepRunEntity r where r.runId in :runIds and r.planId = :planId")
    fun findRunLabels(@Param("runIds") runIds: Collection<String>, @Param("planId") planId: String): List<AutomationRunLabelRow>

    /** The sweep runs of the sessions of a span of trading days under a plan; bounded by the span's items and the plan's entries. */
    fun findAllBySessionIdInAndPlanId(sessionIds: Collection<String>, planId: String): List<AutomationSweepRunEntity>

    /** Moves every sweep run of session [from] to [into]; entities already loaded are not refreshed. */
    @Modifying(flushAutomatically = true)
    @Query("update AutomationSweepRunEntity r set r.sessionId = :into where r.sessionId = :from")
    fun moveSession(@Param("from") from: String, @Param("into") into: String): Int

    /** Moves the sweep runs of the items [itemIds] to session [into]; entities already loaded are not refreshed. */
    @Modifying(flushAutomatically = true)
    @Query("update AutomationSweepRunEntity r set r.sessionId = :into where r.itemId in :itemIds")
    fun moveItems(@Param("itemIds") itemIds: Collection<String>, @Param("into") into: String): Int

    /** Newest buy first, then by plan and label, with no count: [statusAggregates] has the total. [statuses] are [com.trenching.bot.replay.domain.RunStatus] names, all of them for no status filter. */
    @Query("select r $SWEEP_RUN_FILTER and $SWEEP_RUN_STATUS in :statuses order by i.blockTime desc nulls last, i.id, r.planId, r.label")
    fun page(
        @Param("sessionId") sessionId: String?,
        @Param("mint") mint: String?,
        @Param("label") label: String?,
        @Param("outcome") outcome: String?,
        @Param("planId") planId: String?,
        @Param("statuses") statuses: Collection<String>,
        @Param("from") from: Instant?,
        @Param("to") to: Instant?,
        pageable: Pageable,
    ): List<AutomationSweepRunEntity>

    /** The rows and summed P&L of each status that has any row, under every filter of [page] but the status; at most five rows. */
    @Query("select $SWEEP_RUN_STATUS as status, count(r) as runs, sum(r.realizedPnl) as realizedPnl, sum(r.worstCasePnl) as worstCasePnl $SWEEP_RUN_FILTER group by $SWEEP_RUN_STATUS")
    fun statusAggregates(
        @Param("sessionId") sessionId: String?,
        @Param("mint") mint: String?,
        @Param("label") label: String?,
        @Param("outcome") outcome: String?,
        @Param("planId") planId: String?,
        @Param("from") from: Instant?,
        @Param("to") to: Instant?,
    ): List<AutomationSweepStatusRow>
}
