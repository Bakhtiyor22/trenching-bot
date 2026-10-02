package com.trenching.bot.walletactivity.domain

object OrderedWalletTransactions {
    fun canonical(transactions: List<WalletTransaction>): List<WalletTransaction> =
        transactions.groupBy { WalletEventIdentity.of(it.signature, it.instructionIndex) }
            .map { (id, copies) ->
                require(copies.distinct().size == 1) { "Conflicting source event id=$id" }
                copies.first()
            }.sortedWith(compareBy({ it.slot.value }, { it.signature.value }, { it.instructionIndex }))
}
