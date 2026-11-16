package com.trenching.bot.replay.api

import com.trenching.bot.replay.application.RunSearchIndexBackfillService
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Runs the search-index backfill once at startup, outside any request; the test profile starts every context from empty tables.
 * It runs after the automation history start, which is cheap.
 */
@Component
@Profile("!test")
@Order(2)
class RunSearchIndexBackfillRunner(private val backfill: RunSearchIndexBackfillService) : ApplicationRunner {
    override fun run(args: ApplicationArguments) = backfill.backfill()
}
