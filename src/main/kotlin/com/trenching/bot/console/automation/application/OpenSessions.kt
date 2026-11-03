package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.domain.AutomationIds
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.SessionIdleRule
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemBuyRow
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionEntity
import java.time.Instant

/**
 * A wallet's open sessions while one discovery scan is stored (plan Rules 10 and 31, amended 2026-11-03). A session is a run of
 * buys with no gap longer than the idle time, inside one trading day: [SessionIdleRule.fits] and [SessionIdleRule.runs] never join
 * buys of two days, so a buy fits, merges and regroups only within its own day. A buy joins the open session it fits by time ([SessionIdleRule.fits]), whichever scan
 * found that session, so a catch-up extends a session backwards or opens an earlier one. A buy that fits two sessions closes the
 * gap between them, so they become one: the session discovered first (then the one with the earlier first buy, then the smaller
 * id) absorbs the others and keeps its id. A replaced buy can open a gap again, and [regroup] then splits the session there. So
 * the sessions are the same runs of buys whatever order discovery finds them in; only the ids depend on it. Only `OPEN`
 * sessions take buys; a session starts closing only once discovery is complete past its idle time, so no buy still to be found
 * can fit it.
 *
 * A buy without a block time cannot be placed in time: it joins the latest open session without changing its span, or, with
 * none open, a session of its own that starts `CLOSING`, so it never takes or bridges a buy.
 *
 * Built inside the discovery transaction from [stored], the open sessions of the wallet's live discovery, or of one backfill when
 * [backfillId] is given: a backfill's buys are placed among its own sessions only, and the sessions it opens carry its id (plan
 * Rule 38). The transaction then saves [opened], and moves the items of each session in [absorbed] to the one it now belongs to
 * ([current]).
 */
class OpenSessions(
    private val wallet: String,
    stored: List<AutomationSessionEntity>,
    private val idle: SessionIdleRule,
    private val now: Instant,
    private val backfillId: String? = null,
) {
    private val storedById = stored.associateBy { it.id }.toMutableMap()
    private val open = stored.sortedWith(SURVIVOR_FIRST).toMutableList()
    private val created = mutableListOf<AutomationSessionEntity>()
    private val absorbedInto = mutableMapOf<String, AutomationSessionEntity>()

    /** The sessions this scan opened that still stand and are not stored yet, in the order they were opened. */
    val opened: List<AutomationSessionEntity> get() = created.filter { it.id !in absorbedInto }

    /** The stored sessions another session absorbed. */
    val absorbed: List<AutomationSessionEntity> get() = absorbedInto.keys.mapNotNull(storedById::get)

    fun isOpen(sessionId: String): Boolean = open.any { it.id == sessionId }

    /** The session the buy [itemId] at [blockTime] joins, extended to cover it. */
    fun place(itemId: String, blockTime: Instant?): AutomationSessionEntity {
        if (blockTime == null) return open.maxByOrNull { it.lastBuyAt } ?: openNew(itemId, now, AutomationSessionState.CLOSING)
        val fitting = open.filter { idle.fits(it.firstBuyAt, it.lastBuyAt, blockTime) }.sortedWith(SURVIVOR_FIRST)
        val session = fitting.firstOrNull() ?: openNew(itemId, blockTime, AutomationSessionState.OPEN)
        fitting.drop(1).forEach { absorb(it, session) }
        if (blockTime.isBefore(session.firstBuyAt)) session.firstBuyAt = blockTime
        if (blockTime.isAfter(session.lastBuyAt)) session.lastBuyAt = blockTime
        return session
    }

    /** The open session a buy the capture filter skips is counted on: the one already covering [blockTime], if any. */
    fun covering(blockTime: Instant?): AutomationSessionEntity? {
        if (blockTime == null) return open.maxByOrNull { it.lastBuyAt }
        return open.firstOrNull { !blockTime.isBefore(it.firstBuyAt) && idle.fits(it.firstBuyAt, it.lastBuyAt, blockTime) }
    }

    /** The id of the session [sessionId] now belongs to, following every absorption. */
    fun current(sessionId: String): String = absorbedInto[sessionId]?.let { current(it.id) } ?: sessionId

    /**
     * Rebuilds the open session [sessionId] from the items [left] in it after some were removed. Its buys split into runs
     * wherever two are more than the idle time apart: the run holding the session's first item, or else its earliest run, keeps
     * the session, with the span of that run, and its earliest item as first item if the first one is gone; the id never changes.
     * Each other run opens a session of its own, which the caller stores at once, moving that run's items and sweep rows there.
     * Items without a block time stay. A session left with no buy is removed; one left with only buys without a block time takes
     * no more buys and starts `CLOSING`, with the first of those (`left` is ordered by block time, nulls last, then id) as its
     * first item. The buys below the filter stay counted on the session that keeps the id.
     */
    fun regroup(sessionId: String, left: List<AutomationItemBuyRow>): SessionRegrouping {
        val session = requireNotNull(open.firstOrNull { it.id == sessionId }) { "Only an open session is regrouped sessionId=$sessionId" }
        val timed = left.mapNotNull { row -> row.blockTime?.let { TimedBuy(row.id, it) } }.sortedWith(compareBy({ it.at }, { it.itemId }))
        if (timed.isEmpty()) {
            open.remove(session)
            if (left.isEmpty()) return SessionRegrouping(emptied = session)
            session.state = AutomationSessionState.CLOSING
            session.firstItemId = left.first().id
            return SessionRegrouping(closing = session)
        }
        val runs = idle.runs(timed) { it.at }
        val kept = runs.firstOrNull { run -> run.any { it.itemId == session.firstItemId } } ?: runs.first()
        if (kept.none { it.itemId == session.firstItemId }) session.firstItemId = kept.first().itemId
        span(session, kept)
        val split = runs.filter { it !== kept }.associate { run ->
            val part = openNew(run.first().itemId, run.first().at, AutomationSessionState.OPEN)
            created.remove(part)
            storedById[part.id] = part
            span(part, run)
            part to run.map { it.itemId }
        }
        return SessionRegrouping(split = split)
    }

    private fun span(session: AutomationSessionEntity, run: List<TimedBuy>) {
        session.firstBuyAt = run.first().at
        session.lastBuyAt = run.last().at
    }

    private fun absorb(session: AutomationSessionEntity, into: AutomationSessionEntity) {
        open.remove(session)
        absorbedInto[session.id] = into
        if (session.firstBuyAt.isBefore(into.firstBuyAt)) into.firstBuyAt = session.firstBuyAt
        if (session.lastBuyAt.isAfter(into.lastBuyAt)) into.lastBuyAt = session.lastBuyAt
        into.belowFilterCount += session.belowFilterCount
    }

    private fun openNew(firstItemId: String, buyAt: Instant, state: AutomationSessionState): AutomationSessionEntity = AutomationSessionEntity(
        AutomationIds.session(wallet, firstItemId), wallet, firstItemId, buyAt, buyAt, state, null, null, 0, now,
    ).also {
        it.backfillId = backfillId
        created += it
        if (state == AutomationSessionState.OPEN) open += it
    }

    private data class TimedBuy(val itemId: String, val at: Instant)

    private companion object {
        val SURVIVOR_FIRST: Comparator<AutomationSessionEntity> = compareBy({ it.createdAt }, { it.firstBuyAt }, { it.id })
    }
}
