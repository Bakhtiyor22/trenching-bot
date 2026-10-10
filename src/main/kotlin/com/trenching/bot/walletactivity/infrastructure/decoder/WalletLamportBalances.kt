package com.trenching.bot.walletactivity.infrastructure.decoder

import com.trenching.bot.common.WalletAddress
import java.math.BigInteger
import tools.jackson.databind.JsonNode

/** The SOL a transaction cost its signer, read from `meta`; null when the evidence does not prove it. */
internal object WalletLamportBalances {
    /** The wallet's post minus pre lamport balance, at the wallet's position among the transaction's account keys. */
    fun delta(transaction: JsonNode, wallet: WalletAddress): BigInteger? {
        val accountKeys = transaction.path("transaction").path("message").path("accountKeys")
        val indexes = accountKeys.withIndex().filter { (_, key) -> key.path("pubkey").let { it.isString && it.asString() == wallet.value } }
        val index = indexes.singleOrNull()?.index ?: return null
        val meta = transaction.path("meta")
        val pre = lamports(meta.path("preBalances").path(index)) ?: return null
        val post = lamports(meta.path("postBalances").path(index)) ?: return null
        return post.subtract(pre)
    }

    fun transactionFee(transaction: JsonNode): BigInteger? = lamports(transaction.path("meta").path("fee"))

    private fun lamports(node: JsonNode): BigInteger? =
        if (node.isIntegralNumber) node.bigIntegerValue().takeIf { it.signum() >= 0 } else null
}
