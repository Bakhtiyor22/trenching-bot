package com.trenching.bot.console.automation.infrastructure.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** Append-only: rows are inserted and read, never updated or deleted by the application. */
interface AutomationItemEventRepository : JpaRepository<AutomationItemEventEntity, String> {
    /** An item's events in order; callers bound the page to [com.trenching.bot.console.automation.domain.AutomationLimits.MAX_EVENTS_PER_ITEM]. */
    fun findAllByItemIdOrderBySeqAsc(itemId: String, pageable: Pageable): List<AutomationItemEventEntity>

    /** The order of the last event of each of [itemIds] that has one; bounded by the ids given. */
    @Query("select e.itemId as itemId, max(e.seq) as seq from AutomationItemEventEntity e where e.itemId in :itemIds group by e.itemId")
    fun findLastSeqs(@Param("itemIds") itemIds: Collection<String>): List<AutomationItemEventSeqRow>
}
