package com.trenching.bot.walletactivity

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.walletactivity.WalletActivityFixtures.signature
import com.trenching.bot.walletactivity.domain.TradeSide
import com.trenching.bot.walletactivity.domain.WalletActivitySignals
import java.math.BigInteger
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class WalletActivitySignalsTest {
    @Test
    fun `only a curve payment strictly above 1_9 SOL is over the signal threshold`() {
        val readings = listOf("1899999999", "1900000000", "1900000001").map { WalletActivitySignals.overSignalThreshold(action(lamports = BigInteger(it))) }

        assertEquals(listOf(false, false, true), readings)
    }

    @Test
    fun `an action has an earlier one when the same mint traded at a lower slot in the page`() {
        val newer = action(seed = 1, slot = 300, mint = MINT_A)
        val other = action(seed = 2, slot = 200, mint = MINT_B)
        val older = action(seed = 3, slot = 100, mint = MINT_A)
        val page = listOf(newer, other, older)

        assertEquals(listOf(true, false, false), page.map { WalletActivitySignals.hasEarlierActionInPage(it, page) })
    }

    @Test
    fun `within one transaction the earlier instruction is the older action, and other transactions of the same slot are not ordered`() {
        val first = action(seed = 1, slot = 100, mint = MINT_A, instruction = 1, inner = 2)
        val second = action(seed = 1, slot = 100, mint = MINT_A, instruction = 1, inner = 5)
        val sameSlot = action(seed = 2, slot = 100, mint = MINT_A, instruction = 0, inner = null)
        val page = listOf(first, second, sameSlot)

        assertEquals(listOf(false, true, false), page.map { WalletActivitySignals.hasEarlierActionInPage(it, page) })
    }

    @Test
    fun `the page order is slot descending, then signature, instruction and inner index`() {
        val a = action(seed = 1, slot = 100, instruction = 2, inner = 1)
        val b = action(seed = 1, slot = 100, instruction = 2, inner = null)
        val c = action(seed = 1, slot = 100, instruction = 1, inner = 4)
        val d = action(seed = 2, slot = 200)
        val shuffled = listOf(a, b, c, d)

        val ordered = shuffled.sortedWith(WalletActivitySignals.PAGE_ORDER)

        assertEquals(listOf(d, c, b, a), ordered, "a top-level event sorts before the inner events of its instruction")
    }

    private fun action(seed: Int = 1, slot: Long = 100, mint: String = MINT_A, instruction: Int = 0, inner: Int? = 0, lamports: BigInteger = BigInteger.ONE) = WalletAction(
        signature = TransactionSignature.of(signature(seed)),
        instructionIndex = instruction,
        innerIndex = inner,
        slot = Slot.of(slot),
        blockTime = null,
        side = TradeSide.BUY,
        mint = MintAddress.of(mint),
        solLamports = lamports,
        tokenRaw = BigInteger.TEN,
    )

    private companion object {
        const val MINT_A = WalletActivityFixtures.BUY_MINT
        const val MINT_B = WalletActivityFixtures.V3_MINT
    }
}
