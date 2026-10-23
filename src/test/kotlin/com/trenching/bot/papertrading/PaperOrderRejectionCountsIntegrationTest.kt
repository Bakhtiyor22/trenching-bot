package com.trenching.bot.papertrading

import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.domain.ObservationQuality
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.papertrading.domain.PaperFill
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.strategy.domain.DipWatchOutcome
import com.trenching.bot.strategy.domain.TradeIntent
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import java.math.BigDecimal
import kotlin.test.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Import(PostgresTestDatabaseConfiguration::class)
class PaperOrderRejectionCountsIntegrationTest {
    @Autowired private lateinit var orders: PaperOrderStore

    private val pair = MarketPair.of(MintAddress.of("11111111111111111111111111111112"), MintAddress.WRAPPED_SOL)

    private fun intent(runId: String, offset: Int): TradeIntent =
        TradeIntent("$runId:event:$offset", runId, "event:$offset", pair, "dip-entry", "1", "config-hash", 0,
            Slot.of(100), null, Slot.of(100L + offset), BigDecimal("100.000000000000"), BigDecimal("0.0000001"), BigDecimal("0.000000085"),
            Slot.of(4600), DipWatchOutcome.NO_DIP, null)

    private fun fill(intent: TradeIntent): PaperFill =
        PaperFill(intent.id, intent.targetSlot, intent.targetSlot, "observation-${intent.targetSlot.value}",
            BigDecimal("10.05"), BigDecimal("9.950248756219"), BigDecimal("0.100000000000"),
            BigDecimal("100.100000000000"), 10, 50, "test", ObservationQuality.LAST_TRADE, BigDecimal.ZERO, 0)

    private fun record(runId: String, vararg outcomes: (TradeIntent) -> FillOutcome) {
        val intents = outcomes.indices.map { intent(runId, it + 1) }
        orders.record(runId, intents, intents.zip(outcomes).map { (intent, outcome) -> outcome(intent) })
    }

    private fun rejected(reason: RejectionReason): (TradeIntent) -> FillOutcome = { FillOutcome.Rejected(it.id, reason) }

    @Test
    fun `counts rejected orders by reason across every run and ignores fills`() {
        val before = orders.rejectionCounts()

        record("rejection-counts-run-a", rejected(RejectionReason.NO_DIP),
            rejected(RejectionReason.DIP_BELOW_MIN_MARKET_CAP), { FillOutcome.Filled(fill(it)) })
        record("rejection-counts-run-b", rejected(RejectionReason.NO_DIP),
            rejected(RejectionReason.NO_DIP))

        val after = orders.rejectionCounts()
        val added = RejectionReason.entries.associateWith { (after[it] ?: 0) - (before[it] ?: 0) }
        assertEquals(3, added[RejectionReason.NO_DIP])
        assertEquals(1, added[RejectionReason.DIP_BELOW_MIN_MARKET_CAP])
        assertEquals(0, added.filterKeys { it != RejectionReason.NO_DIP && it != RejectionReason.DIP_BELOW_MIN_MARKET_CAP }
            .values.sum())
    }
}
