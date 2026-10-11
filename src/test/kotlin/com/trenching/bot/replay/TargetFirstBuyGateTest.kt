package com.trenching.bot.replay

import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.replay.domain.ActualFirstBuyClaim
import com.trenching.bot.replay.domain.TargetFirstBuyAssessment
import com.trenching.bot.replay.domain.TargetFirstBuyGate
import com.trenching.bot.walletactivity.ActualFirstBuy
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class TargetFirstBuyGateTest {
    @Test
    fun `only a proven actual first buy equal to the token-account first buy passes`() {
        val evidence = TargetTokenFixtures.provenEvidence(RecordedWalletFixtures.MINT, RecordedWalletFixtures.SIGNATURE, SLOT)
        assertEquals(TargetFirstBuyAssessment.Proven(evidence.actualFirstBuy as ActualFirstBuy.Proven), TargetFirstBuyGate.assess(evidence))
    }

    @Test
    fun `every other first-buy state is refused with its reason`() {
        TargetTokenFixtures.refusals(RecordedWalletFixtures.SIGNATURE, SLOT).forEach { (parts, reason) ->
            val assessment = TargetFirstBuyGate.assess(TargetTokenFixtures.evidence(RecordedWalletFixtures.MINT, parts))
            assertEquals(reason, (assessment as TargetFirstBuyAssessment.Refused).reason.name, parts.toString())
        }
    }

    @Test
    fun `the coverage claim names the creation and the coverage date exactly`() {
        val creation = TransactionSignature.of(RecordedWalletFixtures.SIGNATURE)
        val proven = ActualFirstBuy.Proven(creation, Slot.of(SLOT), 0, creation, Slot.of(452_000_000), Instant.parse("2026-10-01T00:00:00Z"),
            emptyMap())
        assertEquals("Actual first Pump.fun curve buy proven for documented routes, for a mint created after 2025-08-01 11:31 UTC " +
            "(creation ${creation.value} at slot 452000000; scanned Pump and PumpSwap accumulators to below 452000000)",
            ActualFirstBuyClaim.of(proven))
    }

    private companion object {
        const val SLOT = RecordedWalletFixtures.SLOT
    }
}
