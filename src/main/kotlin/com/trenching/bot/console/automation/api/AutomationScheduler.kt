package com.trenching.bot.console.automation.api

import com.trenching.bot.console.automation.application.AutomationDiscoveryService
import com.trenching.bot.console.automation.application.AutomationEvaluationService
import com.trenching.bot.console.automation.application.AutomationPipelineService
import com.trenching.bot.console.automation.application.AutomationSessionService
import com.trenching.bot.console.automation.application.AutomationSweepService
import com.trenching.bot.console.automation.config.ConditionalOnAutomationEnabled
import org.springframework.scheduling.annotation.Scheduled
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
    private val evaluation: AutomationEvaluationService,
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
    fun evaluateSessions() {
        evaluation.evaluateClosedSessions()
    }

    @Scheduled(fixedDelayString = DISCOVERY_INTERVAL)
    fun discover() {
        discovery.discover()
    }

    private companion object {
        const val TICK_INTERVAL = "\${trenching.automation.tick-interval:1m}"
        const val DISCOVERY_INTERVAL = "\${trenching.automation.discovery-interval:10m}"
    }
}
