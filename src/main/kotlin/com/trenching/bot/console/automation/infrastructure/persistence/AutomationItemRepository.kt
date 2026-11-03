package com.trenching.bot.console.automation.infrastructure.persistence

import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.BackfillState
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

    /** Items in [state] whose next action is due at [now] (or unset): live items first, then a backfill's, each oldest buy first; one page at a time. */
    @Query("""
        select i from AutomationItemEntity i
        where i.state = :state and (i.nextActionAt is null or i.nextActionAt <= :now)
        order by case when i.backfillId is null then 0 else 1 end, i.blockTime asc nulls last, i.id
    """)
    fun findDueInState(@Param("state") state: AutomationItemState, @Param("now") now: Instant, pageable: Pageable): List<AutomationItemEntity>

    /**
     * The id, session, state and failure code of each item of [sessionIds], for a trading day's completeness and scoring. Callers
     * bound the sessions, which bounds the rows; none is cut off, since a verdict must never rest on a truncated day.
     */
    @Query("""
        select i.id as id, i.sessionId as sessionId, i.state as state, i.failureCode as failureCode from AutomationItemEntity i
        where i.sessionId in :sessionIds order by i.sessionId, i.blockTime, i.id
    """)
    fun findStatesBySessionIdIn(@Param("sessionIds") sessionIds: Collection<String>): List<AutomationItemStateRow>

    /** The items of [sessionIds], oldest buy first, buys without a block time last. */
    @Query("select i from AutomationItemEntity i where i.sessionId in :sessionIds order by i.blockTime asc nulls last, i.id")
    fun findAllBySessionIdIn(@Param("sessionIds") sessionIds: Collection<String>, pageable: Pageable): List<AutomationItemEntity>

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

    /**
     * Items in [states] whose next action is due at [now] (or unset), in the pipeline's priority (plan Rule 40, amended
     * 2026-11-03), each class oldest buy first, one page at a time:
     *
     * 0. live items of a session that still takes or awaits buys (`OPEN` or `CLOSING`), tonight's work;
     * 1. the phase 0 backlog: live items of a `CLOSED` session. With capture on a session cannot close while one of its items is
     *    non-terminal, so these are exactly the items discovered while capture was off, from the moment capture is switched on
     *    until each one is terminal; they never delay a buy of a session that still takes or awaits buys;
     * 2. items of a backfill in [backfillStates], whatever their session's state.
     */
    @Query("""
        select i from AutomationItemEntity i join AutomationSessionEntity s on s.id = i.sessionId
        where i.state in :states and (i.nextActionAt is null or i.nextActionAt <= :now)
          and (i.backfillId is null
            or exists (select 1 from AutomationBackfillEntity b where b.id = i.backfillId and b.state in :backfillStates))
        order by case
            when i.backfillId is not null then 2
            when s.state = com.trenching.bot.console.automation.domain.AutomationSessionState.CLOSED then 1
            else 0 end,
          i.blockTime asc nulls last, i.id
    """)
    fun findDue(
        @Param("states") states: Collection<AutomationItemState>,
        @Param("backfillStates") backfillStates: Collection<BackfillState>,
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<AutomationItemEntity>

    /** The wallet's earliest stored buy at or after [from], by slot: where a listing can be entered without reading what lies after it. */
    fun findFirstByWalletAndBlockTimeGreaterThanEqualOrderBySlotAscSignatureAsc(wallet: String, blockTime: Instant): AutomationItemEntity?

    fun countByBackfillIdAndStateNotIn(backfillId: String, states: Collection<AutomationItemState>): Long

    fun existsByBackfillIdAndFailureCode(backfillId: String, failureCode: String): Boolean

    /** The provider requests of the backfill's captures so far (plan Rule 39): the sum of its items' requests, zero when none has any. */
    @Query("select coalesce(sum(i.requestsUsed), 0) from AutomationItemEntity i where i.backfillId = :backfillId")
    fun sumRequestsByBackfillId(@Param("backfillId") backfillId: String): Long

    /** Each backfill's captured requests, for a page of backfills. */
    @Query("""
        select i.backfillId as backfillId, coalesce(sum(i.requestsUsed), 0) as requests from AutomationItemEntity i
        where i.backfillId in :backfillIds group by i.backfillId
    """)
    fun sumRequestsByBackfill(@Param("backfillIds") backfillIds: Collection<String>): List<AutomationBackfillRequestsRow>

    @Query("""
        select i.backfillId as backfillId, i.state as state, count(i) as items from AutomationItemEntity i
        where i.backfillId in :backfillIds group by i.backfillId, i.state
    """)
    fun countByBackfillAndState(@Param("backfillIds") backfillIds: Collection<String>): List<AutomationBackfillStateCountRow>

    /** How many of the wallet's items in [states] report their requests: the sample a capture estimate takes its median from. */
    @Query("select count(i) from AutomationItemEntity i where i.wallet = :wallet and i.state in :states and i.requestsUsed is not null")
    fun countReportingRequests(@Param("wallet") wallet: String, @Param("states") states: Collection<AutomationItemState>): Long

    /** The requests of those items in ascending order; a page of one row reads the value at an index. */
    @Query("select i.requestsUsed from AutomationItemEntity i where i.wallet = :wallet and i.state in :states and i.requestsUsed is not null order by i.requestsUsed, i.id")
    fun findRequestsAscending(@Param("wallet") wallet: String, @Param("states") states: Collection<AutomationItemState>, pageable: Pageable): List<Int>
}
