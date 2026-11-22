package com.trenching.bot.console.solana.accounts

import com.trenching.bot.chaincapture.RpcEndpointChoice
import com.trenching.bot.console.solana.accounts.RpcAccountTestData.balance
import com.trenching.bot.console.solana.accounts.application.RpcAccountBalances
import com.trenching.bot.console.solana.accounts.application.RpcAccountHealth
import com.trenching.bot.console.solana.accounts.application.RpcAccountRefresh
import com.trenching.bot.console.solana.accounts.domain.BalanceProblem
import com.trenching.bot.console.solana.accounts.domain.BalanceRead
import com.trenching.bot.console.solana.accounts.domain.RpcAccountName
import com.trenching.bot.console.solana.accounts.infrastructure.fixture.FixtureRpcAccountBalances
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test

class RpcAccountRefreshTest {
    private val clock = TestClock()
    private val store = InMemoryRpcAccountStore()
    private val directory = directory(store = store, clock = clock)
    private val balances = FixtureRpcAccountBalances()
    private val asked = mutableListOf<RpcAccountName>()
    private var answers = false
    private val refresh = RpcAccountRefresh(RpcAccountTestData.both(), directory, balances, { asked += it; answers }, clock)

    private fun active() = (directory.choose() as? RpcEndpointChoice.Use)?.target?.account

    @Test
    fun `a refresh reads every configured account and stores its balance`() {
        balances.set(RpcAccountName.PRIMARY, BalanceRead.Read(balance(800_000)))
        balances.set(RpcAccountName.BACKUP, BalanceRead.Read(balance(300_000, limit = 500_000)))

        refresh.refresh()

        val states = directory.snapshot().accounts.map { it.state }
        assertEquals(listOf(800_000L, 300_000L), states.map { it.balance?.creditsRemaining })
        assertEquals(listOf(clock.now, clock.now), states.map { it.balanceCheckedAt })
        assertEquals(2, store.saves.size)
    }

    @Test
    fun `zero credits found by a refresh move the active account and a later refill brings it back`() {
        balances.set(RpcAccountName.PRIMARY, BalanceRead.Read(balance(0)))
        balances.set(RpcAccountName.BACKUP, BalanceRead.Read(balance(900_000)))
        refresh.refresh()
        assertEquals("backup", active())

        clock.now = clock.now.plus(Duration.ofDays(20))
        balances.set(RpcAccountName.PRIMARY, BalanceRead.Read(balance(1_000_000)))
        refresh.refresh()

        assertEquals("primary", active())
    }

    @Test
    fun `an account out of credits whose balance cannot be read is asked once an hour and returns when it answers`() {
        directory.exhausted("primary")
        balances.set(RpcAccountName.PRIMARY, BalanceRead.Unavailable(BalanceProblem.ADMIN_API_DISABLED))
        balances.set(RpcAccountName.BACKUP, BalanceRead.Read(balance(700_000)))

        refresh.refresh()
        clock.now = clock.now.plus(Duration.ofMinutes(15))
        refresh.refresh()
        clock.now = clock.now.plus(Duration.ofMinutes(15))
        refresh.refresh()
        assertEquals(listOf(RpcAccountName.PRIMARY), asked)
        assertEquals("backup", active())

        clock.now = clock.now.plus(Duration.ofMinutes(30))
        answers = true
        refresh.refresh()

        assertEquals(listOf(RpcAccountName.PRIMARY, RpcAccountName.PRIMARY), asked)
        assertEquals("primary", active())
    }

    @Test
    fun `an account whose balance reads fine is never asked with a health request`() {
        directory.exhausted("primary")
        balances.set(RpcAccountName.PRIMARY, BalanceRead.Read(balance(10)))
        balances.set(RpcAccountName.BACKUP, BalanceRead.Read(balance(10_000)))

        refresh.refresh()

        assertEquals(emptyList(), asked)
        assertEquals("backup", active())
    }

    @Test
    fun `an account with credits whose balance cannot be read is not asked either`() {
        refresh.refresh()

        assertEquals(emptyList(), asked)
        assertIs<RpcEndpointChoice.Use>(directory.choose())
    }

    @Test
    fun `a refresh checks the backup's network before reading balances, and a backup that could not be checked is asked again next time`() {
        val check = ScriptedNetworkCheck(com.trenching.bot.console.solana.accounts.domain.NetworkCheck.Unknown)
        val checked = directory(store = store, clock = clock, network = check)
        val withCheck = RpcAccountRefresh(RpcAccountTestData.both(), checked, balances, { false }, clock)
        balances.set(RpcAccountName.PRIMARY, BalanceRead.Read(balance(0)))
        balances.set(RpcAccountName.BACKUP, BalanceRead.Read(balance(900_000)))

        withCheck.refresh()
        assertEquals(RpcEndpointChoice.OutOfCredits, checked.choose())

        check.result = com.trenching.bot.console.solana.accounts.domain.NetworkCheck.Mainnet
        withCheck.refresh()

        assertEquals(listOf(RpcAccountName.BACKUP, RpcAccountName.BACKUP), check.asked.toList())
        assertEquals("backup", (checked.choose() as RpcEndpointChoice.Use).target.account)
    }

    @Test
    fun `an unexpected failure is logged by its class name alone, never its message`() {
        val secret = "https://rpc.example.test/?api-key=LEAKED-KEY-123"
        val failing = RpcAccountBalances { error(secret) }
        val isolated = RpcAccountRefresh(RpcAccountTestData.both(), directory, failing, RpcAccountHealth { false }, clock)

        com.trenching.bot.notification.LogCapture().use { logs ->
            isolated.refresh()

            assertEquals(2, logs.at(ch.qos.logback.classic.Level.WARN).size)
            assertEquals(false, logs.everything().contains("LEAKED-KEY-123"))
            assertEquals(true, logs.everything().contains("IllegalStateException"))
        }
    }

    @Test
    fun `one account failing unexpectedly does not stop the other`() {
        val failing = RpcAccountBalances { account -> if (account == RpcAccountName.PRIMARY) error("boom") else BalanceRead.Read(balance(42)) }
        val isolated = RpcAccountRefresh(RpcAccountTestData.both(), directory, failing, RpcAccountHealth { false }, clock)

        isolated.refresh()

        assertEquals(42L, directory.snapshot().accounts.last().state.balance?.creditsRemaining)
    }
}
