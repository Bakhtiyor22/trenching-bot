package com.trenching.bot.console.automation.api

import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.automation.application.AutomationActivitySummary
import com.trenching.bot.console.automation.application.AutomationBackfillDiscoveryService
import com.trenching.bot.console.automation.application.AutomationBackfillService
import com.trenching.bot.console.automation.application.AutomationDiscoveryService
import com.trenching.bot.console.automation.application.AutomationPipelineService
import com.trenching.bot.console.automation.application.AutomationSessionService
import com.trenching.bot.console.automation.application.AutomationSweepService
import com.trenching.bot.console.automation.config.ConditionalOnAutomationEnabled
import com.trenching.bot.console.automation.domain.AutomationLimits
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/**
 * The automation's timed triggers; each calls one service, which handles and logs its own failures per session or item. They
 * share Spring's single scheduling thread, so they never run at the same time.
 */
@Component
@ConditionalOnAutomationEnabled
class AutomationScheduler(
    private val discovery: AutomationDiscoveryService,
    private val sessions: AutomationSessionService,
    private val pipeline: AutomationPipelineService,
    private val sweep: AutomationSweepService,
    private val backfillDiscovery: AutomationBackfillDiscoveryService,
    private val backfills: AutomationBackfillService,
    private val summary: AutomationActivitySummary,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
) {
    @Scheduled(fixedDelayString = TICK_INTERVAL)
    fun tick() {
        sessions.closeIdleSessions()
    }

    @Scheduled(fixedDelayString = TICK_INTERVAL)
    fun advanceItems() {
        pipeline.advance()
    }

    @Scheduled(fixedDelayString = TICK_INTERVAL)
    fun sweepItems() {
        sweep.sweep()
    }

    @Scheduled(fixedDelayString = TICK_INTERVAL)
    fun scanBackfill() {
        backfillDiscovery.scan()
    }

    @Scheduled(fixedDelayString = TICK_INTERVAL)
    fun settleBackfills() {
        backfills.settle()
    }

    @Scheduled(fixedDelayString = DISCOVERY_INTERVAL)
    fun discover() {
        discovery.discover()
    }

    @Scheduled(fixedDelayString = AutomationLimits.SUMMARY_INTERVAL, initialDelayString = AutomationLimits.SUMMARY_INTERVAL)
    fun logSummary() {
        summary.log(clock.instant())
    }

    private companion object {
        const val TICK_INTERVAL = "\${trenching.automation.tick-interval:1m}"
        const val DISCOVERY_INTERVAL = "\${trenching.automation.discovery-interval:10m}"
    }
}
