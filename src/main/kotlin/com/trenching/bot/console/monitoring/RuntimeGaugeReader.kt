package com.trenching.bot.console.monitoring

import com.trenching.bot.console.domain.RuntimeGauges
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.TimeUnit
import org.springframework.stereotype.Component

/** Current values of the standard Micrometer JVM, process and HTTP server meters. */
@Component
class RuntimeGaugeReader(private val registry: MeterRegistry) {
    fun read(): RuntimeGauges {
        val requests = registry.find(HTTP_REQUESTS).timers()
        val requestCount = requests.sumOf { it.count() }
        val totalLatencyMs = requests.sumOf { it.totalTime(TimeUnit.MILLISECONDS) }
        return RuntimeGauges(
            heapUsedBytes = heapBytes(JVM_MEMORY_USED),
            heapMaxBytes = heapBytes(JVM_MEMORY_MAX),
            uptimeMs = registry.find(PROCESS_UPTIME).timeGauge()?.value(TimeUnit.MILLISECONDS)?.toLong(),
            cpuUsage = registry.find(PROCESS_CPU_USAGE).gauge()?.value()?.takeIf { it.isFinite() && it >= 0 },
            httpRequestCount = requestCount.takeIf { requests.isNotEmpty() },
            httpMeanLatencyMs = if (requestCount > 0) totalLatencyMs / requestCount else null,
        )
    }

    /** Pools without a defined size report -1; they are left out of the heap total. */
    private fun heapBytes(meter: String): Long? {
        val pools = registry.find(meter).tag(AREA, HEAP).gauges().map { it.value() }.filter { it.isFinite() && it >= 0 }
        return pools.takeIf { it.isNotEmpty() }?.sum()?.toLong()
    }

    private companion object {
        const val HTTP_REQUESTS = "http.server.requests"
        const val JVM_MEMORY_USED = "jvm.memory.used"
        const val JVM_MEMORY_MAX = "jvm.memory.max"
        const val PROCESS_UPTIME = "process.uptime"
        const val PROCESS_CPU_USAGE = "process.cpu.usage"
        const val AREA = "area"
        const val HEAP = "heap"
    }
}
