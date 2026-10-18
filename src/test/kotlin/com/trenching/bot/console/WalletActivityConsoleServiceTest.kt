package com.trenching.bot.console

import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.console.application.WalletActivityCache
import com.trenching.bot.console.application.WalletActivityConsoleService
import com.trenching.bot.console.capture.MutableClock
import com.trenching.bot.console.domain.CaptureReference
import com.trenching.bot.console.domain.CapturedTokens
import com.trenching.bot.console.domain.InvalidConsoleRequestException
import com.trenching.bot.console.domain.InvalidWalletAddressException
import com.trenching.bot.console.domain.WalletActivityUnavailableException
import com.trenching.bot.walletactivity.WalletAction
import com.trenching.bot.walletactivity.WalletActivityFixtures
import com.trenching.bot.walletactivity.WalletActivityLookup
import com.trenching.bot.walletactivity.WalletActivityPage
import com.trenching.bot.walletactivity.WalletActivityRequest
import com.trenching.bot.walletactivity.WalletActivityResult
import com.trenching.bot.walletactivity.WalletActivityUnavailable
import com.trenching.bot.walletactivity.domain.TradeSide
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class WalletActivityConsoleServiceTest {
    private val clock = MutableClock(Instant.parse("2026-10-17T10:00:00Z"))
    private val asked = mutableListOf<Pair<WalletActivityRequest, ApiUsageContext>>()
    private var answer: WalletActivityResult = WalletActivityResult.Found(page())
    private val lookup = WalletActivityLookup { request, usage -> asked += request to usage; answer }
    private val captured = mutableMapOf<String, List<CaptureReference>>()

    @Test
    fun `asks the lookup for twenty transactions by default, attributed to wallet activity`() {
        service().activity(WALLET, limit = null, before = null, refresh = false)

        assertEquals(listOf(WalletActivityRequest(WalletAddress.of(WALLET), 20, null) to ApiUsageContext(ApiTask.WALLET_ACTIVITY, null)), asked)
    }

    @Test
    fun `a repeat within sixty seconds is served from the cache with its time, and refresh reads again`() {
        val service = service()
        val first = service.activity(WALLET, 20, null, refresh = false)
        clock.now = clock.now.plusSeconds(59)
        val cached = service.activity(WALLET, 20, null, refresh = false)
        val refreshed = service.activity(WALLET, 20, null, refresh = true)
        clock.now = clock.now.plusSeconds(1)
        val afterRefresh = service.activity(WALLET, 20, null, refresh = false)

        assertNull(first.cachedAt)
        assertEquals(Instant.parse("2026-10-17T10:00:00Z"), cached.cachedAt)
        assertNull(refreshed.cachedAt)
        assertEquals(Instant.parse("2026-10-17T10:00:59Z"), afterRefresh.cachedAt, "refresh stores what it read")
        assertEquals(2, asked.size)
    }

    @Test
    fun `a page served from the cache reports no request used, and a refresh reports the requests it sent`() {
        val service = service()
        val first = service.activity(WALLET, 20, null, refresh = false)
        val cached = service.activity(WALLET, 20, null, refresh = false)
        val refreshed = service.activity(WALLET, 20, null, refresh = true)

        assertEquals(listOf(2, 0, 2), listOf(first, cached, refreshed).map { it.page.requestsUsed })
    }

    @Test
    fun `an entry expires after sixty seconds and the cache key is wallet, limit and before`() {
        val service = service()
        service.activity(WALLET, 20, null, refresh = false)
        service.activity(WALLET, 21, null, refresh = false)
        service.activity(WALLET, 20, OTHER_SIGNATURE, refresh = false)
        clock.now = clock.now.plusSeconds(60)
        val expired = service.activity(WALLET, 20, null, refresh = false)

        assertNull(expired.cachedAt)
        assertEquals(listOf(20 to null, 21 to null, 20 to OTHER_SIGNATURE, 20 to null), asked.map { (request, _) -> request.limit to request.before?.value })
    }

    @Test
    fun `annotates each action with its signal reading, earlier action and the captures holding its mint`() {
        captured[MINT_A] = listOf(CaptureReference("capture-1", "Decu · 7mup…pump · 16 Oct 20:13"))
        answer = WalletActivityResult.Found(page(
            action(seed = 1, slot = 300, mint = MINT_A, lamports = "1900000001"),
            action(seed = 2, slot = 200, mint = MINT_B, lamports = "1900000000"),
            action(seed = 3, slot = 100, mint = MINT_A),
        ))

        val view = service().activity(WALLET, 20, null, refresh = false)

        assertEquals(listOf(true, false, false), view.actions.map { it.overSignalThreshold })
        assertEquals(listOf(true, false, false), view.actions.map { it.earlierActionInPage })
        assertEquals(setOf("CURVE_PAYMENT_EXCLUDING_FEES"), view.actions.map { it.signalBasis }.toSet())
        assertEquals(listOf(listOf("capture-1"), emptyList(), listOf("capture-1")), view.actions.map { action -> action.capturedIn.map { it.captureId } })
    }

    @Test
    fun `a cached page is annotated with the captures as they are now`() {
        val service = service()
        service.activity(WALLET, 20, null, refresh = false)
        captured[MINT_A] = listOf(CaptureReference("capture-2", "Fresh"))

        val cached = service.activity(WALLET, 20, null, refresh = false)

        assertEquals(listOf("capture-2"), cached.actions.single().capturedIn.map { it.captureId })
    }

    @Test
    fun `without the capture console no action is marked captured`() {
        val view = WalletActivityConsoleService(lookup, WalletActivityCache(clock), CapturedTokens.NONE).activity(WALLET, 20, null, refresh = false)

        assertEquals(emptyList(), view.actions.single().capturedIn)
    }

    @Test
    fun `an unavailable lookup is raised with its reason and retry time and is never cached`() {
        answer = WalletActivityResult.Unavailable(WalletActivityUnavailable.PROVIDER_RATE_LIMITED, Duration.ofMillis(2500))
        val service = service()

        val failure = assertFailsWith<WalletActivityUnavailableException> { service.activity(WALLET, 20, null, refresh = false) }
        assertFailsWith<WalletActivityUnavailableException> { service.activity(WALLET, 20, null, refresh = false) }

        assertEquals(WalletActivityUnavailable.PROVIDER_RATE_LIMITED, failure.reason)
        assertEquals(3L, failure.retryAfterSeconds, "a partial second rounds up")
        assertEquals(2, asked.size)
    }

    @Test
    fun `rejects an invalid address, a limit outside one to fifty and an invalid before signature before any lookup`() {
        val service = service()

        assertFailsWith<InvalidWalletAddressException> { service.activity("not-a-wallet", 20, null, refresh = false) }
        assertEquals("limit", assertFailsWith<InvalidConsoleRequestException> { service.activity(WALLET, 0, null, refresh = false) }.field)
        assertEquals("limit", assertFailsWith<InvalidConsoleRequestException> { service.activity(WALLET, 51, null, refresh = false) }.field)
        assertEquals("before", assertFailsWith<InvalidConsoleRequestException> { service.activity(WALLET, 20, "0OIl", refresh = false) }.field)
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `a blank before reads the newest page`() {
        service().activity(WALLET, 20, " ", refresh = false)

        assertNull(asked.single().first.before)
    }

    private fun service() = WalletActivityConsoleService(lookup, WalletActivityCache(clock), CapturedTokens { wallet -> captured.also { assertEquals(WALLET, wallet.value) } })

    private fun page(vararg actions: WalletAction = arrayOf(action(seed = 1, slot = 100, mint = MINT_A))) =
        WalletActivityPage(WalletAddress.of(WALLET), actions.toList(), unreadable = 0, scanned = actions.size, nextBefore = null, requestsUsed = 2)

    private fun action(seed: Int, slot: Long, mint: String, lamports: String = "1") = WalletAction(
        signature = TransactionSignature.of(WalletActivityFixtures.signature(seed)),
        instructionIndex = 0,
        innerIndex = 1,
        slot = Slot.of(slot),
        blockTime = null,
        side = TradeSide.BUY,
        mint = MintAddress.of(mint),
        solLamports = BigInteger(lamports),
        tokenRaw = BigInteger.TEN,
    )

    private companion object {
        const val WALLET = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
        const val MINT_A = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
        const val MINT_B = "6LChGSaKeBUoZGkiNc8z3ZB2rttv7pg1XBPaQJ2rpump"
        val OTHER_SIGNATURE: String = WalletActivityFixtures.signature(77)
    }
}
