package com.trenching.bot.console.automation.config

import com.trenching.bot.common.LogDurations
import com.trenching.bot.common.LogLine
import com.trenching.bot.console.automation.domain.DiscoveryMode
import com.trenching.bot.console.config.SolanaRpcProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Says once, when the application is ready, what the automation will do with the settings it runs under, so a reader of the journal
 * knows without opening the configuration. Only whether an RPC URL is set is said, never the URL, which may carry a key.
 */
@Component
@ConditionalOnAutomationConsole
class AutomationStartupLog(
    private val properties: AutomationProperties,
    private val rpc: SolanaRpcProperties,
) {
    private val log = LoggerFactory.getLogger(AutomationStartupLog::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun logSettings() {
        if (!properties.enabled) {
            LogLine("Automation is off: no buys are discovered or captured.").field("enabled", false).info(log)
            return
        }
        val mode = properties.discoveryMode()
        val sentence = when (mode) {
            DiscoveryMode.Always -> LogLine("Automation is on: discovery scans every {} at all hours, and capture is {}.", LogDurations.compact(properties.discoveryInterval), capture())
            is DiscoveryMode.Window -> LogLine(
                "Automation is on: discovery scans every {} between {} and {} ({}), and capture is {}.",
                LogDurations.compact(properties.discoveryInterval), properties.pollFrom, properties.pollTo, properties.zone, capture(),
            )
        }
        sentence.field("enabled", true).field("captureEnabled", properties.captureEnabled).field("wallet", properties.wallet)
            .field("discovery", mode.label.lowercase()).field("discoveryInterval", LogDurations.compact(properties.discoveryInterval))
            .field("pollFrom", properties.pollFrom.takeIf { mode is DiscoveryMode.Window }).field("pollTo", properties.pollTo.takeIf { mode is DiscoveryMode.Window })
            .field("zone", properties.zone).field("tickInterval", LogDurations.compact(properties.tickInterval))
            .field("maxRequestsPer24h", properties.maxRequestsPer24h).field("backfillReserveRequests", properties.backfillReserveRequests)
            .field("marketDelay", LogDurations.compact(properties.marketDelay)).field("marketMaxTransactions", properties.marketMaxTransactions)
            .field("maxStepAttempts", properties.maxStepAttempts).field("sweepPlan", properties.sweepPlan)
            .field("rpcConfigured", !rpc.rpcUrl.isNullOrBlank()).info(log)
    }

    private fun capture(): String = if (properties.captureEnabled) "on" else "off, so buys are only found"
}
