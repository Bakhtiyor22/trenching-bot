package com.trenching.bot.console.automation.application

import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationDisabledException
import com.trenching.bot.console.automation.domain.ManualRunResult
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Service

/**
 * "Run now" (plan Key Decision 2 and Rule 29): hands one discovery pass to the application's [TaskScheduler], whose single thread
 * also runs the timed discovery, the pipeline, the sweep and the session ticks, so the pass never overlaps them. The request
 * returns at once; a second request while a pass is queued or running adds nothing. The pass ignores the polling window and
 * otherwise scans like a scheduled one. [discovery] and [scheduler] exist only while automation is enabled; without them a
 * request is refused.
 */
@Service
@ConditionalOnAutomationConsole
class AutomationManualRun(
    private val properties: AutomationProperties,
    private val runtime: AutomationRuntime,
    private val discovery: AutomationDiscoveryService?,
    private val scheduler: TaskScheduler?,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(AutomationManualRun::class.java)

    fun request(): ManualRunResult {
        val service = discovery?.takeIf { properties.enabled } ?: throw AutomationDisabledException()
        val tasks = checkNotNull(scheduler) { "Automation is enabled without a task scheduler" }
        if (!runtime.queueManualRun()) return ManualRunResult.ALREADY_QUEUED
        try {
            tasks.schedule({ run(service) }, clock.instant())
        } catch (e: RuntimeException) {
            runtime.manualRunFinished()
            throw e
        }
        log.info("automation manual discovery queued")
        return ManualRunResult.QUEUED
    }

    private fun run(service: AutomationDiscoveryService) {
        try {
            service.discover(ignoreWindow = true)
        } catch (e: Exception) {
            log.error("automation manual discovery failed", e)
        } finally {
            runtime.manualRunFinished()
        }
    }
}
