package com.trenching.bot.walletactivity.infrastructure.capture

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.protocol.pumpfun.VolumeAccumulatorAddress
import com.trenching.bot.protocol.solana.TokenAccountAddress
import com.trenching.bot.walletactivity.WalletHistoryScope

/** The addresses a [WalletHistoryScope] lists or checks; capture, inspection and decoding all derive them here. */
internal object WalletHistoryListing {
    /** [token] is required for [WalletHistoryScope.WALLET_TOKEN_ACCOUNT] and ignored otherwise. */
    fun address(scope: WalletHistoryScope, wallet: WalletAddress, token: TokenTarget?): String = when (scope) {
        WalletHistoryScope.WALLET_REFERENCES -> wallet.value
        WalletHistoryScope.PUMPFUN_USER_VOLUME_ACCUMULATOR -> pumpAccumulator(wallet)
        WalletHistoryScope.WALLET_TOKEN_ACCOUNT -> tokenAccount(wallet, checkNotNull(token) { "Token scope without a target wallet=${wallet.value}" })
    }

    fun tokenAccount(wallet: WalletAddress, token: TokenTarget): String =
        derived(TokenAccountAddress.associated(wallet.value, token.tokenProgram, token.mint.value))

    fun pumpAccumulator(wallet: WalletAddress): String = derived(VolumeAccumulatorAddress.pump(wallet.value))

    fun pumpSwapAccumulator(wallet: WalletAddress): String = derived(VolumeAccumulatorAddress.pumpSwap(wallet.value))

    private fun derived(address: String?): String = address ?: throw CaptureValidationException("PROGRAM_ADDRESS")
}
