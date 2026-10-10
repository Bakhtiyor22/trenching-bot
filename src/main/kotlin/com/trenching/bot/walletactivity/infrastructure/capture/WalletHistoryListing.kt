package com.trenching.bot.walletactivity.infrastructure.capture

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.Base58
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import com.trenching.bot.protocol.solana.ProgramAddress
import com.trenching.bot.walletactivity.WalletHistoryScope

/** The address whose signature listing a [WalletHistoryScope] scans; capture and inspection both derive it here. */
internal object WalletHistoryListing {
    fun address(scope: WalletHistoryScope, wallet: WalletAddress): String = when (scope) {
        WalletHistoryScope.WALLET_REFERENCES -> wallet.value
        WalletHistoryScope.PUMPFUN_USER_VOLUME_ACCUMULATOR -> {
            val seeds = listOf(PumpFunLayout.USER_VOLUME_ACCUMULATOR_SEED.toByteArray(Charsets.US_ASCII), requireNotNull(Base58.decode(wallet.value)))
            ProgramAddress.find(seeds, PumpFunLayout.PROGRAM_ID)?.address ?: throw CaptureValidationException("PROGRAM_ADDRESS")
        }
    }
}
