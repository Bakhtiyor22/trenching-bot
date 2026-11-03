package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.SweepCycle
import com.trenching.bot.papertrading.PaperOrderStore
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.papertrading.domain.orderId
import com.trenching.bot.portfolio.PositionStore
import com.trenching.bot.strategy.TradeIntentStore
import com.trenching.bot.strategy.domain.TradeIntent
import com.trenching.bot.walletactivity.WalletEventStore
import org.springframework.stereotype.Service

/**
 * Reads the cycle of an item's buy from a stored run, through the module store ports the console's run detail also reads.
 * A wallet + token dataset normally holds one cycle, the buy's own; among several, the one started by the buy's transaction.
 */
@Service
@ConditionalOnAutomationConsole
class AutomationRunCycles(
    private val intents: TradeIntentStore,
    private val orders: PaperOrderStore,
    private val positions: PositionStore,
    private val events: WalletEventStore,
) {
    /** The cycle of the buy signed [signature] in run [runId], or null when the run holds none. */
    fun cycle(runId: String, signature: String): SweepCycle? {
        val cycles = intents.intents(runId)
        val intent = cycles.singleOrNull() ?: ofBuy(runId, cycles, signature) ?: return null
        val outcome = checkNotNull(orders.outcomes(runId).firstOrNull { it.orderId == intent.id }) {
            "Missing paper order runId=$runId intentId=${intent.id}"
        }
        return when (outcome) {
            is FillOutcome.Rejected -> SweepCycle.Skipped(outcome.reason)
            is FillOutcome.Filled -> {
                val stored = checkNotNull(positions.storedPositions(runId).firstOrNull { it.position.intentId == intent.id }) {
                    "Missing position runId=$runId intentId=${intent.id}"
                }
                SweepCycle.Entered(stored.position, stored.exitUnavailable)
            }
        }
    }

    private fun ofBuy(runId: String, cycles: List<TradeIntent>, signature: String): TradeIntent? {
        if (cycles.isEmpty()) return null
        val buyEvents = events.acceptedTrades(runId).filter { it.signature.value == signature }.map { it.id }.toSet()
        return cycles.firstOrNull { it.sourceEventId in buyEvents }
    }
}
