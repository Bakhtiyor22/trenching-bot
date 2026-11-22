package com.trenching.bot.console.automation.config

import ch.qos.logback.classic.Level
import com.trenching.bot.console.automation.AutomationFixtures.properties
import com.trenching.bot.console.automation.LogCapture
import com.trenching.bot.console.config.SolanaRpcProperties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** The line that states the automation's effective settings once at startup, and never the RPC URL. */
class AutomationStartupLogTest {
    private val secretUrl = "https://rpc.example.test/?api-key=SECRET-KEY-VALUE"

    private fun logged(settings: AutomationProperties, rpc: SolanaRpcProperties = SolanaRpcProperties(secretUrl)): List<String> =
        LogCapture(AutomationStartupLog::class.java.name, Level.DEBUG).use { logs ->
            AutomationStartupLog(settings, rpc).logSettings()
            logs.events.also { events -> assertTrue(events.all { it.level == Level.INFO }, events.toString()) }.map { it.formattedMessage }
        }

    @Test
    fun `the settings are said in words and then as keys, with whether an RPC URL is set but never the URL`() {
        val message = logged(properties(captureEnabled = true)).single()

        assertTrue(message.startsWith("Automation is on: discovery scans every 10m between 18:00 and 05:00 (+05:00), and capture is on. "), message)
        assertTrue(message.contains("enabled=true captureEnabled=true wallet="), message)
        assertTrue(message.contains(" discovery=window discoveryInterval=10m pollFrom=18:00 pollTo=05:00 zone=+05:00 tickInterval=1m "), message)
        assertTrue(message.endsWith(" marketDelay=35m marketMaxTransactions=4000 maxStepAttempts=2 sweepPlan=${properties().sweepPlan} rpcConfigured=true"), message)
        assertTrue(message.contains("maxRequestsPer24h=7500 backfillReserveRequests=2600"), message)
        assertFalse(message.contains("SECRET-KEY-VALUE") || message.contains("https://") || message.contains("api-key"), message)
    }

    @Test
    fun `discovery that scans at all hours has no window keys, and phase 0 says buys are only found`() {
        val message = logged(properties(pollAlways = true), SolanaRpcProperties()).single()

        assertTrue(message.startsWith("Automation is on: discovery scans every 10m at all hours, and capture is off, so buys are only found. "), message)
        assertTrue(message.contains(" discovery=always "), message)
        assertFalse(message.contains("pollFrom"), message)
        assertTrue(message.endsWith("rpcConfigured=false"), message)
    }

    @Test
    fun `with automation off the line says so and nothing else`() {
        val message = logged(AutomationProperties(enabled = false)).single()

        assertEquals("Automation is off: no buys are discovered or captured. enabled=false", message)
    }
}
