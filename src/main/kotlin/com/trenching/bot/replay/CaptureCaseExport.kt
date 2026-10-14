package com.trenching.bot.replay

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.marketdata.MarketCaseId
import com.trenching.bot.marketdata.MarketCaptureWindowParameters
import com.trenching.bot.marketdata.MarketObservationExtractorVersion
import java.nio.file.Path

data class CaptureCaseExportRequest(
    val walletCaptureDirectory: Path,
    val marketCaptureDirectory: Path,
    val ratesCaptureDirectory: Path,
    val caseId: MarketCaseId,
    val wallet: WalletAddress,
    val mint: MintAddress,
    val anchorSignature: TransactionSignature,
    val anchorSlot: Long,
    val instructionIndex: Int,
    val fromSlot: Long,
    val toSlot: Long,
    val windowParameters: MarketCaptureWindowParameters,
    val output: Path,
)

/** v3 dataset result plus case provenance that the immutable v3 file format does not carry. */
data class CaptureCaseExport(
    val output: Path,
    val checksum: String,
    val caseId: MarketCaseId,
    val trackedWallet: WalletAddress,
    val mint: MintAddress,
    val anchorSignature: TransactionSignature,
    val anchorSlot: Long,
    val instructionIndex: Int,
    val fromSlot: Long,
    val toSlot: Long,
    val windowParameters: MarketCaptureWindowParameters,
    val extractor: MarketObservationExtractorVersion,
    val transactions: Int,
    val observations: Int,
    val written: Boolean,
)
