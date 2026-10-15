package com.trenching.bot.console.capture

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.console.mapper.CaptureResponseMapperImpl
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketHistoryCaptureResult
import com.trenching.bot.marketdata.MarketHistoryDecodeResult
import com.trenching.bot.marketdata.MarketObservationExtractorVersion
import com.trenching.bot.marketdata.MarketWindowCoverage
import com.trenching.bot.marketdata.MarketWindowCutoff
import com.trenching.bot.marketdata.MarketWindowDecodeRecord
import com.trenching.bot.replay.ReplayMarketFixtures
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.Test

class CaptureInspectionMapperTest {
    private val root = Path.of("market")
    private val mapper = CaptureInspectionMapper(CaptureResponseMapperImpl())
    private val decidedWindow = MarketCaptureWindow.of(MintAddress.of(CaptureTokenFixtures.MINT), Slot.of(101), Slot.of(4600), TransactionSignature.of("1".repeat(64)))
    private val fullWindow = MarketCaptureWindow.of(MintAddress.of(CaptureConsoleFixture.WALLET), Slot.of(201), Slot.of(4700), TransactionSignature.of("1".repeat(63) + "2"))
    private val manifest = ReplayMarketFixtures.manifest(listOf(decidedWindow, fullWindow)).let { manifest ->
        manifest.copy(windows = listOf(manifest.windows[0].copy(cutoff = MarketWindowCutoff(140, "TAKE_PROFIT", "engine-v1", "hash")), manifest.windows[1]))
    }
    private val decoded = MarketHistoryDecodeResult(root, true, MarketObservationExtractorVersion.PUMPFUN_BONDING_CURVE_TRADE_V2, ReplayMarketFixtures.PARAMETERS,
        listOf(MarketWindowDecodeRecord(decidedWindow, MarketWindowCoverage.COMPLETE, 0, 0, true, 5, Slot.of(140)),
            MarketWindowDecodeRecord(fullWindow, MarketWindowCoverage.COMPLETE, 0, 0, true, 7)),
        emptyList(), emptyList(), emptyMap())

    @Test
    fun `the market summary shows how far each window was observed and where a decided window stopped`() {
        val summary = mapper.map(CaptureTokenFixtures.exportReady().copy(marketCapture = MarketHistoryCaptureResult(root, manifest), marketDecoded = decoded)).marketSummary

        assertEquals("140", summary["window.0.observedThrough"])
        assertEquals("140", summary["window.0.cutoffThroughSlot"])
        assertEquals("TAKE_PROFIT", summary["window.0.cutoffOutcome"])
        assertEquals("4700", summary["window.1.observedThrough"])
        assertFalse("window.1.cutoffThroughSlot" in summary)
        assertFalse("window.1.cutoffOutcome" in summary)
    }

    @Test
    fun `a decided window shows its cutoff while the capture is still being acquired`() {
        val summary = mapper.map(CaptureTokenFixtures.exportReady().copy(marketCapture = MarketHistoryCaptureResult(root, manifest))).marketSummary

        assertEquals("140", summary["window.0.cutoffThroughSlot"])
        assertEquals("TAKE_PROFIT", summary["window.0.cutoffOutcome"])
        assertFalse("window.0.observedThrough" in summary)
    }
}
