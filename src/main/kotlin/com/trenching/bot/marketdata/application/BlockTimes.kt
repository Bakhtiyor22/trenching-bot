package com.trenching.bot.marketdata.application

import com.trenching.bot.chaincapture.CaptureBudget
import com.trenching.bot.chaincapture.CaptureBudgetException
import com.trenching.bot.chaincapture.CaptureProviderException
import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.chaincapture.ChainCaptureReads
import com.trenching.bot.chaincapture.SolanaReadRpc
import java.time.Instant

/**
 * One capture invocation's block time reads through [ChainCaptureReads.blockTime], within its [budget]. Each slot's time is
 * read once and kept, so a settled window reuses the time its cutoff already asked for; a failed or empty read is not kept,
 * so a later need asks again.
 */
internal class BlockTimes(private val rpc: SolanaReadRpc, private val budget: CaptureBudget) {
    private val known = HashMap<Long, Instant>()

    /**
     * The block time of the nearest produced block at or below [slot]. A block time is optional evidence: when the provider
     * fails or answers malformed, the budget is spent or no time exists, [unavailable] receives the category and the answer is
     * null.
     */
    fun at(slot: Long, unavailable: (category: String) -> Unit): Instant? {
        known[slot]?.let { return it }
        val category = try {
            ChainCaptureReads.blockTime(rpc, slot, budget)?.let { time ->
                known[slot] = time
                return time
            }
            NO_BLOCK_TIME
        } catch (exception: CaptureProviderException) {
            exception.category
        } catch (exception: CaptureBudgetException) {
            exception.category
        } catch (exception: CaptureValidationException) {
            exception.category
        }
        unavailable(category)
        return null
    }

    private companion object {
        /** The provider answered, but has no time for the block. */
        const val NO_BLOCK_TIME = "NO_BLOCK_TIME"
    }
}
