package com.trenching.bot.console.solana.accounts.application

import com.trenching.bot.common.ExceptionCauses
import com.trenching.bot.common.LogLine
import com.trenching.bot.console.solana.accounts.domain.BalanceRead
import com.trenching.bot.console.solana.accounts.domain.RpcAccountConfigs
import com.trenching.bot.console.solana.accounts.domain.RpcAccountName
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory

/**
 * Verifies the network of any account not yet verified, then reads the balance of every configured account and hands each read to the
 * [directory]. A failure that escapes is logged by its class name only: its message can quote an address or a key. Nothing here runs in a database transaction: a
 * read is fetched first, and the directory stores its outcome afterwards. An account that is out of credits and whose balance cannot be
 * read is asked once an hour whether it answers a `getHealth` request, and is back when it does.
 */
class RpcAccountRefresh(
    private val configs: RpcAccountConfigs,
    private val directory: RpcAccountDirectory,
    private val balances: RpcAccountBalances,
    private val health: RpcAccountHealth,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(RpcAccountRefresh::class.java)
    private val lastAsked = mutableMapOf<RpcAccountName, Instant>()

    fun refresh() {
        guarded(null) { directory.verifyNetworks() }
        configs.configured.forEach { account -> guarded(account.name) { refresh(account.name) } }
    }

    private fun guarded(account: RpcAccountName?, action: () -> Unit) {
        try {
            action()
        } catch (e: RuntimeException) {
            LogLine("Refreshing the RPC accounts failed unexpectedly.").field("account", account?.label).field("cause", ExceptionCauses.rootName(e)).warn(log)
        }
    }

    private fun refresh(name: RpcAccountName) {
        val read = balances.read(name)
        directory.recordBalance(name, read)
        if (read is BalanceRead.Unavailable && isOutOfCredits(name) && dueForQuestion(name)) askWhetherItAnswers(name)
    }

    private fun isOutOfCredits(name: RpcAccountName): Boolean =
        directory.snapshot().accounts.first { it.name == name }.state.outOfCreditsSince != null

    private fun dueForQuestion(name: RpcAccountName): Boolean {
        val last = lastAsked[name] ?: return true
        return Duration.between(last, clock.instant()) >= HEALTH_INTERVAL
    }

    private fun askWhetherItAnswers(name: RpcAccountName) {
        lastAsked[name] = clock.instant()
        if (health.answers(name)) directory.answered(name)
    }

    companion object {
        val HEALTH_INTERVAL: Duration = Duration.ofHours(1)
    }
}
