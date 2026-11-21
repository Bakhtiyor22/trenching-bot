package com.trenching.bot.console.automation.application

import com.trenching.bot.common.LogDurations
import com.trenching.bot.common.LogLine
import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.automation.config.ConditionalOnAutomationEnabled
import com.trenching.bot.console.automation.domain.AutomationHold
import com.trenching.bot.console.automation.domain.AutomationHoldContext
import com.trenching.bot.console.automation.domain.AutomationHoldKind
import com.trenching.bot.console.automation.domain.AutomationLimits
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/**
 * Says out loud, once, when something holds the automation's capture back, and when it is over (plan Key Decision 8). The pipeline and
 * the backfill scan report each tick's held and admitted items per [AutomationHoldKind] with [observe]; nothing is logged per item or per tick.
 *
 * A kind is either free, held, or clearing:
 *  - free to held: one WARN naming the hold, with the numbers and how many items wait;
 *  - held: later ticks that hold items say nothing;
 *  - held to clearing: a tick that admits an item and holds none, or, for a kind whose callers say so with `blockedDue`, a tick that holds
 *    none while no due item of the kind is still blocked, because the items left by another route (a manual close, a cancelled backfill,
 *    items that failed) and nothing will ever admit them. No line, because with the window at the cap a freed batch of requests
 *    admits one operation, which fills the cap again, so admitted and held ticks alternate; a line per change would be a line per operation;
 *  - clearing to held: a tick that holds an item again, no line;
 *  - clearing to free: once the hold has stayed clear for [AutomationLimits.HOLD_SETTLE], one INFO with how long capture was held, from the
 *    start to when it began to clear. [settle] is checked on every [observe] of the kind and by the hourly summary.
 *
 * A tick that held and admitted nothing, because nothing was due or the capture worker was busy, changes nothing. Nothing is
 * remembered across a restart: a hold still in force is logged again on the first tick, which is what a reader of the new process wants.
 * The pipeline ticks on the scheduling thread and the summary reads from it too; the state is kept under a lock all the same.
 */
@Component
@ConditionalOnAutomationEnabled
class AutomationHolds(@param:Qualifier(SimulationClock.NAME) private val clock: Clock) {
    private val log = LoggerFactory.getLogger(AutomationHolds::class.java)
    private val lock = Any()
    private val states = mutableMapOf<AutomationHoldKind, HoldState>()

    /**
     * One tick's [held] and [admitted] items of [kind]; [context] carries the numbers the lines print. [blockedDue] is how many due items of the
     * kind still stand `BLOCKED_BUDGET`, or null for a kind that clears only by admitting an item: with none held and none blocked, the
     * hold has nothing left to wait for and starts clearing.
     */
    fun observe(kind: AutomationHoldKind, held: Int, admitted: Int, context: AutomationHoldContext, blockedDue: Int? = null) {
        val now = clock.instant()
        synchronized(lock) {
            settle(kind, now)
            val state = states[kind]
            when {
                held > 0 && state == null -> start(kind, now, held, context)
                held > 0 && state != null -> state.holdsAgain(held, context)
                state != null && (admitted > 0 || blockedDue == 0) -> state.clearFrom(now, context)
                else -> Unit
            }
        }
    }

    /** Ends every hold that has stayed clear for [AutomationLimits.HOLD_SETTLE] by [now]. */
    fun settle(now: Instant) = synchronized(lock) { AutomationHoldKind.entries.forEach { settle(it, now) } }

    /** The holds in force, those that still hold items: a hold that began to clear is not one. */
    fun current(): List<AutomationHold> = synchronized(lock) {
        AutomationHoldKind.entries.mapNotNull { kind -> states[kind]?.takeIf { it.clearingSince == null }?.let { AutomationHold(kind, it.since, it.waiting, it.context) } }
    }

    private fun start(kind: AutomationHoldKind, now: Instant, waiting: Int, context: AutomationHoldContext) {
        states[kind] = HoldState(now, waiting, context)
        val sentence = if (kind.namesReason) LogLine(kind.paused, context.reason ?: UNKNOWN_REASON) else LogLine(kind.paused)
        sentence.numbers(context).field("waiting", waiting).warn(log)
    }

    private fun settle(kind: AutomationHoldKind, now: Instant) {
        val state = states[kind] ?: return
        val clearingSince = state.clearingSince ?: return
        if (Duration.between(clearingSince, now) < AutomationLimits.HOLD_SETTLE) return
        states.remove(kind)
        LogLine(kind.resumed, LogDurations.compact(Duration.between(state.since, clearingSince))).numbers(state.context).info(log)
    }

    private fun LogLine.numbers(context: AutomationHoldContext): LogLine =
        field("requestsLast24h", context.requestsLast24h).field("cap", context.cap).field("reserveRequests", context.reserveRequests)

    private class HoldState(val since: Instant, var waiting: Int, var context: AutomationHoldContext) {
        var clearingSince: Instant? = null
            private set

        fun holdsAgain(waiting: Int, context: AutomationHoldContext) {
            clearingSince = null
            this.waiting = waiting
            this.context = context
        }

        fun clearFrom(now: Instant, context: AutomationHoldContext) {
            if (clearingSince == null) clearingSince = now
            this.context = context
        }
    }

    private companion object {
        const val UNKNOWN_REASON = "no reason given"
    }
}
