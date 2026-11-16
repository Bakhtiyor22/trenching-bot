package com.trenching.bot.console.automation.api

import com.trenching.bot.console.automation.application.AutomationHistoryStartService
import com.trenching.bot.console.config.ConditionalOnConsoleEnabled
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Starts the histories of the items that predate the event table once at startup, outside any request. It is the automation
 * console's own condition spelled out with `!test`: the test profile starts every context from empty tables, and a test that wants
 * it calls [AutomationHistoryStartService] itself. It runs first of the startup runners, before the run search index backfill: it
 * writes a few small rows, where the backfill reads whole datasets.
 */
@Component
@Profile("(local | prod) & !test")
@ConditionalOnConsoleEnabled
@Order(1)
class AutomationHistoryStartRunner(private val history: AutomationHistoryStartService) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        history.start()
    }
}
