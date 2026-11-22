package com.trenching.bot.console.automation.api

import com.trenching.bot.console.automation.application.AutomationVariationService
import com.trenching.bot.console.config.ConditionalOnConsoleEnabled
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Seeds the sweep variations and checks the active ones once at startup (plan Key Decision 3 and Rule 8); a violation fails
 * startup. A sweep tick that runs before it finds no variation and sweeps nothing. It is the automation console's own condition
 * spelled out with `!test`, as [AutomationHistoryStartRunner] does: a test that needs the rows calls the service itself. It runs
 * before every other startup runner.
 */
@Component
@Profile("(local | prod) & !test")
@ConditionalOnConsoleEnabled
@Order(0)
class AutomationVariationStartRunner(private val variations: AutomationVariationService) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        variations.start()
    }
}
