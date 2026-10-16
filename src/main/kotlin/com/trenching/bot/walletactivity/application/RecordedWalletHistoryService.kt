package com.trenching.bot.walletactivity.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.*
import com.trenching.bot.walletactivity.domain.OrderedWalletTransactions
import com.trenching.bot.walletactivity.infrastructure.capture.WalletCaptureFormat
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryArtifactStore
import com.trenching.bot.walletactivity.infrastructure.decoder.PumpFunBuyDecoder
import com.trenching.bot.walletactivity.infrastructure.decoder.PumpFunBuyDecoding
import java.nio.file.Path
import org.slf4j.LoggerFactory

internal class RecordedWalletHistoryService(
    private val store: WalletHistoryArtifactStore,
    decoder: PumpFunBuyDecoder,
) : RecordedWalletHistory {
    private val log = LoggerFactory.getLogger(RecordedWalletHistoryService::class.java)
    private val reader = CapturedArtifactReader(store, decoder)
    private val cache = CapturedArtifactsCache()

    /** A token-account capture whose step 1 resolved also has its verification recomputed and checked against its artifacts. */
    override fun inspect(directory: Path): WalletHistoryCaptureResult {
        val verified = store.inspect(directory)
        val manifest = verified.manifest
        if (manifest.targetMint != null && WalletCaptureFormat.recordsResolved(manifest)) {
            targetToken(artifacts(directory, manifest, WalletAddress.of(manifest.wallet)))
        }
        return verified
    }

    /** A token-account capture capped before the end of its history decodes too, with [WalletHistoryDecodeResult.complete] false. */
    override fun decode(directory: Path, wallet: WalletAddress): WalletHistoryDecodeResult {
        val verified = store.inspect(directory)
        if (!WalletCaptureFormat.acquisitionFinished(verified.manifest)) throw CaptureValidationException("CAPTURE_INCOMPLETE")
        if (verified.manifest.wallet != wallet.value) throw CaptureValidationException("WALLET_MISMATCH")
        val artifacts = artifacts(directory, verified.manifest, wallet)
        val manifest = artifacts.manifest
        val decodings = artifacts.decodings
        val records = manifest.records.map { record ->
            WalletHistoryDecodeRecord(TransactionSignature.of(record.signature), Slot.of(record.slot), outcome(record, decodings[record.signature]))
        }
        val decoded = decodings.values.filterIsInstance<PumpFunBuyDecoding.Decoded>()
        val transactions = OrderedWalletTransactions.canonical(decoded.map { it.transaction })
        val buyFacts = decoded.map { it.facts }.sortedWith(compareBy({ it.slot }, { it.signature.value }))
        val rejected = decodings.values.filterIsInstance<PumpFunBuyDecoding.Rejected>()
        val rejectedInstructions = rejected.flatMap { it.instructions }.groupingBy { it }.eachCount().toSortedMap()
        val rejections = rejected.groupingBy { it.reason.name }.eachCount().toSortedMap()
        log.info("wallet_history_decode finished wallet={} selected={} decoded={} unsupportedRoute={} ambiguous={} rejections={}",
            wallet.value, records.size, transactions.size,
            records.count { it.outcome == WalletHistoryDecodeOutcome.UNSUPPORTED_ROUTE },
            records.count { it.outcome == WalletHistoryDecodeOutcome.AMBIGUOUS }, rejections)
        val targetToken = targetToken(artifacts)
        targetToken?.let { log.info("wallet_history_decode target_token mint={} tokenAccount={} tokenAccountFirstBuy={} actualFirstBuy={}",
            it.mint.value, it.tokenAccount, it.tokenAccountFirstBuy, it.actualFirstBuy) }
        return WalletHistoryDecodeResult(verified.directory, wallet, manifest.selection.size, manifest.complete,
            WalletHistoryDecoderVersion.PUMPFUN_BONDING_CURVE_BUY_V2, transactions, records, buyFacts, rejectedInstructions, rejections,
            targetToken)
    }

    /** [manifest] was just verified with every artifact by [WalletHistoryArtifactStore.inspect]. */
    private fun artifacts(directory: Path, manifest: WalletHistoryManifest, wallet: WalletAddress): CapturedArtifacts =
        cache.get(manifest) { reader.read(directory, manifest, wallet) }

    private fun targetToken(artifacts: CapturedArtifacts): TargetTokenEvidence? = artifacts.assessment?.let { assessment ->
        val tokenAccountFirstBuy = assessment.tokenAccountFirstBuy(artifacts.manifest, artifacts.inspected)
        assessment.evidence(tokenAccountFirstBuy, ActualFirstBuyVerification.check(artifacts.manifest, tokenAccountFirstBuy, artifacts.kinds))
    }

    private fun outcome(record: WalletHistoryRecord, decoding: PumpFunBuyDecoding?): WalletHistoryDecodeOutcome = when (record.outcome) {
        WalletHistoryOutcome.UNCLASSIFIED -> when (val decoded = checkNotNull(decoding) { "Verified artifact was not decoded signature=${record.signature}" }) {
            is PumpFunBuyDecoding.Decoded -> WalletHistoryDecodeOutcome.DECODED
            is PumpFunBuyDecoding.Rejected -> decoded.reason.outcome
        }
        WalletHistoryOutcome.NON_SIGNER -> WalletHistoryDecodeOutcome.NOT_SIGNER
        WalletHistoryOutcome.FAILED -> WalletHistoryDecodeOutcome.FAILED
        WalletHistoryOutcome.UNAVAILABLE -> WalletHistoryDecodeOutcome.UNAVAILABLE
        WalletHistoryOutcome.UNSUPPORTED_VERSION -> WalletHistoryDecodeOutcome.UNSUPPORTED_VERSION
        WalletHistoryOutcome.WITHHELD_UNSAFE_TEXT -> WalletHistoryDecodeOutcome.WITHHELD_UNSAFE_TEXT
        WalletHistoryOutcome.WALLET_ABSENT -> WalletHistoryDecodeOutcome.WALLET_ABSENT
        WalletHistoryOutcome.PENDING, WalletHistoryOutcome.PROVIDER_UNAVAILABLE, WalletHistoryOutcome.INVALID_TRANSACTION ->
            throw CaptureValidationException("CAPTURE_INCOMPLETE")
    }
}
