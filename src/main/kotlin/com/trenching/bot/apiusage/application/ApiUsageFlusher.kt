package com.trenching.bot.apiusage.application

import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.DataIntegrityViolationException

/**
 * Writes the accumulated deltas every [interval] on its own thread, and once more on [close]. A failed flush keeps its
 * deltas in memory for the next one; only a hard kill loses counts, at most one interval of them.
 */
class ApiUsageFlusher(
    private val accumulator: ApiUsageAccumulator,
    private val store: ApiUsageBucketStore,
    private val interval: Duration,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { action ->
        Thread(action, "api-usage-flusher").apply { isDaemon = true }
    },
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(ApiUsageFlusher::class.java)

    fun start() {
        executor.scheduleWithFixedDelay(::flushQuietly, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * Returns how many buckets were written. Two writers creating the same bucket at once collide on its second unique
     * key instead of merging; the loser's deltas are put back and written again, now onto the existing row.
     */
    fun flush(): Int {
        var attempt = 1
        while (true) {
            try {
                return accumulator.drainTo(store::add)
            } catch (exception: RuntimeException) {
                if (!isWriteRace(exception) || attempt == MAX_ATTEMPTS) throw exception
                log.warn("api_usage flush_race attempt={}", attempt)
                attempt++
            }
        }
    }

    private fun isWriteRace(exception: RuntimeException) =
        exception is DataIntegrityViolationException || exception is ConcurrencyFailureException

    override fun close() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) log.warn("api_usage flusher_shutdown_timed_out")
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        flushQuietly()
    }

    private fun flushQuietly() {
        try {
            val buckets = flush()
            if (buckets > 0) log.debug("api_usage flushed buckets={}", buckets)
        } catch (exception: RuntimeException) {
            log.error("api_usage flush_failed", exception)
        }
    }

    private companion object {
        const val SHUTDOWN_WAIT_SECONDS = 5L
        const val MAX_ATTEMPTS = 3
    }
}
