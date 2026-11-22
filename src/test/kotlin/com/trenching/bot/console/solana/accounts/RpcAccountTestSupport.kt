package com.trenching.bot.console.solana.accounts

import com.trenching.bot.chaincapture.RpcEndpointChoice
import com.trenching.bot.chaincapture.RpcEndpointSource
import com.trenching.bot.console.solana.accounts.application.RpcAccountDirectory
import com.trenching.bot.console.solana.accounts.application.RpcAccountNetworkCheck
import com.trenching.bot.console.solana.accounts.application.RpcAccountStore
import com.trenching.bot.console.solana.accounts.domain.RpcAccountBalance
import com.trenching.bot.console.solana.accounts.domain.RpcAccountConfig
import com.trenching.bot.console.solana.accounts.domain.RpcAccountConfigs
import com.trenching.bot.console.solana.accounts.domain.NetworkCheck
import com.trenching.bot.console.solana.accounts.domain.RpcAccountName
import com.trenching.bot.console.solana.accounts.domain.RpcAccountState
import com.trenching.bot.notification.OutboundNotification
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

object RpcAccountTestData {
    const val PRIMARY_KEY = "PRIMARY-KEY-0001"
    const val BACKUP_KEY = "BACKUP-KEY-0002"
    const val PRIMARY_PROJECT = "11111111-aaaa-bbbb-cccc-primaryproject"
    const val BACKUP_PROJECT = "22222222-aaaa-bbbb-cccc-backupproject0"
    const val PRIMARY_URL = "https://primary.example.test/?api-key=$PRIMARY_KEY"
    const val BACKUP_URL = "https://backup.example.test/?api-key=$BACKUP_KEY"
    val NOW: Instant = Instant.parse("2026-11-22T10:00:00Z")
    val CYCLE_END: LocalDate = LocalDate.parse("2026-12-05")

    fun primary(url: String = PRIMARY_URL, projectId: String? = PRIMARY_PROJECT) = RpcAccountConfig(RpcAccountName.PRIMARY, url, "primary.example.test", projectId)

    fun backup(url: String = BACKUP_URL, projectId: String? = BACKUP_PROJECT) = RpcAccountConfig(RpcAccountName.BACKUP, url, "backup.example.test", projectId)

    fun both() = RpcAccountConfigs(listOf(primary(), backup()))

    fun balance(remaining: Long, limit: Long = 1_000_000, used: Long = limit - remaining, cycleEnd: LocalDate = CYCLE_END, plan: String = "Free") =
        RpcAccountBalance(remaining, used, limit, cycleEnd, plan)
}

class TestClock(var now: Instant = RpcAccountTestData.NOW) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
}

/** The rows in memory; [failing] makes the next saves throw, as a database that is down would. */
class InMemoryRpcAccountStore(initial: List<RpcAccountState> = emptyList()) : RpcAccountStore {
    val rows = initial.associateBy { it.name }.toMutableMap()
    val published = mutableListOf<OutboundNotification>()
    val saves = mutableListOf<RpcAccountState>()
    @Volatile var failing = false

    @Synchronized
    override fun load(): List<RpcAccountState> = rows.values.toList()

    @Synchronized
    override fun save(state: RpcAccountState, notifications: List<OutboundNotification>) {
        check(!failing) { "database is down" }
        rows[state.name] = state
        saves += state
        notifications.filter { n -> published.none { it.key == n.key } }.forEach(published::add)
    }
}

/** An [RpcEndpointSource] the test sets; remembers the accounts reported exhausted. */
class ScriptedRpcEndpoints(@Volatile var choice: RpcEndpointChoice) : RpcEndpointSource {
    val exhaustedAccounts = mutableListOf<String>()

    override fun choose(): RpcEndpointChoice = choice

    override fun exhausted(account: String) {
        exhaustedAccounts += account
    }
}

/** Answers every network check with [result] and remembers which accounts were asked. */
class ScriptedNetworkCheck(@Volatile var result: NetworkCheck = NetworkCheck.Mainnet) : RpcAccountNetworkCheck {
    val asked = java.util.concurrent.CopyOnWriteArrayList<RpcAccountName>()

    override fun check(account: RpcAccountName): NetworkCheck {
        asked += account
        return result
    }
}

fun directory(
    configs: RpcAccountConfigs = RpcAccountTestData.both(),
    store: InMemoryRpcAccountStore = InMemoryRpcAccountStore(),
    clock: Clock = TestClock(),
    lowBalancePercent: Int = 10,
    resumeCredits: Long = 1_000,
    network: RpcAccountNetworkCheck = ScriptedNetworkCheck(),
    zone: ZoneId = ZoneOffset.UTC,
) = RpcAccountDirectory(configs, store.load(), store, network, clock, lowBalancePercent, resumeCredits, zone)
