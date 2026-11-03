package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationHeartbeat
import com.trenching.bot.walletactivity.WalletActivityUnavailable
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import org.springframework.stereotype.Component

/** The scheduler's last runs, kept in memory for `/status`; written by the scheduling thread, read by requests. */
@Component
@ConditionalOnAutomationConsole
class AutomationRuntime {
    private val state = AtomicReference(AutomationHeartbeat.NONE)

    fun heartbeat(): AutomationHeartbeat = state.get()

    fun ticked(at: Instant) {
        state.updateAndGet { it.copy(lastTickAt = at) }
    }

    fun discovered(at: Instant) {
        state.updateAndGet { it.copy(lastDiscoveryAt = at, discoveryProblem = null) }
    }

    fun discoveryUnavailable(reason: WalletActivityUnavailable) {
        state.updateAndGet { it.copy(discoveryProblem = reason) }
    }
}
