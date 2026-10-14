package com.trenching.bot.console.capture

import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

fun interface CaptureRetryScheduler : AutoCloseable {
    fun schedule(delay: Duration, action: () -> Unit)

    override fun close() = Unit

    companion object {
        val NONE = CaptureRetryScheduler { _, _ -> }
    }
}

class ExecutorCaptureRetryScheduler(
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { action ->
        Thread(action, "capture-retry-scheduler").apply { isDaemon = true }
    },
) : CaptureRetryScheduler {
    override fun schedule(delay: Duration, action: () -> Unit) {
        executor.schedule(action, maxOf(0, delay.toMillis()), TimeUnit.MILLISECONDS)
    }

    override fun close() {
        executor.shutdownNow()
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
