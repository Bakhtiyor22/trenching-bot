package com.trenching.bot.walletactivity.domain

import com.trenching.bot.common.TransactionSignature

object WalletEventIdentity {
    fun of(signature: TransactionSignature, instructionIndex: Int): String {
        require(instructionIndex >= 0) { "Negative instruction index" }
        return signature.value + ":" + instructionIndex
    }
}
