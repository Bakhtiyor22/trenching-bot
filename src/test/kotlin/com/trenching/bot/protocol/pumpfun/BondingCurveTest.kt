package com.trenching.bot.protocol.pumpfun

import com.trenching.bot.common.DecimalPolicy
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class BondingCurveTest {
    private val mapper = JsonMapper.builder().build()

    @ParameterizedTest
    @ValueSource(strings = ["/marketdata/pumpfun-trade-453004997.json", "/walletactivity/decoder/pumpfun-buy.json"])
    fun `a captured buy's pre-trade reserves and SOL amount reproduce its tokens and both fees exactly`(resource: String) {
        val event = tradeEvent(resource)
        assertEquals(BUY, event.isBuy)

        assertEquals(CurveBuy(event.tokenAmount), BondingCurve.buy(preTrade(event), event.solAmount))
        assertEquals(event.feeLamports, BondingCurve.fee(event.solAmount, event.feeBasisPoints))
        assertEquals(event.creatorFeeLamports, BondingCurve.fee(event.solAmount, event.creatorFeeBasisPoints))
    }

    @Test
    fun `buy and sell round down like the program`() {
        val reserves = reserves(virtualSol = 100, virtualToken = 1000, realSol = 50, realToken = 500)

        assertEquals(CurveBuy(BigInteger.valueOf(90)), BondingCurve.buy(reserves, BigInteger.TEN))
        assertEquals(CurveSell(BigInteger.valueOf(8)), BondingCurve.sell(reserves, BigInteger.valueOf(90)))
    }

    @Test
    fun `fees round up to the next lamport`() {
        assertEquals(BigInteger.ONE, BondingCurve.fee(BigInteger.ONE, BigInteger.ONE))
        assertEquals(BigInteger.valueOf(95), BondingCurve.fee(BigInteger.valueOf(10_000), BigInteger.valueOf(95)))
        assertEquals(BigInteger.valueOf(96), BondingCurve.fee(BigInteger.valueOf(10_001), BigInteger.valueOf(95)))
        assertEquals(BigInteger.ZERO, BondingCurve.fee(BigInteger.ZERO, BigInteger.valueOf(95)))
        assertEquals(BigInteger.ZERO, BondingCurve.fee(BigInteger.valueOf(10_001), BigInteger.ZERO))
    }

    @Test
    fun `u64 maximum reserves and inputs do not overflow`() {
        val max = BigInteger.ONE.shiftLeft(Long.SIZE_BITS).subtract(BigInteger.ONE)
        val reserves = CurveReserves(max, max, max, max)
        val half = max.shiftRight(1)

        assertEquals(CurveBuy(half), BondingCurve.buy(reserves, max))
        assertEquals(CurveSell(half), BondingCurve.sell(reserves, max))
        assertEquals(max, BondingCurve.fee(max, BigInteger.valueOf(BondingCurve.MAX_BASIS_POINTS.toLong())))
    }

    @Test
    fun `a buy beyond the real token reserves and a sell beyond the real SOL reserves have no result`() {
        assertNull(BondingCurve.buy(reserves(100, 1000, 50, 89), BigInteger.TEN))
        assertEquals(CurveBuy(BigInteger.valueOf(90)), BondingCurve.buy(reserves(100, 1000, 50, 90), BigInteger.TEN))
        assertNull(BondingCurve.sell(reserves(100, 1000, 7, 500), BigInteger.valueOf(90)))
        assertEquals(CurveSell(BigInteger.valueOf(8)), BondingCurve.sell(reserves(100, 1000, 8, 500), BigInteger.valueOf(90)))
    }

    @Test
    fun `a zero or negative input has no result`() {
        val reserves = reserves(100, 1000, 50, 500)

        assertNull(BondingCurve.buy(reserves, BigInteger.ZERO))
        assertNull(BondingCurve.buy(reserves, BigInteger.ONE.negate()))
        assertNull(BondingCurve.sell(reserves, BigInteger.ZERO))
        assertNull(BondingCurve.sell(reserves, BigInteger.ONE.negate()))
    }

    @Test
    fun `selling the tokens just bought returns less SOL than was paid`() {
        val event = tradeEvent("/marketdata/pumpfun-trade-453004997.json")
        val before = preTrade(event)
        val bought = requireNotNull(BondingCurve.buy(before, event.solAmount)).tokensOut
        val after = CurveReserves(before.virtualSol + event.solAmount, before.virtualToken - bought,
            before.realSol + event.solAmount, before.realToken - bought)

        val sold = requireNotNull(BondingCurve.sell(after, bought)).lamportsOut

        assertTrue(sold < event.solAmount, "sold=$sold paid=${event.solAmount}")
    }

    @Test
    fun `spot price is SOL per whole token at the price scale`() {
        val initial = CurveReserves(BigInteger("30000000000"), BigInteger("1073000000000000"), BigInteger.ZERO, BigInteger("793100000000000"))

        val price = BondingCurve.spotPrice(initial)

        assertEquals(DecimalPolicy.PRICE_SCALE, price.scale())
        assertEquals(BigDecimal("30").divide(BigDecimal("1073000000"), DecimalPolicy.PRICE_SCALE, DecimalPolicy.ROUNDING), price)
    }

    @Test
    fun `reserves that break the curve's invariants are refused`() {
        assertFailsWith<IllegalArgumentException> { reserves(0, 1000, 0, 500) }
        assertFailsWith<IllegalArgumentException> { reserves(100, 0, 50, 0) }
        assertFailsWith<IllegalArgumentException> { reserves(100, 1000, 101, 500) }
        assertFailsWith<IllegalArgumentException> { reserves(100, 1000, 50, 1001) }
        assertFailsWith<IllegalArgumentException> { reserves(100, 1000, -1, 500) }
        assertFailsWith<IllegalArgumentException> {
            CurveReserves(BigInteger.ONE.shiftLeft(Long.SIZE_BITS), BigInteger.TEN, BigInteger.ZERO, BigInteger.ZERO)
        }
    }

    /** A buy moved [PumpFunInstruction.TradeEvent.solAmount] into both SOL reserves and its tokens out of both token reserves. */
    private fun preTrade(event: PumpFunInstruction.TradeEvent) = CurveReserves(
        virtualSol = event.virtualSolReserves - event.solAmount,
        virtualToken = event.virtualTokenReserves + event.tokenAmount,
        realSol = event.realSolReserves - event.solAmount,
        realToken = event.realTokenReserves + event.tokenAmount,
    )

    private fun tradeEvent(resource: String): PumpFunInstruction.TradeEvent {
        val root = javaClass.getResourceAsStream(resource).use { mapper.readTree(requireNotNull(it)) }
        val transaction = if (root.has("result")) root.path("result") else root
        return PumpFunInstructionReader.read(transaction).filterIsInstance<PumpFunInstruction.TradeEvent>().single()
    }

    private fun reserves(virtualSol: Long, virtualToken: Long, realSol: Long, realToken: Long) = CurveReserves(
        BigInteger.valueOf(virtualSol), BigInteger.valueOf(virtualToken), BigInteger.valueOf(realSol), BigInteger.valueOf(realToken))

    private companion object {
        const val BUY = 1
    }
}
