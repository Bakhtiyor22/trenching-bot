package com.trenching.bot.console

import com.trenching.bot.console.ChartFixtures.MINT
import com.trenching.bot.console.ChartFixtures.TRACKED
import com.trenching.bot.console.ChartFixtures.TRADER_A
import com.trenching.bot.console.ChartFixtures.at
import com.trenching.bot.console.application.ChartLimits
import com.trenching.bot.console.application.ChartParameters
import com.trenching.bot.console.application.ChartPosition
import com.trenching.bot.console.domain.ChartQuery
import com.trenching.bot.console.domain.InvalidConsoleRequestException
import com.trenching.bot.common.Base58
import com.trenching.bot.common.MintAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class ChartParametersTest {
    @Test
    fun `a bare query takes the documented defaults`() {
        val parameters = ChartParameters.of(query())

        assertEquals(300, parameters.maxBuckets)
        assertEquals(500, parameters.markerLimit)
        assertNull(parameters.pair)
        assertNull(parameters.cursor)
        assertEquals(emptySet(), parameters.wallets)
    }

    @Test
    fun `limits above the server cap are clamped and below one are refused`() {
        assertEquals(ChartLimits.MAX_BUCKETS, ChartParameters.of(query(maxBuckets = 5_000)).maxBuckets)
        assertEquals(ChartLimits.MAX_MARKER_LIMIT, ChartParameters.of(query(markerLimit = 9_999)).markerLimit)
        assertEquals("maxBuckets", assertFailsWith<InvalidConsoleRequestException> { ChartParameters.of(query(maxBuckets = 0)) }.field)
        assertEquals("markerLimit", assertFailsWith<InvalidConsoleRequestException> { ChartParameters.of(query(markerLimit = -1)) }.field)
    }

    @Test
    fun `from after to is refused and equal bounds are not`() {
        val time = at("2026-10-01T00:00:00Z")

        assertEquals("from", assertFailsWith<InvalidConsoleRequestException> { ChartParameters.of(query(from = time.plusSeconds(1), to = time)) }.field)
        ChartParameters.of(query(from = time, to = time))
    }

    @Test
    fun `the window includes both bounds`() {
        val from = at("2026-10-01T00:00:00Z")
        val parameters = ChartParameters.of(query(from = from, to = from.plusSeconds(10)))

        assertEquals(listOf(false, true, true, false), listOf(from.minusNanos(1), from, from.plusSeconds(10), from.plusSeconds(11)).map(parameters::contains))
    }

    @Test
    fun `wallets must be valid base58 keys and at most twenty distinct`() {
        assertEquals(setOf(TRADER_A, TRACKED), ChartParameters.of(query(wallets = listOf(TRADER_A, TRACKED, TRADER_A))).wallets)
        assertEquals("wallet", assertFailsWith<InvalidConsoleRequestException> { ChartParameters.of(query(wallets = listOf("not-a-wallet"))) }.field)
        val tooMany = (1..21).map { n -> Base58.encode(ByteArray(32) { _ -> n.toByte() }) }
        assertEquals("wallet", assertFailsWith<InvalidConsoleRequestException> { ChartParameters.of(query(wallets = tooMany)) }.field)
        assertEquals(20, ChartParameters.of(query(wallets = tooMany.take(20))).wallets.size)
    }

    @Test
    fun `a pair must be two different mints`() {
        assertEquals("pair", assertFailsWith<InvalidConsoleRequestException> { ChartParameters.of(query(pair = MINT)) }.field)
        assertEquals(MintAddress.of(MINT), ChartParameters.of(query(pair = "$MINT/${MintAddress.WRAPPED_SOL.value}")).pair?.base)
    }

    @Test
    fun `a cursor round trips and a malformed one is refused`() {
        val position = ChartPosition(at("2026-10-01T00:00:01.5Z"), 42, "id:with:colons")

        assertEquals(position, ChartParameters.of(query(cursor = position.encode())).cursor)
        listOf("!!!", "", encoded("v1:1:1"), encoded("v2:1:0:1:x"), encoded("v1:abc:0:1:x"), encoded("v1:1:0:1:"),
            encoded("v1:1:2000000000:1:x")).forEach { cursor ->
            assertEquals("cursor", assertFailsWith<InvalidConsoleRequestException> { ChartParameters.of(query(cursor = cursor)) }.field, cursor)
        }
    }

    private fun encoded(text: String): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    private fun query(
        pair: String? = null,
        from: java.time.Instant? = null,
        to: java.time.Instant? = null,
        maxBuckets: Int? = null,
        markerLimit: Int? = null,
        wallets: List<String> = emptyList(),
        cursor: String? = null,
        tickLimit: Int? = null,
    ) = ChartQuery(pair, from, to, maxBuckets, markerLimit, wallets, cursor, tickLimit)
}
