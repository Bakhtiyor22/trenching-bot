package com.trenching.bot.support

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.TokenAmount
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.ObservedTrade
import com.trenching.bot.marketdata.domain.TradeSide
import com.trenching.bot.protocol.pumpfun.CurveReserves
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.walletactivity.domain.WalletTransaction
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * The bundled sample shaped for `SimulationConfig.agreed()`: a 20% dip and +55% / -40% exits, compared on post-trade prices,
 * following the wallet's sells. Every sample curve's spot price equals its trade's average to within 1e-17 SOL per token, so
 * [PRICES] sets the trades that meet the agreed levels exactly, as both, keeping every trigger slot. Each mint's first wallet
 * buy is also recorded in its own slot as the tracked wallet's trade, whose post-trade price is the buy's own P0 (1e-7 SOL per
 * token, 1e-8 for the low cap token), so the post-trade reference and the minimum market cap are judged exactly as the average
 * reference was. That recorded trade pays [SIGNAL_CURVE_PAYMENT] into the curve, so every buy passes spec P3's 1.9 SOL
 * minimum; the payment does not move its price. The sample's traders never include the tracked wallet, so no wallet sell is
 * recorded.
 */
object AgreedDipSample {
    /**
     * The dips at exactly 20% below P0 (0.00000008) for every cycle that enters (the stop-loss cycle's spot is otherwise 8e-19
     * above it); the take-profit crossings at about 0.00000014, above +55% of each entry's execution price; and the stop-loss
     * trigger at 0.00000004, below -40%. The fills and sales use other trades' curves, so the sample's hand-checked P&L still
     * holds, except for the standing-curve sale, which is made against the reshaped take-profit curve itself
     * ([STANDING_CURVE_PNL]).
     */
    val PRICES: Map<String, String> = mapOf(
        "fixture:TP:1002:0" to "0.00000008", "fixture:TP:1005:0" to "0.00000014",
        "fixture:SL:2001:0" to "0.00000008", "fixture:SL:2004:0" to "0.00000004",
        "fixture:MIGRATED_POS:6001:0" to "0.00000008",
        "fixture:HORIZON:7001:0" to "0.00000008",
        "fixture:NOEXIT:8001:0" to "0.00000008", "fixture:NOEXIT:8003:0" to "0.00000014",
        "fixture:NOFILL:9001:0" to "0.00000008",
    )

    /**
     * The [DipSample.NO_EXIT_FILL] cycle's take profit sold against slot 8003's reshaped curve (62151427980 virtual lamports,
     * 443938771285715 virtual raw tokens): its 1188191190525 raw tokens return 165902732 lamports less 1576076 + 497709 in fees
     * (95 and 30 bps rounding up) and the 0.001 transaction cost, against the 0.10225 cost basis.
     */
    const val STANDING_CURVE_PNL = "0.060578947000"

    /** The v4 twin shaped for the agreed configuration. */
    fun dataset(): ReplayDataset = shape(DipSampleV4.dataset())

    /** 2 SOL: with its 95 and 30 basis point fees the buy pays 2.025 SOL. */
    val SIGNAL_CURVE_PAYMENT: BigInteger = BigInteger.valueOf(2_000_000_000)

    /** [dataset], one of the sample's v4 to v6 twins, with each first buy recorded paying [curvePayment] and [prices] set. */
    fun shape(dataset: ReplayDataset, prices: Map<String, String> = PRICES, curvePayment: BigInteger = SIGNAL_CURVE_PAYMENT): ReplayDataset {
        val firstBuys = dataset.transactions.groupBy { it.pair }.values
            .map { buys -> buys.minWith(compareBy({ it.slot.value }, { it.signature.value }, { it.instructionIndex })) }
        val priced = dataset.observations.map { observation -> prices[observation.id]?.let { atSpot(observation, it) } ?: observation }
        return dataset.copy(observations = priced + firstBuys.map { buyObservation(dataset, it, curvePayment) })
    }

    /**
     * [observation] with [spot] as its average and its post-trade price: the curve's virtual token reserve is moved, rounding up
     * so the spot is never above [spot], and the real token reserve with it.
     */
    fun atSpot(observation: MarketObservation, spot: String): MarketObservation {
        val reserves = observation.curve.reserves
        val virtualToken = BigDecimal(reserves.virtualSol).divide(BigDecimal(spot).movePointRight(LAMPORTS_PER_RAW_TOKEN_DIGITS), 0, RoundingMode.CEILING)
            .toBigIntegerExact()
        val realToken = virtualToken.subtract(reserves.virtualToken.subtract(reserves.realToken))
        return observation.copy(price = BigDecimal(spot),
            curve = observation.curve.copy(reserves = CurveReserves(reserves.virtualSol, virtualToken, reserves.realSol, realToken)))
    }

    /** [buy] as the market data records it: the mint's first open-curve trade moved to the buy's slot and time, signed by its wallet. */
    private fun buyObservation(dataset: ReplayDataset, buy: WalletTransaction, curvePayment: BigInteger): MarketObservation {
        val template = dataset.observations.filter { it.pair == buy.pair && !it.curve.complete }.minWith(compareBy({ it.slot.value }, { it.id }))
        val sourceEventId = "${buy.signature.value}:${buy.instructionIndex}:-"
        val p0 = DecimalPolicy.swapPrice(TokenAmount.of(buy.quoteDelta.negate(), buy.quoteDecimals), TokenAmount.of(buy.baseDelta, buy.baseDecimals))
        val recorded = template.copy(id = "${PumpFunLayout.TRADE_SOURCE}:$sourceEventId:${buy.pair.base.value}", sourceEventId = sourceEventId,
            slot = buy.slot, priceSlot = buy.slot, observedTime = buy.blockTime ?: template.observedTime, curveComplete = false,
            trade = ObservedTrade.of(buy.wallet.value, TradeSide.BUY, curvePayment, buy.baseDelta))
        return atSpot(recorded, p0.stripTrailingZeros().toPlainString())
    }

    /** SOL per whole token is lamports per raw token times 10^(token decimals − SOL decimals) = 10^-3. */
    private const val LAMPORTS_PER_RAW_TOKEN_DIGITS = PumpFunLayout.SOL_AMOUNT_DECIMALS - PumpFunLayout.TOKEN_DECIMALS
}
