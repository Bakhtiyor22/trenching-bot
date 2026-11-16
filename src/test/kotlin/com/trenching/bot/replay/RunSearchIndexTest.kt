package com.trenching.bot.replay

import com.trenching.bot.common.MintAddress
import com.trenching.bot.replay.domain.IndexedTrade
import com.trenching.bot.replay.domain.RunSearchIndex
import java.math.BigInteger
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class RunSearchIndexTest {
    @Test
    fun `the anchor is the tracked wallet's earliest successful supported buy in canonical order`() {
        val index = RunSearchIndex.of(WALLET, listOf(
            trade(slot = 20, signature = "b", time = "2026-11-10T08:00:00Z", base = MINT_B),
            trade(slot = 10, signature = "z", time = "2026-11-10T07:30:00Z", base = MINT_A),
            trade(slot = 10, signature = "a", time = "2026-11-10T07:00:00Z", base = MINT_B),
        ), emptyList())

        assertEquals(Instant.parse("2026-11-10T07:00:00Z"), index.anchorBlockTime)
    }

    @Test
    fun `sells, failed, unsupported and other wallets' trades are never the anchor`() {
        val index = RunSearchIndex.of(WALLET, listOf(
            trade(slot = 1, delta = BigInteger.ONE.negate()),
            trade(slot = 2, successful = false),
            trade(slot = 3, supported = false),
            trade(slot = 4, wallet = "someone-else"),
        ), listOf(MINT_A))

        assertNull(index.anchorBlockTime)
        assertEquals(setOf(MINT_A), index.mints)
    }

    @Test
    fun `mints are the dataset's pair bases plus the anchor buy's mint`() {
        val index = RunSearchIndex.of(WALLET, listOf(trade(slot = 1, base = MINT_B)), listOf(MINT_A))

        assertEquals(setOf(MINT_A, MINT_B), index.mints)
    }

    @Test
    fun `an anchor buy without a block time gives no anchor time`() {
        assertNull(RunSearchIndex.of(WALLET, listOf(trade(slot = 1, time = null)), emptyList()).anchorBlockTime)
    }

    private fun trade(
        slot: Long, signature: String = "sig", time: String? = "2026-11-10T07:00:00Z", base: MintAddress = MINT_A,
        delta: BigInteger = BigInteger.TEN, successful: Boolean = true, supported: Boolean = true, wallet: String = WALLET,
    ) = IndexedTrade(wallet, slot, signature, 0, time?.let(Instant::parse), base, delta, successful, supported)

    private companion object {
        const val WALLET = "11111111111111111111111111111111"
        val MINT_A: MintAddress = MintAddress.of("11111111111111111111111111111112")
        val MINT_B: MintAddress = MintAddress.of("11111111111111111111111111111113")
    }
}
