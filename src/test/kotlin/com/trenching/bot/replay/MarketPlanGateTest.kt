package com.trenching.bot.replay

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketCaptureWindowParameters
import com.trenching.bot.replay.RecordedWalletFixtures.MINT
import com.trenching.bot.replay.RecordedWalletFixtures.SIGNATURE
import com.trenching.bot.replay.RecordedWalletFixtures.SLOT
import com.trenching.bot.replay.RecordedWalletFixtures.WALLET
import com.trenching.bot.replay.application.MarketPlanGate
import com.trenching.bot.replay.domain.MarketPlanDecision
import com.trenching.bot.walletactivity.WalletHistoryCaptureConfiguration
import com.trenching.bot.walletactivity.WalletHistoryDecodeResult
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper

class MarketPlanGateTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val recorded = WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper)

    @BeforeEach
    fun saveCapture() = RecordedWalletFixtures.save(mapper, directory)

    @Test
    fun `a proven first buy with lead parameters plans exactly the lead window`() {
        val plan = assertIs<MarketPlanDecision.Planned>(MarketPlanGate().plan(target(), MarketCaptureWindowParameters.DEFAULT_LEAD_SLOTS)).plan

        assertEquals(listOf(window(SLOT - 50)), plan.windows)
        assertEquals(MarketCaptureWindowParameters.DEFAULT_LEAD_SLOTS, plan.parameters.leadSlots)
    }

    @Test
    fun `a proven first buy with legacy parameters plans the window after it`() {
        val plan = assertIs<MarketPlanDecision.Planned>(MarketPlanGate().plan(target(), MarketCaptureWindowParameters.LEGACY_LEAD_SLOTS)).plan

        assertEquals(listOf(window(SLOT + 1)), plan.windows)
    }

    @Test
    fun `a first buy too early for its lead is refused as an invalid market window instead of failing the planner`() {
        val early = early(recorded.decode(directory, WalletAddress.of(WALLET)))

        assertEquals(MarketPlanDecision.Refused("MARKET_WINDOW_INVALID"), MarketPlanGate().plan(early, MarketCaptureWindowParameters.DEFAULT_LEAD_SLOTS))
        assertIs<MarketPlanDecision.Planned>(MarketPlanGate().plan(early, MarketCaptureWindowParameters.LEGACY_LEAD_SLOTS))
    }

    private fun early(history: WalletHistoryDecodeResult): WalletHistoryDecodeResult =
        history.copy(transactions = history.transactions.map { it.copy(slot = Slot.of(EARLY_SLOT)) })

    private fun target(): WalletHistoryDecodeResult = recorded.decode(directory, WalletAddress.of(WALLET))
        .copy(targetToken = TargetTokenFixtures.provenEvidence(MINT, SIGNATURE, SLOT))

    private fun window(fromSlot: Long) = MarketCaptureWindow.of(MintAddress.of(MINT), Slot.of(fromSlot), Slot.of(SLOT + 4500),
        TransactionSignature.of(SIGNATURE))

    private companion object {
        /** One slot short of the default lead: the window would start at slot -1. */
        const val EARLY_SLOT = MarketCaptureWindowParameters.DEFAULT_LEAD_SLOTS - 1
    }
}
