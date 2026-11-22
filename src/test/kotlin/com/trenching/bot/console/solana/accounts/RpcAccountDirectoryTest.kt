package com.trenching.bot.console.solana.accounts

import ch.qos.logback.classic.Level
import com.trenching.bot.chaincapture.RpcEndpointChoice
import com.trenching.bot.console.solana.accounts.application.RpcAccountDirectory
import com.trenching.bot.console.solana.accounts.application.RpcAccountStanding
import com.trenching.bot.console.solana.accounts.domain.BalanceProblem
import com.trenching.bot.console.solana.accounts.domain.BalanceRead
import com.trenching.bot.console.solana.accounts.domain.RpcAccountConfigs
import com.trenching.bot.console.solana.accounts.domain.RpcAccountName
import com.trenching.bot.console.solana.accounts.domain.RpcAccountState
import com.trenching.bot.console.solana.accounts.RpcAccountTestData.CYCLE_END
import com.trenching.bot.console.solana.accounts.RpcAccountTestData.NOW
import com.trenching.bot.console.solana.accounts.RpcAccountTestData.balance
import com.trenching.bot.notification.LogCapture
import com.trenching.bot.notification.NotificationKind
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RpcAccountDirectoryTest {
    private val clock = TestClock()
    private val store = InMemoryRpcAccountStore()
    private val network = ScriptedNetworkCheck()
    private val directory = directory(store = store, clock = clock, network = network)

    private fun chosen(directory: RpcAccountDirectory = this.directory): String? =
        (directory.choose() as? RpcEndpointChoice.Use)?.target?.account

    @Test
    fun `the primary is chosen while it has credits, whatever the backup holds`() {
        assertEquals("primary", chosen())
        val target = assertIs<RpcEndpointChoice.Use>(directory.choose()).target
        assertEquals(RpcAccountTestData.PRIMARY_URL, target.endpoint)
        assertEquals("backup", directory.snapshot().accounts.last().name.label)
    }

    @Test
    fun `with no primary configured nothing is chosen and the choice says so`() {
        val none = directory(configs = RpcAccountConfigs.NONE)

        assertEquals(RpcEndpointChoice.NotConfigured, none.choose())
        assertNull(none.snapshot().active)
        assertEquals(listOf(RpcAccountStanding.NOT_CONFIGURED, RpcAccountStanding.NOT_CONFIGURED), none.snapshot().accounts.map { it.standing })
    }

    @Test
    fun `a primary that ran out moves the choice to the backup, stores the change and publishes one message`() {
        LogCapture().use { logs ->
            directory.exhausted("primary")

            assertEquals("backup", chosen())
            assertEquals(NOW, store.rows.getValue(RpcAccountName.PRIMARY).outOfCreditsSince)
            assertEquals(listOf("RPC_ACCOUNT_SWITCHED|primary|backup|$NOW"), store.published.map { it.key })
            assertEquals(NotificationKind.RPC_ACCOUNT_SWITCHED, store.published.single().kind)
            assertEquals(1, logs.at(Level.WARN).size)
        }
    }

    @Test
    fun `reporting the same exhaustion again stores nothing and publishes nothing`() {
        directory.exhausted("primary")
        clock.now = NOW.plusSeconds(30)

        directory.exhausted("primary")

        assertEquals(1, store.saves.size)
        assertEquals(1, store.published.size)
        assertEquals(NOW, store.rows.getValue(RpcAccountName.PRIMARY).outOfCreditsSince)
    }

    @Test
    fun `concurrent reports of one exhaustion store one change and publish one message`() {
        val callers = 16
        val pool = Executors.newFixedThreadPool(callers)
        val start = CountDownLatch(1)
        try {
            val done = (1..callers).map { pool.submit { start.await(); directory.exhausted("primary") } }
            start.countDown()
            done.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, store.saves.size)
        assertEquals(1, store.published.size)
        assertEquals("backup", chosen())
        assertEquals(listOf(RpcAccountName.BACKUP), network.asked.toList(), "the backup is verified once however many callers report")
    }

    @Test
    fun `with the backup out as well no account has credits, one message says so, and choosing reports it`() {
        directory.exhausted("primary")
        directory.exhausted("backup")

        assertEquals(RpcEndpointChoice.OutOfCredits, directory.choose())
        assertNull(directory.snapshot().active)
        assertEquals(listOf("RPC_ACCOUNT_SWITCHED|primary|backup|$NOW", "RPC_ACCOUNT_SWITCHED|backup|none|$NOW"), store.published.map { it.key })
        assertTrue(store.published.last().title.contains("No RPC account has credits"))
    }

    @Test
    fun `without a backup an exhausted primary leaves nothing to choose`() {
        val alone = directory(configs = RpcAccountConfigs(listOf(RpcAccountTestData.primary())), store = store)

        alone.exhausted("primary")

        assertEquals(RpcEndpointChoice.OutOfCredits, alone.choose())
        assertEquals(listOf("RPC_ACCOUNT_SWITCHED|primary|none|$NOW"), store.published.map { it.key })
    }

    @Test
    fun `a backup that runs out while the primary is active changes the stored state but sends no switch message`() {
        directory.exhausted("backup")

        assertEquals("primary", chosen())
        assertEquals(NOW, store.rows.getValue(RpcAccountName.BACKUP).outOfCreditsSince)
        assertTrue(store.published.isEmpty())
    }

    @Test
    fun `an account the directory does not know is ignored`() {
        directory.exhausted("tertiary")
        val noBackup = directory(configs = RpcAccountConfigs(listOf(RpcAccountTestData.primary())))
        noBackup.exhausted("backup")

        assertEquals("primary", chosen(noBackup))
        assertTrue(store.saves.isEmpty())
    }

    @Test
    fun `a balance read of zero marks the account out and moves the choice`() {
        directory.verifyNetworks()
        directory.recordBalance(RpcAccountName.PRIMARY, BalanceRead.Read(balance(remaining = 0)))

        assertEquals("backup", chosen())
        assertEquals(NOW, directory.snapshot().accounts.first().state.outOfCreditsSince)
        assertEquals(listOf("RPC_ACCOUNT_SWITCHED|primary|backup|$NOW", "RPC_BALANCE_LOW|primary|$CYCLE_END"), store.published.map { it.key })
    }

    @Test
    fun `the primary returns when a read shows at least the resume credits and one message says so`() {
        directory.exhausted("primary")
        clock.now = NOW.plus(Duration.ofHours(2))

        directory.recordBalance(RpcAccountName.PRIMARY, BalanceRead.Read(balance(remaining = 1_000)))

        assertEquals("primary", chosen())
        assertNull(store.rows.getValue(RpcAccountName.PRIMARY).outOfCreditsSince)
        assertEquals("RPC_ACCOUNT_SWITCHED|backup|primary|${NOW.plus(Duration.ofHours(2))}", store.published.last { it.kind == NotificationKind.RPC_ACCOUNT_SWITCHED }.key)
        assertEquals(2, store.published.count { it.kind == NotificationKind.RPC_ACCOUNT_SWITCHED })
    }

    @Test
    fun `an account out of credits stays out while a read shows fewer credits than the resume threshold`() {
        directory.exhausted("primary")

        directory.recordBalance(RpcAccountName.PRIMARY, BalanceRead.Read(balance(remaining = 999)))

        assertEquals("backup", chosen())
        assertEquals(NOW, directory.snapshot().accounts.first().state.outOfCreditsSince)
    }

    @Test
    fun `an account that answers again although its balance cannot be read is back`() {
        directory.exhausted("primary")

        directory.answered(RpcAccountName.PRIMARY)

        assertEquals("primary", chosen())
        assertEquals(2, store.published.size)
    }

    @Test
    fun `a low balance is published once per account and cycle however often it is read`() {
        repeat(3) {
            clock.now = clock.now.plusSeconds(900)
            directory.recordBalance(RpcAccountName.BACKUP, BalanceRead.Read(balance(remaining = 99_999)))
        }
        clock.now = clock.now.plusSeconds(900)
        directory.recordBalance(RpcAccountName.BACKUP, BalanceRead.Read(balance(remaining = 100_000)))

        assertEquals(listOf("RPC_BALANCE_LOW|backup|$CYCLE_END"), store.published.map { it.key })
        assertTrue(store.published.single().lines.single().contains("99,999 of 1,000,000"), store.published.single().lines.toString())
    }

    @Test
    fun `a limit of zero never reads as low`() {
        directory.recordBalance(RpcAccountName.PRIMARY, BalanceRead.Read(balance(remaining = 5, limit = 0, used = 0)))

        assertTrue(store.published.isEmpty())
    }

    @Test
    fun `a failed read keeps the last balance, stores the problem and logs once per change of problem`() {
        directory.recordBalance(RpcAccountName.PRIMARY, BalanceRead.Read(balance(remaining = 500_000)))
        LogCapture().use { logs ->
            repeat(3) {
                clock.now = clock.now.plusSeconds(900)
                directory.recordBalance(RpcAccountName.PRIMARY, BalanceRead.Unavailable(BalanceProblem.ADMIN_API_DISABLED))
            }
            directory.recordBalance(RpcAccountName.PRIMARY, BalanceRead.Unavailable(BalanceProblem.AUTHENTICATION))

            val state = directory.snapshot().accounts.first().state
            assertEquals(500_000, state.balance?.creditsRemaining)
            assertEquals(BalanceProblem.AUTHENTICATION, state.balanceProblem)
            assertEquals(2, logs.at(Level.WARN).size, logs.at(Level.WARN).toString())
            assertFalse(logs.everything().contains("api-key"))
        }
    }

    @Test
    fun `a restart while on the backup loads the stored state and neither re-sends the message nor forgets the account`() {
        directory.exhausted("primary")
        val sent = store.published.size

        val restarted = directory(store = store, clock = clock)

        assertEquals(RpcEndpointChoice.OutOfCredits, restarted.choose(), "the backup is not offered before its network was verified in this process")
        restarted.verifyNetworks()
        assertEquals("backup", chosen(restarted))
        assertEquals(sent, store.published.size)
        assertEquals(1, store.saves.size)
    }

    @Test
    fun `a stored state of an account that is no longer configured is not shown and does not block the active one`() {
        val old = RpcAccountState.fresh(RpcAccountName.BACKUP, NOW).copy(outOfCreditsSince = NOW.minusSeconds(60))
        val alone = directory(configs = RpcAccountConfigs(listOf(RpcAccountTestData.primary())), store = InMemoryRpcAccountStore(listOf(old)))

        assertEquals("primary", chosen(alone))
        assertNull(alone.snapshot().accounts.last().state.outOfCreditsSince)
        assertNull(alone.snapshot().accounts.last().host)
    }

    @Test
    fun `a store that fails keeps the change in memory and logs an error without a url or key`() {
        store.failing = true
        LogCapture().use { logs ->
            directory.exhausted("primary")

            assertEquals("backup", chosen())
            assertEquals(1, logs.at(Level.ERROR).size)
            assertFalse(logs.everything().contains(RpcAccountTestData.PRIMARY_KEY))
        }
    }

    @Test
    fun `the snapshot names each account's standing and host and never an endpoint`() {
        directory.exhausted("primary")

        val snapshot = directory.snapshot()

        assertEquals(RpcAccountName.BACKUP, snapshot.active)
        assertEquals(listOf(RpcAccountStanding.OUT_OF_CREDITS, RpcAccountStanding.ACTIVE), snapshot.accounts.map { it.standing })
        assertEquals(listOf("primary.example.test", "backup.example.test"), snapshot.accounts.map { it.host })
        assertFalse(snapshot.toString().contains(RpcAccountTestData.PRIMARY_KEY))
        assertFalse(snapshot.toString().contains(RpcAccountTestData.PRIMARY_PROJECT))
    }

    @Test
    fun `the primary returning is read as standby for the backup`() {
        directory.exhausted("primary")
        directory.answered(RpcAccountName.PRIMARY)

        assertEquals(listOf(RpcAccountStanding.ACTIVE, RpcAccountStanding.STANDBY), directory.snapshot().accounts.map { it.standing })
    }
}
