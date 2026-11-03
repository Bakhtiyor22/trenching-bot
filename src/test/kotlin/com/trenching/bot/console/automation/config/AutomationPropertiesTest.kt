package com.trenching.bot.console.automation.config

import com.trenching.bot.console.api.dto.CaptureBudget
import com.trenching.bot.console.api.dto.CaptureRecoveryConfig
import com.trenching.bot.console.automation.domain.SweepEntry
import com.trenching.bot.console.automation.domain.SweepPlan
import com.trenching.bot.console.automation.domain.SweepPlans
import com.trenching.bot.strategy.domain.SimulationConfig
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.core.io.ClassPathResource

class AutomationPropertiesTest {
    @Test
    fun `the defaults are the approved plan's`() {
        val properties = bind(emptyMap())

        assertFalse(properties.enabled)
        assertFalse(properties.captureEnabled)
        assertNull(properties.wallet)
        assertEquals(ZoneOffset.ofHours(5), properties.zone)
        assertEquals(LocalTime.of(18, 0), properties.pollFrom)
        assertEquals(LocalTime.of(5, 0), properties.pollTo)
        assertEquals(Duration.ofMinutes(1), properties.tickInterval)
        assertEquals(Duration.ofMinutes(10), properties.discoveryInterval)
        assertEquals(Duration.ofHours(12), properties.initialLookback)
        assertEquals(5, properties.maxDiscoveryPages)
        assertEquals(Duration.ofMinutes(60), properties.sessionIdle)
        assertEquals(Duration.ofMinutes(35), properties.marketDelay)
        assertEquals(BigDecimal("1.5"), properties.captureMinSignalSol)
        assertEquals(300, properties.captureFeeMarginBps)
        assertEquals(2, properties.maxStepAttempts)
        assertEquals(5000L, properties.maxRequestsPer24h)
        assertEquals(CaptureBudget(400, 104_857_600, 10), properties.captureBudget())
        assertEquals(CaptureRecoveryConfig(1200, 314_572_800, 3), properties.captureRecovery())
        assertEquals(SweepPlans.DECU_SWEEP_1, properties.activePlan())
        assertEquals(listOf(7, 10), listOf(properties.evaluationWindowSessions, properties.minSignals))
        assertEquals(0, properties.minNetPnlSol.signum())
    }

    @Test
    fun `application yml states the same defaults, with automation and capture off`() {
        val loader = YamlPropertiesFactoryBean().apply { setResources(ClassPathResource("application.yml")) }
        val fromFile = Binder(MapConfigurationPropertySource(requireNotNull(loader.getObject())))
            .bind(AutomationProperties.PREFIX, AutomationProperties::class.java).get()
        val defaults = bind(emptyMap())

        assertFalse(fromFile.enabled)
        assertFalse(fromFile.captureEnabled)
        assertEquals(snapshot(defaults), snapshot(fromFile))
    }

    @Test
    fun `configuration strings bind in their documented formats`() {
        val properties = bind(mapOf(
            "enabled" to "true", "wallet" to WALLET, "zone" to "+05:00", "poll-from" to "18:00", "poll-to" to "05:00",
            "tick-interval" to "1m", "discovery-interval" to "10m", "initial-lookback" to "PT12H", "session-idle" to "60m",
            "market-delay" to "35m", "capture-min-signal-sol" to "1.5", "operation-budget.requests" to "300",
            "operation-budget.timeout" to "5m", "recovery.attempts" to "4", "recovery.bytes" to "200MB",
        ))

        assertEquals(WALLET, properties.walletAddress().value)
        assertEquals(LocalTime.of(5, 0), properties.pollTo)
        assertEquals(Duration.ofHours(12), properties.initialLookback)
        assertEquals(CaptureBudget(300, 104_857_600, 5), properties.captureBudget())
        assertEquals(CaptureRecoveryConfig(1200, 209_715_200, 4), properties.captureRecovery())
    }

    @Test
    fun `the default polling window crosses midnight in UTC+5`() {
        val window = bind(emptyMap()).pollWindow()

        assertTrue(window.contains(Instant.parse("2026-11-02T18:00:00Z")), "23:00 local")
        assertTrue(window.contains(Instant.parse("2026-11-02T21:00:00Z")), "02:00 local")
        assertFalse(window.contains(Instant.parse("2026-11-02T07:00:00Z")), "12:00 local")
    }

    @Test
    fun `a capture filter above the plan's smallest minimum fails startup naming both values`() {
        val message = failure(mapOf("capture-min-signal-sol" to "1.500000001"))

        assertTrue(message.contains("capture-min-signal-sol=1.500000001"), message)
        assertTrue(message.contains("1.5"), message)
        assertTrue(message.contains("decu-sweep-1"), message)
    }

    @Test
    fun `a capture filter equal to the plan's smallest minimum is accepted`() {
        assertEquals(BigDecimal("1.5"), bind(mapOf("capture-min-signal-sol" to "1.500000000")).captureMinSignalSol.stripTrailingZeros())
    }

    @Test
    fun `a plan entry without a minimum signal size is refused`() {
        val plan = SweepPlan("no-minimum", listOf(SweepEntry("V00", SimulationConfig.agreed()), SweepEntry("V01", SimulationConfig.mvp())))

        val error = assertFailsWith<IllegalArgumentException> { plan.requireCaptureMinimumWithin(BigDecimal("1.5")) }

        assertTrue(error.message.orEmpty().contains("V01"), error.message)
    }

    @Test
    fun `enabling automation without a wallet fails startup`() {
        assertTrue(failure(mapOf("enabled" to "true")).contains("trenching.automation.wallet"))
    }

    @Test
    fun `a wallet that is not a base58 address fails startup`() {
        assertTrue(failure(mapOf("wallet" to "not-a-wallet")).contains("trenching.automation.wallet"))
    }

    @Test
    fun `an unknown sweep plan fails startup`() {
        assertTrue(failure(mapOf("sweep-plan" to "decu-sweep-0")).contains("decu-sweep-0"))
    }

    @Test
    fun `values outside their bounds fail startup`() {
        listOf(
            "poll-to" to "18:00", "tick-interval" to "9s", "discovery-interval" to "61m", "initial-lookback" to "8d",
            "max-discovery-pages" to "21", "session-idle" to "9m", "market-delay" to "30m", "capture-fee-margin-bps" to "2001",
            "max-step-attempts" to "6", "max-requests-per-24h" to "99", "operation-budget.requests" to "1001",
            "operation-budget.timeout" to "90s", "recovery.attempts" to "9", "evaluation-window-sessions" to "61", "min-signals" to "0", "min-net-pnl-sol" to "-0.1",
            "capture-min-signal-sol" to "0",
        ).forEach { (key, value) ->
            assertFailsWith<BindException>("$key=$value") { bind(mapOf(key to value)) }
        }
    }

    private fun bind(values: Map<String, String>): AutomationProperties =
        Binder(MapConfigurationPropertySource(values.mapKeys { "${AutomationProperties.PREFIX}.${it.key}" }))
            .bindOrCreate(AutomationProperties.PREFIX, AutomationProperties::class.java)

    private fun snapshot(properties: AutomationProperties): List<Any?> = listOf(
        properties.zone, properties.pollFrom, properties.pollTo, properties.tickInterval, properties.discoveryInterval,
        properties.initialLookback, properties.maxDiscoveryPages, properties.sessionIdle, properties.marketDelay,
        properties.captureMinSignalSol.stripTrailingZeros(), properties.captureFeeMarginBps, properties.maxStepAttempts,
        properties.maxRequestsPer24h, properties.captureBudget(), properties.captureRecovery(), properties.sweepPlan,
        properties.evaluationWindowSessions, properties.minSignals, properties.minNetPnlSol.signum(),
    )

    private fun failure(values: Map<String, String>): String =
        generateSequence<Throwable>(assertFailsWith<BindException> { bind(values) }) { it.cause }.last().message.orEmpty()

    private companion object {
        const val WALLET = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
    }
}
