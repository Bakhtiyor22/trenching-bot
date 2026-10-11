package com.trenching.bot.walletactivity.infrastructure.decoder

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.WalletAddress
import java.math.BigInteger
import tools.jackson.databind.JsonNode

internal sealed interface WalletTokenBalanceChange {
    data class Measured(val delta: BigInteger, val decimals: Int) : WalletTokenBalanceChange
    data class Unproven(val reason: PumpFunBuyRejection) : WalletTokenBalanceChange
}

internal object WalletTokenBalances {
    private val U64_DIGITS = Regex("[0-9]{1,20}")
    private val MAX_U64: BigInteger = BigInteger.ONE.shiftLeft(Long.SIZE_BITS).subtract(BigInteger.ONE)
    private const val MAX_DECIMALS = 255

    /**
     * Sums post minus pre over the wallet-owned token accounts of [mint]. An account absent from the pre
     * balances was created in the transaction and starts at zero; one absent from the post balances is unproven.
     */
    fun change(meta: JsonNode, wallet: WalletAddress, mint: MintAddress): WalletTokenBalanceChange {
        val pre = owned(meta.path("preTokenBalances"), wallet, mint)
            ?: return WalletTokenBalanceChange.Unproven(PumpFunBuyRejection.MALFORMED_EVIDENCE)
        val post = owned(meta.path("postTokenBalances"), wallet, mint)
            ?: return WalletTokenBalanceChange.Unproven(PumpFunBuyRejection.MALFORMED_EVIDENCE)
        if (post.isEmpty() || !post.keys.containsAll(pre.keys)) return WalletTokenBalanceChange.Unproven(PumpFunBuyRejection.MISSING_BALANCE)
        val decimals = (pre.values + post.values).map { it.decimals }.distinct().singleOrNull()
            ?: return WalletTokenBalanceChange.Unproven(PumpFunBuyRejection.DECIMALS_MISMATCH)
        return WalletTokenBalanceChange.Measured(post.values.sumOf { it.amount } - pre.values.sumOf { it.amount }, decimals)
    }

    /**
     * Post minus pre balance of [mint] in the token account at [accountIndex]. An account absent from the pre balances counts
     * from zero; one absent from the post balances, or malformed evidence, gives null.
     */
    fun accountDelta(meta: JsonNode, accountIndex: Int, mint: MintAddress): BigInteger? {
        val pre = ofMint(meta.path("preTokenBalances"), mint) { true } ?: return null
        val post = ofMint(meta.path("postTokenBalances"), mint) { true } ?: return null
        val after = post[accountIndex] ?: return null
        return after.amount - (pre[accountIndex]?.amount ?: BigInteger.ZERO)
    }

    private fun owned(balances: JsonNode, wallet: WalletAddress, mint: MintAddress): Map<Int, TokenBalance>? =
        ofMint(balances, mint) { isText(it.path("owner"), wallet.value) }

    private fun ofMint(balances: JsonNode, mint: MintAddress, include: (JsonNode) -> Boolean): Map<Int, TokenBalance>? {
        if (balances.isMissingNode || balances.isNull) return emptyMap()
        if (!balances.isArray) return null
        val parsed = balances.filter { isText(it.path("mint"), mint.value) && include(it) }
            .map { balance -> parse(balance) ?: return null }
        return if (parsed.distinctBy { it.accountIndex }.size == parsed.size) parsed.associateBy { it.accountIndex } else null
    }

    private fun parse(node: JsonNode): TokenBalance? {
        val accountIndex = node.path("accountIndex")
        val amount = node.path("uiTokenAmount").path("amount")
        val decimals = node.path("uiTokenAmount").path("decimals")
        if (!accountIndex.isIntegralNumber || !accountIndex.canConvertToInt() ||
            !amount.isString || !U64_DIGITS.matches(amount.asString()) ||
            !decimals.isIntegralNumber || !decimals.canConvertToInt() || decimals.asInt() !in 0..MAX_DECIMALS) return null
        val raw = BigInteger(amount.asString())
        return if (raw > MAX_U64) null else TokenBalance(accountIndex.asInt(), raw, decimals.asInt())
    }

    private fun isText(node: JsonNode, expected: String): Boolean = node.isString && node.asString() == expected
}

private data class TokenBalance(val accountIndex: Int, val amount: BigInteger, val decimals: Int)
