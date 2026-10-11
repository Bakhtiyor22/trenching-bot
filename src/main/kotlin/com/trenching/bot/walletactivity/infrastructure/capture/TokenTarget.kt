package com.trenching.bot.walletactivity.infrastructure.capture

import com.trenching.bot.common.MintAddress
import com.trenching.bot.walletactivity.WalletHistoryManifest

/** The mint a token-account capture targets and the token program that owns it. */
internal data class TokenTarget(val mint: MintAddress, val tokenProgram: String) {
    companion object {
        /** Null for every scope without a target; [MintAddress.of] refuses a malformed mint. */
        fun of(manifest: WalletHistoryManifest): TokenTarget? = manifest.targetMint?.let { mint ->
            TokenTarget(MintAddress.of(mint), checkNotNull(manifest.tokenProgram) { "Target mint without a token program mint=$mint" })
        }
    }
}
