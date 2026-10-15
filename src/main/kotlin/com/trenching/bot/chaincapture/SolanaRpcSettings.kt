package com.trenching.bot.chaincapture

import java.time.Duration

interface SolanaRpcSettings {
    val endpoint: String?
    val connectTimeout: Duration
    val readTimeout: Duration
    val operationTimeout: Duration

    /** Requests this endpoint may have in flight across the whole process. */
    val maxConcurrentRequests: Int

    /** Minimum time between two request starts on this endpoint across the whole process. */
    val minRequestInterval: Duration

    fun isTimeoutsValid(): Boolean = connectTimeout.isPositive && connectTimeout <= MAX_CONNECT_TIMEOUT &&
        readTimeout.isPositive && readTimeout <= MAX_READ_TIMEOUT &&
        operationTimeout >= readTimeout && operationTimeout <= MAX_OPERATION_TIMEOUT

    fun isPacingValid(): Boolean = maxConcurrentRequests in 1..MAX_CONCURRENT_REQUESTS &&
        minRequestInterval >= MIN_REQUEST_INTERVAL && minRequestInterval <= MAX_REQUEST_INTERVAL

    fun validate() {
        if (!isTimeoutsValid()) throw CaptureValidationException("TIMEOUTS")
        if (!isPacingValid()) throw CaptureValidationException("PACING")
    }

    companion object {
        val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val DEFAULT_READ_TIMEOUT: Duration = Duration.ofSeconds(15)
        const val DEFAULT_OPERATION_TIMEOUT_MINUTES = 10L
        const val MAX_OPERATION_TIMEOUT_MINUTES = 30L
        val DEFAULT_OPERATION_TIMEOUT: Duration = Duration.ofMinutes(DEFAULT_OPERATION_TIMEOUT_MINUTES)

        /** Four requests in flight and eight starts per second sit below the 10 requests per second free tier. */
        const val DEFAULT_MAX_CONCURRENT_REQUESTS = 4
        const val MAX_CONCURRENT_REQUESTS = 16
        val DEFAULT_MIN_REQUEST_INTERVAL: Duration = Duration.ofMillis(125)
        private val MIN_REQUEST_INTERVAL: Duration = Duration.ofMillis(20)
        private val MAX_REQUEST_INTERVAL: Duration = Duration.ofSeconds(10)
        private val MAX_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val MAX_READ_TIMEOUT: Duration = Duration.ofMinutes(1)
        private val MAX_OPERATION_TIMEOUT: Duration = Duration.ofMinutes(MAX_OPERATION_TIMEOUT_MINUTES)

        /** A per-invocation deadline chosen by the caller: whole minutes from 1 to [MAX_OPERATION_TIMEOUT_MINUTES]. */
        fun isInvocationTimeoutValid(timeout: Duration): Boolean =
            timeout >= Duration.ofMinutes(1) && timeout <= MAX_OPERATION_TIMEOUT && timeout.toSecondsPart() == 0 && timeout.toNanosPart() == 0
    }
}
