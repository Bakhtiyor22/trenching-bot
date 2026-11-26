package com.trenching.bot.console.solana.accounts

import ch.qos.logback.classic.Level
import com.trenching.bot.chaincapture.RpcEndpointChoice
import com.trenching.bot.console.solana.accounts.RpcAccountTestData.NOW
import com.trenching.bot.console.solana.accounts.RpcAccountTestData.balance
import com.trenching.bot.console.solana.accounts.application.RpcAccountStanding
import com.trenching.bot.console.solana.accounts.domain.BalanceRead
import com.trenching.bot.console.solana.accounts.domain.NetworkCheck
import com.trenching.bot.console.solana.accounts.domain.RpcAccountName
import com.trenching.bot.notification.LogCapture
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** The backup is offered only once verified as mainnet (R12), and an account that keeps failing after it returns waits longer (R13). */
class RpcAccountNetworkAndReturnTest {
    private val clock = TestClock()
    private val store = InMemoryRpcAccountStore()
    private val network = ScriptedNetworkCheck()
    private val directory = directory(store = store, clock = clock, network = network)

    private fun chosen(from: com.trenching.bot.console.solana.accounts.application.RpcAccountDirectory = directory): String? =
        (from.choose() as? RpcEndpointChoice.Use)?.target?.account

    @Test
    fun `a verified backup is used, and the switch lands on it with one message`() {
        directory.exhausted("primary")

        assertEquals("backup", chosen())
        assertEquals(listOf(RpcAccountName.BACKUP), network.asked.toList())
        assertEquals(listOf("RPC_ACCOUNT_SWITCHED|primary|backup|$NOW"), store.published.map { it.key })
    }

    @Test
    fun `the primary is never asked for its network`() {
        directory.verifyNetworks()
        directory.exhausted("primary")

        assertEquals(listOf(RpcAccountName.BACKUP), network.asked.toList())
    }

    @Test
    fun `a backup on another network is never used, shows WRONG_NETWORK, is logged once and is not asked again`() {
        network.result = NetworkCheck.OtherNetwork
        LogCapture().use { logs ->
            directory.exhausted("primary")
            directory.verifyNetworks()
            directory.verifyNetworks()

            assertEquals(RpcEndpointChoice.OutOfCredits, directory.choose())
            assertNull(directory.snapshot().active)
            assertEquals(listOf(RpcAccountStanding.OUT_OF_CREDITS, RpcAccountStanding.WRONG_NETWORK), directory.snapshot().accounts.map { it.standing })
            assertEquals(listOf(RpcAccountName.BACKUP), network.asked.toList())
            assertEquals(1, logs.at(Level.WARN).count { it.contains("not on mainnet") }, logs.at(Level.WARN).toString())
            assertEquals(listOf("RPC_ACCOUNT_SWITCHED|primary|none|$NOW"), store.published.map { it.key })
        }
    }

    @Test
    fun `a backup that could not be checked holds capture, is asked again, and is used once verified`() {
        network.result = NetworkCheck.Unknown
        directory.exhausted("primary")

        assertEquals(RpcEndpointChoice.OutOfCredits, directory.choose())
        directory.verifyNetworks()
        assertEquals(RpcEndpointChoice.OutOfCredits, directory.choose())
        assertEquals(listOf(RpcAccountStanding.OUT_OF_CREDITS, RpcAccountStanding.STANDBY), directory.snapshot().accounts.map { it.standing })

        network.result = NetworkCheck.Mainnet
        clock.now = NOW.plusSeconds(900)
        directory.verifyNetworks()

        assertEquals("backup", chosen())
        assertEquals(3, network.asked.size)
        assertEquals(listOf(RpcAccountStanding.OUT_OF_CREDITS, RpcAccountStanding.ACTIVE), directory.snapshot().accounts.map { it.standing })
    }

    @Test
    fun `a restart while on the backup holds until the first check and then does not announce again`() {
        directory.exhausted("primary")
        val sent = store.published.size
        val freshNetwork = ScriptedNetworkCheck()

        val restarted = directory(store = store, clock = clock, network = freshNetwork)

        assertEquals(RpcEndpointChoice.OutOfCredits, restarted.choose())
        assertEquals(emptyList(), freshNetwork.asked.toList(), "choosing reads memory only")
        restarted.verifyNetworks()
        assertEquals("backup", chosen(restarted))
        assertEquals(sent, store.published.size)
    }

    @Test
    fun `a restart on a backup that turns out to be on another network announces that nothing is available`() {
        directory.exhausted("primary")
        val restarted = directory(store = store, clock = clock, network = ScriptedNetworkCheck(NetworkCheck.OtherNetwork))

        restarted.verifyNetworks()

        assertEquals(RpcEndpointChoice.OutOfCredits, restarted.choose())
        assertTrue(store.published.last().title.contains("No RPC account has credits"))
    }

    private fun returnPrimaryAt(at: Instant) {
        clock.now = at
        directory.recordBalance(RpcAccountName.PRIMARY, BalanceRead.Read(balance(remaining = 900_000)))
    }

    @Test
    fun `a primary that returns and runs out again within an hour may not return for an hour`() {
        directory.exhausted("primary")
        val returned = NOW.plus(Duration.ofHours(2))
        returnPrimaryAt(returned)
        assertEquals("primary", chosen())
        val messages = store.published.size

        clock.now = returned.plusSeconds(600)
        directory.exhausted("primary")
        returnPrimaryAt(returned.plusSeconds(1800))

        assertEquals("backup", chosen(), "a return inside the wait is refused")
        assertEquals(messages + 1, store.published.size, "only the second loss is announced; the refused return sends nothing")

        returnPrimaryAt(returned.plusSeconds(600).plus(Duration.ofHours(1)).plusSeconds(1))

        assertEquals("primary", chosen())
    }

    @Test
    fun `each further failed return doubles the wait`() {
        directory.exhausted("primary")
        var returned = NOW.plus(Duration.ofMinutes(5))
        listOf(Duration.ofHours(1), Duration.ofHours(2), Duration.ofHours(4)).forEach { wait ->
            returnPrimaryAt(returned)
            assertEquals("primary", chosen())
            val out = returned.plusSeconds(60)
            clock.now = out
            directory.exhausted("primary")

            returnPrimaryAt(out.plus(wait).minusSeconds(1))
            assertEquals("backup", chosen(), "still waiting just before $wait")
            returned = out.plus(wait)
        }
    }

    @Test
    fun `the wait stops doubling at a day`() {
        directory.exhausted("primary")
        var at = NOW.plus(Duration.ofMinutes(5))
        returnPrimaryAt(at)
        val observed = mutableListOf<Duration>()
        repeat(8) {
            clock.now = at.plusSeconds(60)
            directory.exhausted("primary")
            val out = clock.now
            var wait = Duration.ZERO
            while (chosen() != "primary") {
                wait = wait.plusMinutes(30)
                returnPrimaryAt(out.plus(wait))
            }
            observed += wait
            at = out.plus(wait)
        }

        assertTrue(observed.max() <= Duration.ofHours(24).plusMinutes(30), observed.toString())
        assertTrue(observed.last() >= Duration.ofHours(23), observed.toString())
    }

    @Test
    fun `a return that lasts an hour resets the wait`() {
        directory.exhausted("primary")
        returnPrimaryAt(NOW.plus(Duration.ofHours(2)))
        clock.now = NOW.plus(Duration.ofHours(2)).plusSeconds(60)
        directory.exhausted("primary")
        val lasting = NOW.plus(Duration.ofHours(4))
        returnPrimaryAt(lasting)

        clock.now = lasting.plus(Duration.ofHours(1)).plusSeconds(1)
        directory.exhausted("primary")
        returnPrimaryAt(clock.now.plusSeconds(60))

        assertEquals("primary", chosen(), "a return that lasted an hour owes nothing")
    }

    @Test
    fun `the next balance read stores the state again after a store that failed`() {
        store.failing = true
        directory.exhausted("primary")
        assertNull(store.rows[RpcAccountName.PRIMARY])
        store.failing = false

        clock.now = NOW.plusSeconds(900)
        directory.recordBalance(RpcAccountName.PRIMARY, BalanceRead.Read(balance(remaining = 10)))

        assertEquals(NOW, store.rows.getValue(RpcAccountName.PRIMARY).outOfCreditsSince)
        assertEquals(10L, store.rows.getValue(RpcAccountName.PRIMARY).balance?.creditsRemaining)
    }
}
