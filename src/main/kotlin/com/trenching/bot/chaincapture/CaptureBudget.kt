package com.trenching.bot.chaincapture

import java.time.Duration

class CaptureBudget(val maxRequests: Int, val maxBytes: Long, val initialRequests: Int = 0, val initialBytes: Long = 0) {
    private var deadlineNanos: Long? = null
    var requestsUsed: Int = 0
        private set
    var bytesUsed: Long = 0
        private set

    fun request() {
        if (requestsUsed >= maxRequests) throw CaptureBudgetException("REQUESTS")
        requestsUsed++
    }

    fun bytes(count: Long) {
        if (count < 0) throw CaptureBudgetException("BYTES")
        bytesUsed += count
        if (bytesUsed > maxBytes) throw CaptureBudgetException("BYTES")
    }

    fun remainingBytes(): Long = maxOf(0, maxBytes - bytesUsed)

    /** Requests across every run of one capture directory, including those recorded before this run. */
    fun cumulativeRequests(): Int = Math.addExact(initialRequests, requestsUsed)

    fun cumulativeBytes(): Long = Math.addExact(initialBytes, bytesUsed)

    fun startDeadline(nowNanos: Long, timeout: Duration) {
        if (deadlineNanos == null) deadlineNanos = nowNanos + timeout.toNanos()
    }

    fun checkDeadline(nowNanos: Long) {
        if (remainingNanos(nowNanos) <= 0) throw CaptureBudgetException("DEADLINE")
    }

    fun remainingNanos(nowNanos: Long): Long = (deadlineNanos ?: Long.MAX_VALUE) - nowNanos
}
