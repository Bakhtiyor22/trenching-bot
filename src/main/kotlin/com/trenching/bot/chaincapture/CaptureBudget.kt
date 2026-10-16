package com.trenching.bot.chaincapture

import com.trenching.bot.apiusage.ApiUsageContext
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * One invocation's allowance, safe to share between the threads of one capture. [operationTimeout] overrides the
 * transport's default deadline for this invocation only. [usage] attributes every request it pays for in API usage.
 */
class CaptureBudget(
    val maxRequests: Int,
    val maxBytes: Long,
    val initialRequests: Int = 0,
    val initialBytes: Long = 0,
    val operationTimeout: Duration? = null,
    val usage: ApiUsageContext = ApiUsageContext.UNATTRIBUTED,
) {
    private val deadlineNanos = AtomicReference<Long?>(null)
    private val requests = AtomicInteger()
    private val bytes = AtomicLong()

    /** What this invocation did besides sending requests, for throughput measurements. */
    val counters = CaptureCounters()

    val requestsUsed: Int get() = requests.get()
    val bytesUsed: Long get() = bytes.get()

    fun request() {
        while (true) {
            val used = requests.get()
            if (used >= maxRequests) throw CaptureBudgetException("REQUESTS", used, bytesUsed)
            if (requests.compareAndSet(used, used + 1)) return
        }
    }

    fun bytes(count: Long) {
        if (count < 0) throw CaptureBudgetException("BYTES", requestsUsed, bytesUsed)
        val total = bytes.addAndGet(count)
        if (total > maxBytes) throw CaptureBudgetException("BYTES", requestsUsed, total)
    }

    fun remainingBytes(): Long = maxOf(0, maxBytes - bytesUsed)

    /** Requests across every run of one capture directory, including those recorded before this run. */
    fun cumulativeRequests(): Int = Math.addExact(initialRequests, requestsUsed)

    fun cumulativeBytes(): Long = Math.addExact(initialBytes, bytesUsed)

    fun startDeadline(nowNanos: Long, timeout: Duration) {
        deadlineNanos.compareAndSet(null, nowNanos + timeout.toNanos())
    }

    fun checkDeadline(nowNanos: Long) {
        if (remainingNanos(nowNanos) <= 0) throw CaptureBudgetException("DEADLINE", requestsUsed, bytesUsed)
    }

    fun remainingNanos(nowNanos: Long): Long = (deadlineNanos.get() ?: Long.MAX_VALUE) - nowNanos
}
