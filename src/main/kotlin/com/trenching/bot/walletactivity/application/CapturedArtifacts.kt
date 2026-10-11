package com.trenching.bot.walletactivity.application

import com.trenching.bot.walletactivity.WalletHistoryManifest
import com.trenching.bot.walletactivity.domain.TokenAccountRecordKind
import com.trenching.bot.walletactivity.infrastructure.decoder.PumpFunBuyDecoding

/** [assessment] is set exactly for a token-account capture; the maps are keyed by signature. */
internal data class CapturedArtifacts(
    val manifest: WalletHistoryManifest,
    val decodings: Map<String, PumpFunBuyDecoding>,
    val inspected: Map<String, TokenAccountRecordKind>,
    val kinds: VerificationKinds,
    val assessment: TargetTokenAssessment?,
)
