package com.trenching.bot.replay.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.marketdata.*
import com.trenching.bot.replay.*
import com.trenching.bot.walletactivity.*
import java.nio.file.Path
import java.time.Instant

internal class CaptureInspectionService(
    private val wallets: RecordedWalletHistory,
    private val markets: RecordedMarketHistory,
    private val rates: RecordedReferenceRates,
) {
    fun inspect(paths: CapturePaths): CaptureInspection {
        var walletCapture: WalletHistoryCaptureResult? = null
        var walletDecoded: WalletHistoryDecodeResult? = null
        val wallet = verified {
            walletCapture = wallets.inspect(paths.wallet)
            val captured = requireNotNull(walletCapture)
            if (!captured.manifest.complete) CaptureStageSummary(CaptureStageState.INCOMPLETE, "WALLET_CAPTURE_INCOMPLETE", canResume = true)
            else {
                walletDecoded = wallets.decode(paths.wallet, WalletAddress.of(captured.manifest.wallet))
                if (ReplayMarketWindowPlanner().plan(requireNotNull(walletDecoded)).windows.isEmpty())
                    CaptureStageSummary(CaptureStageState.UNUSABLE, "NO_SOURCE_BUYS")
                else CaptureStageSummary(CaptureStageState.READY)
            }
        }
        val walletReady = wallet.state == CaptureStageState.READY
        var marketCapture: MarketHistoryCaptureResult? = null
        var marketDecoded: MarketHistoryDecodeResult? = null
        val market = if (paths.market == null) absent(walletReady, "WALLET_CAPTURE_NOT_READY") else verified {
            marketCapture = markets.inspect(paths.market)
            val captured = requireNotNull(marketCapture)
            if (captured.manifest.acquisitionComplete) marketDecoded = markets.decode(paths.market)
            val decoded = marketDecoded
            val overCap = captured.manifest.windows.any { it.coverage == MarketWindowCoverage.OVER_CAP }
            when {
                !walletReady -> CaptureStageSummary(CaptureStageState.INCOMPLETE, "WALLET_CAPTURE_NOT_READY")
                overCap -> CaptureStageSummary(CaptureStageState.UNUSABLE, "OVER_CAP", requiresNewAttempt = true)
                !captured.manifest.acquisitionComplete -> CaptureStageSummary(CaptureStageState.INCOMPLETE, "MARKET_CAPTURE_INCOMPLETE", canResume = true)
                decoded == null -> CaptureStageSummary(CaptureStageState.INCOMPLETE, "MARKET_CAPTURE_INCOMPLETE")
                else -> marketReady(requireNotNull(walletDecoded), decoded)
            }
        }.let { if (it.state == CaptureStageState.NOT_STARTED) absent(walletReady, "WALLET_CAPTURE_NOT_READY") else it }
        val marketReady = market.state == CaptureStageState.READY
        var rateCapture: ReferenceRateCaptureResult? = null
        val rateSummary = if (paths.rates == null) absent(marketReady, "MARKET_CAPTURE_NOT_READY") else verified {
            rateCapture = rates.inspect(paths.rates)
            val captured = requireNotNull(rateCapture)
            val decoded = marketDecoded
            when {
                !marketReady || decoded == null -> CaptureStageSummary(CaptureStageState.INCOMPLETE, "MARKET_CAPTURE_NOT_READY")
                captured.manifest.records.map { Instant.parse(it.minute) }.toSet() != decoded.observations.map { ReferenceRate.minuteOf(it.observedTime) }.toSet() ->
                    CaptureStageSummary(CaptureStageState.INVALID, "RESUME_IDENTITY", requiresNewAttempt = true)
                !captured.manifest.complete -> CaptureStageSummary(CaptureStageState.INCOMPLETE, "REFERENCE_RATES_INCOMPLETE", canResume = true)
                else -> CaptureStageSummary(CaptureStageState.READY)
            }
        }.let { if (it.state == CaptureStageState.NOT_STARTED) absent(marketReady, "MARKET_CAPTURE_NOT_READY") else it }
        return CaptureInspection(wallet, market, rateSummary, marketReady, marketReady && rateSummary.state == CaptureStageState.READY,
            walletCapture, walletDecoded, marketCapture, marketDecoded, rateCapture)
    }

    private fun marketReady(wallet: WalletHistoryDecodeResult, market: MarketHistoryDecodeResult): CaptureStageSummary {
        val plan = ReplayMarketWindowPlanner().plan(wallet)
        if (market.windowParameters != plan.parameters || market.windows.map { it.window }.toSet() != plan.windows.toSet())
            return CaptureStageSummary(CaptureStageState.INVALID, "MARKET_CAPTURE_MISMATCH", requiresNewAttempt = true)
        if (market.windows.any { it.coverage != MarketWindowCoverage.COMPLETE })
            return CaptureStageSummary(CaptureStageState.INCOMPLETE, "MARKET_CAPTURE_INCOMPLETE", canResume = true)
        if (!market.complete || market.windows.any { !it.yieldsObservations })
            return CaptureStageSummary(CaptureStageState.UNUSABLE, "MARKET_CAPTURE_INCOMPLETE", requiresNewAttempt = true)
        if (market.observations.isEmpty()) return CaptureStageSummary(CaptureStageState.UNUSABLE, "NO_OBSERVATIONS", requiresNewAttempt = true)
        RecordedMarketVerification.requireMatching(plan, market)
        return CaptureStageSummary(CaptureStageState.READY)
    }

    private fun absent(allowed: Boolean, reason: String): CaptureStageSummary =
        CaptureStageSummary(CaptureStageState.NOT_STARTED, if (allowed) null else reason, canStart = allowed)

    private fun verified(block: () -> CaptureStageSummary): CaptureStageSummary = try { block() }
    catch (exception: CaptureValidationException) {
        if (exception.category == "MANIFEST_MISSING") CaptureStageSummary(CaptureStageState.NOT_STARTED, canStart = true)
        else CaptureStageSummary(CaptureStageState.INVALID, exception.category)
    }
}
