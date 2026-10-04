package com.trenching.bot.chaincapture

import java.time.Duration

interface SolanaRpcSettings {
    val endpoint: String?
    val connectTimeout: Duration
    val readTimeout: Duration
    val operationTimeout: Duration

    fun isTimeoutsValid(): Boolean = connectTimeout.isPositive && connectTimeout <= MAX_CONNECT_TIMEOUT &&
        readTimeout.isPositive && readTimeout <= MAX_READ_TIMEOUT &&
        operationTimeout >= readTimeout && operationTimeout <= MAX_OPERATION_TIMEOUT

    fun validate() {
        if (!isTimeoutsValid()) throw CaptureValidationException("TIMEOUTS")
    }

    companion object {
        val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val DEFAULT_READ_TIMEOUT: Duration = Duration.ofSeconds(15)
        val DEFAULT_OPERATION_TIMEOUT: Duration = Duration.ofMinutes(10)
        private val MAX_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val MAX_READ_TIMEOUT: Duration = Duration.ofMinutes(1)
        private val MAX_OPERATION_TIMEOUT: Duration = Duration.ofMinutes(30)
    }
}
