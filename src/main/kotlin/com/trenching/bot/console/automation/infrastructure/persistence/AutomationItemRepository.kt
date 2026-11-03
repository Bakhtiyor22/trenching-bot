package com.trenching.bot.console.automation.infrastructure.persistence

import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionState
import java.time.Instant
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AutomationItemRepository : JpaRepository<AutomationItemEntity, String> {
    fun findOneById(id: String): AutomationItemEntity?

    /** The wallet's items for any of [mints]; [mints] is one discovery scan's and a mint has one item, so the result is bounded. */
    fun findAllByWalletAndMintIn(wallet: String, mints: Collection<String>): List<AutomationItemEntity>

    fun findAllBySessionIdOrderByBlockTimeAscIdAsc(sessionId: String, pageable: Pageable): List<AutomationItemEntity>

    /** Every item of one open session with its buy time, for regrouping it; bounded by one session's items. */
    @Query("select i.id as id, i.blockTime as blockTime from AutomationItemEntity i where i.sessionId = :sessionId order by i.blockTime asc nulls last, i.id")
    fun findBuysBySessionId(@Param("sessionId") sessionId: String): List<AutomationItemBuyRow>

    /** Moves every item of session [from] to [into], bumping each one's version; entities already loaded are not refreshed. */
    @Modifying(flushAutomatically = true)
    @Query("update versioned AutomationItemEntity i set i.sessionId = :into where i.sessionId = :from")
    fun moveSession(@Param("from") from: String, @Param("into") into: String): Int

    /** Moves the items [ids] to session [into], bumping each one's version; entities already loaded are not refreshed. */
    @Modifying(flushAutomatically = true)
    @Query("update versioned AutomationItemEntity i set i.sessionId = :into where i.id in :ids")
    fun moveItems(@Param("ids") ids: Collection<String>, @Param("into") into: String): Int

    fun findAllBySessionIdAndStateNotIn(sessionId: String, states: Collection<AutomationItemState>): List<AutomationItemEntity>

    fun countBySessionIdAndStateNotIn(sessionId: String, states: Collection<AutomationItemState>): Long

    fun countBySessionId(sessionId: String): Long

    /** Items in [state] whose next action is due at [now] (or unset), oldest buy first, one page at a time. */
    @Query("""
        select i from AutomationItemEntity i
        where i.state = :state and (i.nextActionAt is null or i.nextActionAt <= :now)
        order by i.blockTime asc nulls last, i.id
    """)
    fun findDueInState(@Param("state") state: AutomationItemState, @Param("now") now: Instant, pageable: Pageable): List<AutomationItemEntity>

    /** The id and state of each item of [sessionIds], for scoring an evaluation window. */
    @Query("select i.id as id, i.state as state from AutomationItemEntity i where i.sessionId in :sessionIds order by i.sessionId, i.blockTime, i.id")
    fun findStatesBySessionIdIn(@Param("sessionIds") sessionIds: Collection<String>, pageable: Pageable): List<AutomationItemStateRow>

    @Query("""
        select i.sessionId as sessionId, i.state as state, count(i) as items from AutomationItemEntity i
        where i.sessionId in :sessionIds group by i.sessionId, i.state
    """)
    fun countBySessionAndState(@Param("sessionIds") sessionIds: Collection<String>): List<AutomationItemStateCountRow>

    /** Each session's provider requests: the sum of its items' [AutomationItemEntity.requestsUsed], zero when none has any. */
    @Query("""
        select i.sessionId as sessionId, coalesce(sum(i.requestsUsed), 0) as requests from AutomationItemEntity i
        where i.sessionId in :sessionIds group by i.sessionId
    """)
    fun sumRequestsBySession(@Param("sessionIds") sessionIds: Collection<String>): List<AutomationSessionRequestsRow>

    /** Items in [states] of a session in [sessionStates] whose next action is due at [now] (or unset), oldest buy first. */
    @Query("""
        select i from AutomationItemEntity i
        where i.state in :states and (i.nextActionAt is null or i.nextActionAt <= :now)
          and exists (select 1 from AutomationSessionEntity s where s.id = i.sessionId and s.state in :sessionStates)
        order by i.blockTime asc nulls last, i.id
    """)
    fun findDue(
        @Param("states") states: Collection<AutomationItemState>,
        @Param("sessionStates") sessionStates: Collection<AutomationSessionState>,
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<AutomationItemEntity>
}
