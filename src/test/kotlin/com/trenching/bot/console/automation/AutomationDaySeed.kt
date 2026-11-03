package com.trenching.bot.console.automation

import com.trenching.bot.console.automation.AutomationRows.item
import com.trenching.bot.console.automation.AutomationRows.session
import com.trenching.bot.console.automation.AutomationRows.sweepRun
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.AutomationStep
import com.trenching.bot.console.automation.domain.SweepPlans
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSweepRunRepository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Closed trading days written straight to the tables: a session at 21:00 local with simulated items whose entry V0k earns
 * (k - 4) / 100 SOL per signal, except V08 and V09, whose buys are below their minimum size (no signal).
 */
class AutomationDaySeed(
    private val sessions: AutomationSessionRepository,
    private val items: AutomationItemRepository,
    private val sweepRuns: AutomationSweepRunRepository,
) {
    private val labels = SweepPlans.DECU_SWEEP_1.entries.map { it.label }
    private var next = 1

    /** The number of the next item, for tests that write further items of their own. */
    fun nextNumber(): Int = next++

    /** The seven trading days from [first] to [last] (November 2026 dates), each closed with two signal items. */
    fun week(first: Int = 5, last: Int = 11) = (first..last).forEach { closedDay(date(it), signals = 2) }

    fun closedDay(date: String, signals: Int) {
        val sessionId = "session-$date"
        sessions.save(session(sessionId, AutomationSessionState.CLOSED, local(date, "21:00")))
        repeat(signals) {
            val n = nextNumber()
            items.save(item(n, sessionId, AutomationItemState.SIMULATED, blockTime = local(date, "21:00").plusSeconds(it.toLong())).apply { step = AutomationStep.SWEEP })
            sweepRuns.saveAll(labels.mapIndexed { k, label ->
                if (k >= BELOW_MINIMUM_FROM) {
                    sweepRun("r$n-$label", "item-$n", label, sessionId = sessionId, outcome = "SIGNAL_BELOW_MIN_SIZE", runId = "run-$n-$label")
                } else {
                    sweepRun("r$n-$label", "item-$n", label, sessionId = sessionId, outcome = "TAKE_PROFIT", runId = "run-$n-$label", signal = true,
                        worstCasePnl = BigDecimal(k - 4).movePointLeft(2).setScale(12).toPlainString())
                }
            })
        }
    }

    companion object {
        private const val BELOW_MINIMUM_FROM = 8

        fun date(dayOfNovember: Int): String = "2026-11-" + dayOfNovember.toString().padStart(2, '0')

        /** [time] (`HH:mm` or `HH:mm:ss`) on [date] in UTC+5. */
        fun local(date: String, time: String): Instant =
            LocalDateTime.parse("${date}T${if (time.length == 5) "$time:00" else time}").toInstant(ZoneOffset.ofHours(5))
    }
}
