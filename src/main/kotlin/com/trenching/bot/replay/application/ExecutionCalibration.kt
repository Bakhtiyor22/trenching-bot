package com.trenching.bot.replay.application

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.protocol.pumpfun.BondingCurve
import com.trenching.bot.protocol.pumpfun.CurveReserves
import com.trenching.bot.replay.domain.BuyCalibration
import com.trenching.bot.replay.domain.BuyCosts
import com.trenching.bot.replay.domain.CalibrationSummary
import com.trenching.bot.replay.domain.CurveMathCheck
import com.trenching.bot.replay.domain.LatencyCheck
import com.trenching.bot.replay.domain.UnpredictableReason
import com.trenching.bot.walletactivity.PumpFunBuyFacts
import com.trenching.bot.walletactivity.PumpFunBuyInstruction
import java.math.BigDecimal
import java.math.BigInteger

/**
 * The bonding-curve-execution plan's calibration rule, offline and deterministic. Per buy:
 * - **math**: the buy instruction's curve formula and the fees, applied to the reserves before the buy (its post-trade
 *   reserves with its own SOL and token deltas backed out), must reproduce its recorded tokens and fees;
 * - **latency**: the newest captured trade of its mint in a strictly earlier slot predicts its tokens. Trades in one slot
 *   have no recorded order, so the newest is the last in canonical (slot, id) order;
 * - **costs**: the wallet's lamport spend beyond the curve, its fees and the network fee is the "other" residual.
 */
object ExecutionCalibration {
    private const val ERROR_BPS_SCALE = CalibrationMedians.BASIS_POINTS_SCALE

    /** One calibration per buy, ordered by slot, then signature. */
    fun buys(facts: List<PumpFunBuyFacts>, observations: List<MarketObservation>): List<BuyCalibration> {
        val market = OrderedMarketData(observations)
        return facts.sortedWith(compareBy({ it.slot }, { it.signature.value })).map { buy ->
            BuyCalibration(buy.signature, buy.slot, buy.mint, buy.solAmount, buy.instruction, math(buy),
                latency(buy, prior(market, buy.mint, buy.slot)), costs(buy))
        }
    }

    fun summary(buys: List<BuyCalibration>): CalibrationSummary {
        val errors = buys.mapNotNull { (it.latency as? LatencyCheck.Checked)?.errorBps?.abs() }
        val mathExact = buys.count { it.math.exact }
        return CalibrationSummary(
            buys = buys.size,
            mathExact = mathExact,
            mathMismatches = buys.size - mathExact,
            latencyChecked = errors.size,
            noPriorState = buys.count { it.latency == LatencyCheck.NoPriorState },
            unpredictable = buys.count { it.latency is LatencyCheck.Unpredictable },
            medianErrorBps = CalibrationMedians.basisPoints(errors),
            maxErrorBps = errors.maxOrNull(),
            medianTransactionFeeLamports = CalibrationMedians.lamports(buys.map { it.costs.transactionFeeLamports }),
            medianOtherLamports = CalibrationMedians.lamports(buys.map { it.costs.otherLamports }),
            instructions = buys.groupingBy { it.instruction.name }.eachCount().toSortedMap(),
        )
    }

    private fun math(buy: PumpFunBuyFacts): CurveMathCheck {
        val computedTokens = preTradeReserves(buy)?.let { tokensOut(buy.instruction, it, buy.solAmount) }
        return CurveMathCheck(
            tokenAmount = buy.tokenAmount,
            computedTokenAmount = computedTokens,
            feeLamports = buy.feeLamports,
            computedFeeLamports = BondingCurve.fee(buy.solAmount, buy.feeBps),
            creatorFeeLamports = buy.creatorFeeLamports,
            computedCreatorFeeLamports = BondingCurve.fee(buy.solAmount, buy.creatorFeeBps),
        )
    }

    /** The reserves before [buy], or null when backing its deltas out does not leave a valid curve. */
    private fun preTradeReserves(buy: PumpFunBuyFacts): CurveReserves? {
        val post = buy.postReserves
        val virtualSol = post.virtualSol.subtract(buy.solAmount)
        val virtualToken = post.virtualToken.add(buy.tokenAmount)
        val realSol = post.realSol.subtract(buy.solAmount)
        val realToken = post.realToken.add(buy.tokenAmount)
        return if (CurveReserves.isValid(virtualSol, virtualToken, realSol, realToken)) {
            CurveReserves(virtualSol, virtualToken, realSol, realToken)
        } else null
    }

    private fun prior(market: OrderedMarketData, mint: MintAddress, slot: Slot): MarketObservation? {
        if (slot.value == 0L) return null
        return market.observations(MarketPair.of(mint, MintAddress.WRAPPED_SOL), Slot.of(0), Slot.of(slot.value - 1)).lastOrNull()
    }

    private fun latency(buy: PumpFunBuyFacts, prior: MarketObservation?): LatencyCheck {
        if (prior == null) return LatencyCheck.NoPriorState
        val predicted = tokensOut(buy.instruction, prior.curve.reserves, buy.solAmount)
            ?: return LatencyCheck.Unpredictable(prior.id, prior.slot, UnpredictableReason.EXCEEDS_CURVE)
        if (predicted.signum() == 0) return LatencyCheck.Unpredictable(prior.id, prior.slot, UnpredictableReason.ZERO_TOKENS)
        val errorBps = BigDecimal(buy.tokenAmount.subtract(predicted)).multiply(DecimalPolicy.BASIS_POINTS)
            .divide(BigDecimal(predicted), ERROR_BPS_SCALE, CalibrationMedians.ROUNDING)
        return LatencyCheck.Checked(prior.id, prior.slot, predicted, errorBps)
    }

    /** Each buy instruction's own curve rule; both back reserves out by the full SOL amount. */
    private fun tokensOut(instruction: PumpFunBuyInstruction, reserves: CurveReserves, lamportsIn: BigInteger): BigInteger? =
        when (instruction) {
            PumpFunBuyInstruction.BUY -> BondingCurve.buy(reserves, lamportsIn)
            PumpFunBuyInstruction.BUY_EXACT_QUOTE_IN_V3 -> BondingCurve.buyExactQuoteIn(reserves, lamportsIn)
        }?.tokensOut

    private fun costs(buy: PumpFunBuyFacts): BuyCosts {
        val other = buy.walletLamportDelta.negate().subtract(buy.solAmount).subtract(buy.feeLamports)
            .subtract(buy.creatorFeeLamports).subtract(buy.transactionFeeLamports)
        return BuyCosts(buy.transactionFeeLamports, other)
    }
}
