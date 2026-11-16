package com.trenching.bot.replay.application

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Indexes the runs stored before the search index existed, a page at a time. Safe to repeat: indexed runs, and runs the pass
 * failed on, are not read again.
 */
@Service
class RunSearchIndexBackfillService(private val pages: RunSearchIndexBackfillTransaction) {
    private val log = LoggerFactory.getLogger(RunSearchIndexBackfillService::class.java)

    fun backfill() {
        var indexed = 0
        var skipped = 0
        var failed = 0
        try {
            var afterId = ""
            val references = ReferenceIndexCache()
            while (true) {
                val page = pages.indexPage(afterId, references)
                indexed += page.indexed
                skipped += page.skipped
                failed += page.failed
                afterId = page.lastId ?: break
            }
        } catch (exception: RuntimeException) {
            log.error("run search index backfill stopped runs={} skipped={} failed={}", indexed, skipped, failed, exception)
            return
        }
        log.info("run search index backfilled runs={} skipped={} failed={}", indexed, skipped, failed)
    }
}
