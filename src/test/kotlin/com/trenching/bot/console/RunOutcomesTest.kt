package com.trenching.bot.console

import com.trenching.bot.console.domain.RunOutcome
import com.trenching.bot.console.domain.RunOutcomeStage
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.domain.CloseReason
import com.trenching.bot.portfolio.domain.PositionOutcomeCounts
import com.trenching.bot.support.StubOutcomeStores
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RunOutcomesTest {
    private val stores = StubOutcomeStores()
    private val outcomes = stores.outcomes()

    @Test
    fun `no run ids make no store call`() {
        assertEquals(emptyMap(), outcomes.of(emptyList()))
        assertTrue(stores.orderCalls.isEmpty() && stores.positionCalls.isEmpty())
    }

    @Test
    fun `every distinct run has an entry, asked of each store once, and a run with nothing stored has none`() {
        stores.rejections["run-1"] = mapOf(RejectionReason.NO_DIP to 1)

        val found = outcomes.of(listOf("run-1", "run-2", "run-1"))

        assertEquals(listOf("run-1", "run-2"), found.keys.toList())
        assertEquals(listOf(RunOutcome("NO_DIP", RunOutcomeStage.NOT_BOUGHT, 1)), found.getValue("run-1"))
        assertEquals(emptyList(), found.getValue("run-2"))
        assertEquals(listOf(listOf("run-1", "run-2")), stores.orderCalls)
        assertEquals(listOf(listOf("run-1", "run-2")), stores.positionCalls)
    }

    @Test
    fun `outcomes are ordered sold, held, not bought, then by count descending and by code`() {
        stores.rejections["run-1"] = mapOf(RejectionReason.NO_DIP to 5, RejectionReason.DIP_BELOW_MIN_MARKET_CAP to 5)
        stores.positionCounts["run-1"] = PositionOutcomeCounts(
            closed = mapOf(CloseReason.WALLET_SOLD to 1, CloseReason.TAKE_PROFIT to 3, CloseReason.STOP_LOSS to 1),
            held = mapOf(RejectionReason.NO_DIP to 2),
        )

        assertEquals(
            listOf(
                RunOutcome("TAKE_PROFIT", RunOutcomeStage.SOLD, 3),
                RunOutcome("STOP_LOSS", RunOutcomeStage.SOLD, 1),
                RunOutcome("WALLET_SOLD", RunOutcomeStage.SOLD, 1),
                RunOutcome("NO_DIP", RunOutcomeStage.HELD, 2),
                RunOutcome("DIP_BELOW_MIN_MARKET_CAP", RunOutcomeStage.NOT_BOUGHT, 5),
                RunOutcome("NO_DIP", RunOutcomeStage.NOT_BOUGHT, 5),
            ),
            outcomes.of("run-1"),
        )
    }
}
