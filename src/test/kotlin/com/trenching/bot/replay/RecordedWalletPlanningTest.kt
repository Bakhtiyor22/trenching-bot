package com.trenching.bot.replay

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.replay.application.RecordedWalletPlanning
import com.trenching.bot.walletactivity.RecordedWalletHistory
import com.trenching.bot.walletactivity.TargetTokenEvidence
import com.trenching.bot.walletactivity.WalletHistoryCaptureConfiguration
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper

class RecordedWalletPlanningTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val recorded = WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper)

    @BeforeEach
    fun saveCapture() = RecordedWalletFixtures.save(mapper, directory)

    @Test
    fun `an existing scope plans exactly as before`() {
        val planned = RecordedWalletPlanning(recorded).plan(directory)
        assertEquals(listOf(window(SLOT)), planned.plan.windows)
        assertNull(planned.history.targetToken)
    }

    @Test
    fun `case anchor resolves the exact wallet instruction that created its window`() {
        val planning = RecordedWalletPlanning(recorded)
        val planned = planning.plan(directory)
        val anchor = planning.anchor(planned, planned.plan.windows.single())

        assertEquals(TransactionSignature.of(SIGNATURE), anchor.signature)
        assertEquals(Slot.of(SLOT), anchor.slot)
        assertEquals(planned.history.transactions.single().instructionIndex, anchor.instructionIndex)
    }

    @Test
    fun `a proven first buy equal to the token-account first buy plans one window from it`() {
        val evidence = TargetTokenFixtures.provenEvidence(MINT, SIGNATURE, SLOT)
        val planned = planning(evidence).plan(directory)
        assertEquals(listOf(window(SLOT)), planned.plan.windows)
        assertEquals(evidence, planned.history.targetToken)
    }

    @Test
    fun `every other first-buy state refuses planning with its reason`() {
        TargetTokenFixtures.refusals(SIGNATURE, SLOT).forEach { (parts, reason) ->
            val refused = assertFailsWith<CaptureValidationException> { planning(TargetTokenFixtures.evidence(MINT, parts)).plan(directory) }
            assertEquals(reason, refused.category, parts.toString())
        }
    }

    @Test
    fun `a proven first buy the decoded buys do not start from is a target window mismatch`() {
        val shifted = TargetTokenFixtures.provenEvidence(MINT, SIGNATURE, SLOT + 1)
        val refused = assertFailsWith<CaptureValidationException> { planning(shifted).plan(directory) }
        assertEquals("TARGET_WINDOW_MISMATCH", refused.category)
    }

    private fun planning(evidence: TargetTokenEvidence) = RecordedWalletPlanning(withTarget(recorded, evidence))

    private fun window(slot: Long) = MarketCaptureWindow.of(MintAddress.of(MINT), Slot.of(slot + 1), Slot.of(slot + 4500),
        TransactionSignature.of(SIGNATURE))

    internal companion object {
        const val MINT = RecordedWalletFixtures.MINT
        const val SIGNATURE = RecordedWalletFixtures.SIGNATURE
        const val SLOT = RecordedWalletFixtures.SLOT

        /** Decodes the recorded capture as a wallet + token capture reporting [evidence]. */
        fun withTarget(recorded: RecordedWalletHistory, evidence: TargetTokenEvidence) = object : RecordedWalletHistory {
            override fun inspect(directory: Path) = recorded.inspect(directory)
            override fun decode(directory: Path, wallet: WalletAddress) = recorded.decode(directory, wallet).copy(targetToken = evidence)
        }
    }
}
