package com.trenching.bot.console

import com.trenching.bot.console.monitoring.RuntimeGaugeReader
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.TimeGauge
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class RuntimeGaugeReaderTest {
    private val registry = SimpleMeterRegistry()
    private val reader = RuntimeGaugeReader(registry)

    @Test
    fun `missing meters read as null`() {
        val gauges = reader.read()

        assertNull(gauges.heapUsedBytes)
        assertNull(gauges.heapMaxBytes)
        assertNull(gauges.uptimeMs)
        assertNull(gauges.cpuUsage)
        assertNull(gauges.httpRequestCount)
        assertNull(gauges.httpMeanLatencyMs)
    }

    @Test
    fun `heap sums the heap pools and leaves out undefined maxima and non-heap pools`() {
        heap("jvm.memory.used", "eden", 100.0)
        heap("jvm.memory.used", "old", 300.0)
        heap("jvm.memory.max", "eden", -1.0)
        heap("jvm.memory.max", "old", 1000.0)
        Gauge.builder("jvm.memory.used") { 5000.0 }.tags("area", "nonheap", "id", "metaspace").register(registry)

        val gauges = reader.read()

        assertEquals(400, gauges.heapUsedBytes)
        assertEquals(1000, gauges.heapMaxBytes)
    }

    @Test
    fun `http requests are counted across all tags with their mean latency`() {
        Timer.builder("http.server.requests").tag("uri", "/a").register(registry).record(Duration.ofMillis(10))
        Timer.builder("http.server.requests").tag("uri", "/b").register(registry).record(Duration.ofMillis(30))
        TimeGauge.builder("process.uptime", { 90.0 }, TimeUnit.SECONDS).register(registry)
        Gauge.builder("process.cpu.usage") { 0.25 }.register(registry)

        val gauges = reader.read()

        assertEquals(2, gauges.httpRequestCount)
        assertEquals(20.0, gauges.httpMeanLatencyMs)
        assertEquals(90_000, gauges.uptimeMs)
        assertEquals(0.25, gauges.cpuUsage)
    }

    @Test
    fun `an unavailable cpu reading is null`() {
        Gauge.builder("process.cpu.usage") { Double.NaN }.register(registry)

        assertNull(reader.read().cpuUsage)
    }

    private fun heap(name: String, pool: String, value: Double) {
        Gauge.builder(name) { value }.tags("area", "heap", "id", pool).register(registry)
    }
}
