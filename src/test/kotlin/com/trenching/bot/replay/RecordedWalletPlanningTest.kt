package com.trenching.bot.replay

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketCaptureWindowParameters
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
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper

class RecordedWalletPlanningTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val recorded = WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper)

    @BeforeEach
    fun saveCapture() = RecordedWalletFixtures.save(mapper, directory)

    @Test
    fun `an existing scope plans one window from the lead before its first buy`() {
        val planned = RecordedWalletPlanning(recorded).plan(directory, LEAD)
        assertEquals(listOf(leadWindow()), planned.plan.windows)
        assertEquals(LEAD, planned.plan.parameters.leadSlots)
        assertNull(planned.history.targetToken)
    }

    @Test
    fun `case anchor resolves the exact wallet instruction that created its window`() {
        val planning = RecordedWalletPlanning(recorded)
        val planned = planning.plan(directory, LEAD)
        val anchor = planning.anchor(planned, planned.plan.windows.single())

        assertEquals(TransactionSignature.of(SIGNATURE), anchor.signature)
        assertEquals(Slot.of(SLOT), anchor.slot)
        assertEquals(planned.history.transactions.single().instructionIndex, anchor.instructionIndex)
    }

    @Test
    fun `a legacy window still anchors with the legacy parameters it was captured with`() {
        val planning = RecordedWalletPlanning(recorded)
        val planned = planning.plan(directory, LEGACY)

        assertEquals(listOf(legacyWindow()), planned.plan.windows)
        assertEquals(TransactionSignature.of(SIGNATURE), planning.anchor(planned, legacyWindow()).signature)
    }

    @Test
    fun `a window paired with the other lead's parameters is a capture mismatch`() {
        val planning = RecordedWalletPlanning(recorded)
        val lead = planning.plan(directory, LEAD)
        val legacy = planning.plan(directory, LEGACY)

        assertMismatch { planning.anchor(lead, legacyWindow()) }
        assertMismatch { planning.anchor(legacy, leadWindow()) }
        assertMismatch { planning.anchor(lead.copy(plan = lead.plan.copy(parameters = legacy.plan.parameters)), leadWindow()) }
        assertMismatch { planning.anchor(legacy.copy(plan = legacy.plan.copy(parameters = lead.plan.parameters)), legacyWindow()) }
    }

    @Test
    fun `a proven first buy equal to the token-account first buy plans one window from it`() {
        val evidence = TargetTokenFixtures.provenEvidence(MINT, SIGNATURE, SLOT)
        val planned = planning(evidence).plan(directory, LEAD)
        assertEquals(listOf(leadWindow()), planned.plan.windows)
        assertEquals(evidence, planned.history.targetToken)
    }

    @Test
    fun `every other first-buy state refuses planning with its reason`() {
        TargetTokenFixtures.refusals(SIGNATURE, SLOT).forEach { (parts, reason) ->
            val refused = assertFailsWith<CaptureValidationException> { planning(TargetTokenFixtures.evidence(MINT, parts)).plan(directory, LEAD) }
            assertEquals(reason, refused.category, parts.toString())
        }
    }

    @Test
    fun `a proven first buy the decoded buys do not start from is a target window mismatch`() {
        val shifted = TargetTokenFixtures.provenEvidence(MINT, SIGNATURE, SLOT + 1)
        val refused = assertFailsWith<CaptureValidationException> { planning(shifted).plan(directory, LEAD) }
        assertEquals("TARGET_WINDOW_MISMATCH", refused.category)
    }

    @Test
    fun `a first buy too early for its lead refuses planning through the logged refusal`() {
        val history = RecordedWalletPlanning(recorded).history(directory)
        val early = history.copy(transactions = history.transactions.map { it.copy(slot = Slot.of(LEAD - 1)) })
        val logger = LoggerFactory.getLogger(RecordedWalletPlanning::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        val refused = try {
            assertFailsWith<CaptureValidationException> { RecordedWalletPlanning(recorded).plan(early, LEAD) }
        } finally {
            logger.detachAppender(appender)
        }

        assertEquals("MARKET_WINDOW_INVALID", refused.category)
        val warning = appender.list.single { it.level == Level.WARN }
        assertEquals("replay_market_plan refused reason=MARKET_WINDOW_INVALID wallet=${history.wallet.value}", warning.formattedMessage)
    }

    private fun planning(evidence: TargetTokenEvidence) = RecordedWalletPlanning(withTarget(recorded, evidence))

    private fun leadWindow() = MarketCaptureWindow.of(MintAddress.of(MINT), Slot.of(SLOT - LEAD), Slot.of(SLOT + 4500),
        TransactionSignature.of(SIGNATURE))

    private fun legacyWindow() = MarketCaptureWindow.of(MintAddress.of(MINT), Slot.of(SLOT + 1), Slot.of(SLOT + 4500),
        TransactionSignature.of(SIGNATURE))

    private fun assertMismatch(action: () -> Unit) =
        assertEquals("MARKET_CAPTURE_MISMATCH", assertFailsWith<CaptureValidationException> { action() }.category)

    internal companion object {
        const val MINT = RecordedWalletFixtures.MINT
        const val SIGNATURE = RecordedWalletFixtures.SIGNATURE
        const val SLOT = RecordedWalletFixtures.SLOT
        const val LEAD = MarketCaptureWindowParameters.DEFAULT_LEAD_SLOTS
        const val LEGACY = MarketCaptureWindowParameters.LEGACY_LEAD_SLOTS

        /** Decodes the recorded capture as a wallet + token capture reporting [evidence]. */
        fun withTarget(recorded: RecordedWalletHistory, evidence: TargetTokenEvidence) = object : RecordedWalletHistory {
            override fun inspect(directory: Path) = recorded.inspect(directory)
            override fun decode(directory: Path, wallet: WalletAddress) = recorded.decode(directory, wallet).copy(targetToken = evidence)
        }
    }
}
