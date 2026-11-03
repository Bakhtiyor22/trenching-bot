package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationHeartbeat
import com.trenching.bot.walletactivity.WalletActivityUnavailable
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.springframework.stereotype.Component

/**
 * The scheduler's last runs and whether a manual discovery pass is queued or running, kept in memory for `/status`; written by
 * the scheduling thread and the requests that queue a pass, read by requests.
 */
@Component
@ConditionalOnAutomationConsole
class AutomationRuntime {
    private val state = AtomicReference(AutomationHeartbeat.NONE)
    private val manualRun = AtomicBoolean(false)

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

    fun manualRunQueued(): Boolean = manualRun.get()

    /** Marks a manual pass queued; false, changing nothing, when one is already queued or running. */
    fun queueManualRun(): Boolean = manualRun.compareAndSet(false, true)

    fun manualRunFinished() {
        manualRun.set(false)
    }
}
