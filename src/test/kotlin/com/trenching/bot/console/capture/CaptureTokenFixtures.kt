package com.trenching.bot.console.capture

import com.trenching.bot.common.Base58
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.domain.CloseReason
import com.trenching.bot.replay.CaptureInspection
import com.trenching.bot.replay.CaptureStageState
import com.trenching.bot.replay.CaptureStageSummary
import com.trenching.bot.replay.domain.CaptureVerdict
import com.trenching.bot.replay.domain.CaptureVerdictStage
import com.trenching.bot.replay.domain.CaptureVerdictState
import com.trenching.bot.replay.domain.CaseClassification
import com.trenching.bot.replay.domain.DipVerdict
import com.trenching.bot.replay.domain.EntryVerdict
import com.trenching.bot.replay.domain.ExitVerdict
import com.trenching.bot.replay.domain.ReplayVerdict
import com.trenching.bot.replay.domain.TargetTokenVerdict
import com.trenching.bot.replay.domain.VerdictProvenance
import com.trenching.bot.replay.domain.VerdictReasons
import com.trenching.bot.walletactivity.ActualFirstBuy
import com.trenching.bot.walletactivity.TargetTokenEvidence
import com.trenching.bot.walletactivity.TokenAccountFirstBuy
import com.trenching.bot.walletactivity.VerificationListing
import com.trenching.bot.walletactivity.WalletHistoryCaptureResult
import com.trenching.bot.walletactivity.WalletHistoryManifest
import com.trenching.bot.walletactivity.WalletHistoryScope
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant

/** Wallet + token evidence, manifests and verdicts built directly, the way the replay contract reports them. */
object CaptureTokenFixtures {
    val MINT: String = Base58.encode(ByteArray(32) { 7 })
    val BUY: TransactionSignature = signature(1)
    val CREATION: TransactionSignature = signature(2)
    const val BUY_SLOT = 1000L
    const val CREATION_SLOT = 900L
    val CREATION_TIME: Instant = Instant.parse("2026-10-01T00:00:00Z")

    val proven = ActualFirstBuy.Proven(BUY, Slot.of(BUY_SLOT), 0, CREATION, Slot.of(CREATION_SLOT), CREATION_TIME,
        mapOf(VerificationListing.PUMP_ACCUMULATOR to Slot.of(CREATION_SLOT - 1), VerificationListing.PUMPSWAP_ACCUMULATOR to Slot.of(CREATION_SLOT - 1)))

    fun evidence(actual: ActualFirstBuy = proven, tokenAccount: TokenAccountFirstBuy = TokenAccountFirstBuy.Found(BUY, Slot.of(BUY_SLOT), 0)) =
        TargetTokenEvidence(MintAddress.of(MINT), "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb", "TokenAccount111111111111111111111111111111",
            "PumpAccumu1ator1111111111111111111111111111", "PumpSwapAccumu1ator11111111111111111111111", tokenAccount, actual)

    fun manifest(scope: WalletHistoryScope, mint: String? = null, limit: Int = 100, scanPages: Int = 5) = WalletHistoryManifest(
        schemaVersion = if (scope == WalletHistoryScope.WALLET_TOKEN_ACCOUNT) 4 else 3, wallet = CaptureConsoleFixture.WALLET, scope = scope,
        genesisHash = "genesis", commitment = "confirmed", capturedAt = "2026-10-10T09:00:00Z", limit = limit, scanPageLimit = scanPages,
        selection = emptyList(), records = emptyList(), complete = true, discoveryComplete = true, discoveryStop = null, scannedSignatures = 0,
        skippedFailed = 0, scanCursor = null, requestsUsed = 0, bytesUsed = 0, targetMint = mint)

    private val ready = CaptureStageSummary(CaptureStageState.READY)

    /** Every stage ready and export allowed, with the wallet manifest of [scope]. */
    fun exportReady(scope: WalletHistoryScope = WalletHistoryScope.WALLET_TOKEN_ACCOUNT) = CaptureInspection(ready, ready, ready,
        canCalibrate = true, canExport = true,
        walletCapture = WalletHistoryCaptureResult(Path.of("wallet"), manifest(scope, MINT.takeIf { scope == WalletHistoryScope.WALLET_TOKEN_ACCOUNT })),
        targetToken = evidence().takeIf { scope == WalletHistoryScope.WALLET_TOKEN_ACCOUNT })

    fun inspectionOnly(classification: CaseClassification = CaseClassification.INCOMPLETE_CAPTURE, datasetExported: Boolean = false) = TargetTokenVerdict(
        classification,
        if (datasetExported) CaptureVerdict(CaptureVerdictState.COMPLETE, CaptureVerdictStage.EXPORT, VerdictReasons.QUALIFICATION_PENDING)
        else CaptureVerdict(CaptureVerdictState.INCOMPLETE, CaptureVerdictStage.EXPORT, VerdictReasons.NOT_EXPORTED), null, null,
        DipVerdict.NotEvaluated(VerdictReasons.REPLAY_NOT_RUN), null, null, ReplayVerdict.NotRun, VerdictProvenance())

    val ranProvenance = VerdictProvenance("checksum", "run-1", "config-hash", "dip-paper-v1", BigDecimal("0.000000025"), BUY, Slot.of(BUY_SLOT))

    val takeProfit = TargetTokenVerdict(CaseClassification.QUALIFYING_DIP, CaptureVerdict(CaptureVerdictState.COMPLETE), null, null,
        DipVerdict.Qualified(Slot.of(1002), BigDecimal("1.23E-8"), BigDecimal("4100.50"), BigDecimal("152.31"), "observation-1002"),
        EntryVerdict.Filled(Slot.of(1003), BigDecimal("0.0000000125"), "observation-1003"),
        ExitVerdict.Closed(CloseReason.TAKE_PROFIT, Slot.of(1006), BigDecimal("0.012500000")), ReplayVerdict.Ran, ranProvenance)

    val notFilledOpen = takeProfit.copy(entry = EntryVerdict.NotFilled(RejectionReason.entries.first()),
        exit = ExitVerdict.Open(RejectionReason.entries.last()))

    val replayFailed = TargetTokenVerdict(CaseClassification.REPLAY_FAILED, CaptureVerdict(CaptureVerdictState.COMPLETE), null, null,
        DipVerdict.NotEvaluated(VerdictReasons.REPLAY_FAILED), null, null, ReplayVerdict.Failed(VerdictReasons.DATASET_INVALID), VerdictProvenance())

    private fun signature(index: Int): TransactionSignature =
        TransactionSignature.of(Base58.encode(ByteArray(64).also { it[62] = 1; it[63] = index.toByte() }))
}
