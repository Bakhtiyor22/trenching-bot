package com.trenching.bot.console.automation.infrastructure.persistence

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** The sweep run list filter: each given value must match; the mint is the replayed buy's. */
private const val SWEEP_RUN_FILTER = """
    from AutomationSweepRunEntity r join AutomationItemEntity i on i.id = r.itemId
    where (:sessionId is null or r.sessionId = :sessionId) and (:mint is null or i.mint = :mint)
      and (:label is null or r.label = :label) and (:outcome is null or r.outcome = :outcome)
"""

interface AutomationSweepRunRepository : JpaRepository<AutomationSweepRunEntity, String> {
    fun findAllByItemIdOrderByPlanIdAscLabelAsc(itemId: String, pageable: Pageable): List<AutomationSweepRunEntity>

    /** The labels an item already has a row for under a plan; bounded by the plan's entries. */
    @Query("select r.label from AutomationSweepRunEntity r where r.itemId = :itemId and r.planId = :planId")
    fun findLabels(@Param("itemId") itemId: String, @Param("planId") planId: String): List<String>

    /** The sweep runs of an evaluation window's sessions under a plan; bounded by the window's items and the plan's entries. */
    fun findAllBySessionIdInAndPlanId(sessionIds: Collection<String>, planId: String): List<AutomationSweepRunEntity>

    /** Moves every sweep run of session [from] to [into]; entities already loaded are not refreshed. */
    @Modifying(flushAutomatically = true)
    @Query("update AutomationSweepRunEntity r set r.sessionId = :into where r.sessionId = :from")
    fun moveSession(@Param("from") from: String, @Param("into") into: String): Int

    /** Moves the sweep runs of the items [itemIds] to session [into]; entities already loaded are not refreshed. */
    @Modifying(flushAutomatically = true)
    @Query("update AutomationSweepRunEntity r set r.sessionId = :into where r.itemId in :itemIds")
    fun moveItems(@Param("itemIds") itemIds: Collection<String>, @Param("into") into: String): Int

    /** Newest buy first, then by plan and label. */
    @Query("select r $SWEEP_RUN_FILTER order by i.blockTime desc nulls last, i.id, r.planId, r.label", countQuery = "select count(r) $SWEEP_RUN_FILTER")
    fun page(
        @Param("sessionId") sessionId: String?,
        @Param("mint") mint: String?,
        @Param("label") label: String?,
        @Param("outcome") outcome: String?,
        pageable: Pageable,
    ): Page<AutomationSweepRunEntity>
}
