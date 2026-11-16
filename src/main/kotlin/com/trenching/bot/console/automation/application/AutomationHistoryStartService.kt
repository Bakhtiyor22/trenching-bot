package com.trenching.bot.console.automation.application

import com.trenching.bot.config.SimulationClock
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationHistoryStartResult
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/**
 * Starts the history of the items that predate the event table (plan Domain Rule H4) and are idle: each item with no event gets one
 * `HISTORY_STARTED` carrying its state now, in a transaction of its own. An item the pipeline changed meanwhile already has its
 * history started by the writer, and is skipped; one that fails is logged and left for the next pass, and the pass carries on.
 * Safe to repeat: an item that has an event is not read again.
 */
@Service
@ConditionalOnAutomationConsole
class AutomationHistoryStartService(
    private val pages: AutomationHistoryStartTransaction,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(AutomationHistoryStartService::class.java)

    fun start(): AutomationHistoryStartResult {
        var started = 0
        var skipped = 0
        var failed = 0
        try {
            var afterId = ""
            while (true) {
                val ids = pages.idsAfter(afterId)
                if (ids.isEmpty()) break
                ids.forEach { itemId ->
                    try {
                        if (pages.start(itemId, clock.instant())) started++ else skipped++
                    } catch (exception: RuntimeException) {
                        failed++
                        log.warn("automation history start failed itemId={}", itemId, exception)
                    }
                }
                afterId = ids.last()
            }
        } catch (exception: RuntimeException) {
            log.error("automation history start stopped items={} skipped={} failed={}", started, skipped, failed, exception)
            return AutomationHistoryStartResult(started, skipped, failed)
        }
        log.info("automation history started items={} skipped={} failed={}", started, skipped, failed)
        return AutomationHistoryStartResult(started, skipped, failed)
    }
}
