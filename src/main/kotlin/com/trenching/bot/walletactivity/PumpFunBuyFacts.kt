package com.trenching.bot.walletactivity

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.protocol.pumpfun.CurveReserves
import java.math.BigInteger

/**
 * What a decoded Pump.fun buy paid, in raw units, for checking the execution model against it. The amounts, fees, basis
 * points and [postReserves] come from the buy's `TradeEvent`; the basis points are the event's raw u64 values.
 * [transactionFeeLamports] is the network fee (`meta.fee`) and [walletLamportDelta] the signer wallet's post minus pre
 * lamport balance, so it is negative for a buy.
 */
data class PumpFunBuyFacts(
    val signature: TransactionSignature,
    val slot: Slot,
    val mint: MintAddress,
    val solAmount: BigInteger,
    val tokenAmount: BigInteger,
    val feeLamports: BigInteger,
    val creatorFeeLamports: BigInteger,
    val feeBps: BigInteger,
    val creatorFeeBps: BigInteger,
    val postReserves: CurveReserves,
    val transactionFeeLamports: BigInteger,
    val walletLamportDelta: BigInteger,
    /** Which buy instruction paid; each prices the curve by its own rule, per the pumpfun-exact-quote-in-v3-buys ADR. */
    val instruction: PumpFunBuyInstruction,
)

/** The Pump.fun buy instructions the decoder supports. */
enum class PumpFunBuyInstruction { BUY, BUY_EXACT_QUOTE_IN_V3 }
