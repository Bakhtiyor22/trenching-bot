package com.trenching.bot.console.solana.accounts.application

import com.trenching.bot.chaincapture.RpcEndpointChoice
import com.trenching.bot.chaincapture.RpcEndpointSource
import com.trenching.bot.chaincapture.RpcEndpointTarget
import com.trenching.bot.common.LogLine
import com.trenching.bot.console.solana.accounts.domain.AccountNetwork
import com.trenching.bot.console.solana.accounts.domain.BalanceRead
import com.trenching.bot.console.solana.accounts.domain.NetworkCheck
import com.trenching.bot.console.solana.accounts.domain.ReturnWait
import com.trenching.bot.console.solana.accounts.domain.RpcAccountConfigs
import com.trenching.bot.console.solana.accounts.domain.RpcAccountMessages
import com.trenching.bot.console.solana.accounts.domain.RpcAccountName
import com.trenching.bot.console.solana.accounts.domain.RpcAccountRules
import com.trenching.bot.console.solana.accounts.domain.RpcAccountState
import com.trenching.bot.notification.OutboundNotification
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.slf4j.LoggerFactory

/**
 * The state of the primary and the backup account that every RPC client shares (plan 20261122, Rules 11 to 20 and amendments R12, R13).
 * The state lives in memory, loaded from the store at startup, so [choose] never touches the database and a restart does not forget an
 * account that ran out.
 *
 * The backup is offered only after its genesis hash was read as mainnet in this process: by [verifyNetworks], which the timed refresh
 * calls, or at the switch itself, in [exhausted], before the backup becomes active. Until then it counts as unavailable, so [choose]
 * answers [RpcEndpointChoice.OutOfCredits] and capture holds. An account that returns and fails again waits longer before it may return.
 *
 * A change is made in memory first, under a lock, and then stored in one short transaction that also publishes the notification it
 * causes. A second report of the same change finds it made and does nothing, so one exhaustion stores one change and sends one message.
 * If the transaction fails the memory keeps the change, the failure is logged, and the next balance read stores the state again; only the
 * message of that change is lost.
 */
class RpcAccountDirectory(
    private val configs: RpcAccountConfigs,
    stored: List<RpcAccountState>,
    private val store: RpcAccountStore,
    private val networkCheck: RpcAccountNetworkCheck,
    private val clock: Clock,
    private val lowBalancePercent: Int,
    private val resumeCredits: Long,
    private val zone: ZoneId,
) : RpcEndpointSource {
    private val log = LoggerFactory.getLogger(RpcAccountDirectory::class.java)
    private val lock = Any()
    private val verification = Any()
    @Volatile private var standing: Standing = initial(stored)

    /** The active account the last message told the operator about; a restart starts from the account that has credits, so it does not announce again what the previous process announced. Guarded by [lock]. */
    private var announced: RpcAccountName? = RpcAccountRules.withCredits(configs, standing.states)

    private class Standing(
        val states: Map<RpcAccountName, RpcAccountState>,
        val networks: Map<RpcAccountName, AccountNetwork>,
        val returns: Map<RpcAccountName, ReturnWait>,
    )

    override fun choose(): RpcEndpointChoice {
        if (configs[RpcAccountName.PRIMARY] == null) return RpcEndpointChoice.NotConfigured
        val current = standing
        val account = RpcAccountRules.active(configs, current.states, current.networks)?.let(configs::get) ?: return RpcEndpointChoice.OutOfCredits
        return RpcEndpointChoice.Use(RpcEndpointTarget(account.name.label, account.endpoint))
    }

    /** The primary's own report verifies the backup first, so the switch lands on an account known to be mainnet; the request is made outside every lock the request path takes and outside any transaction. */
    override fun exhausted(account: String) {
        val name = RpcAccountName.ofLabel(account)?.takeIf { configs[it] != null } ?: return
        if (name == RpcAccountName.PRIMARY) verify(RpcAccountName.BACKUP)
        change(name) { state, now -> RpcAccountRules.afterExhausted(state, now) }
    }

    /** Reads the genesis hash of every configured account not yet verified; one that could not be checked is tried again by the next call. */
    fun verifyNetworks() {
        configs.configured.forEach { verify(it.name) }
    }

    /** Applies a balance read of [name]: zero credits marks it out, enough credits bring it back, a low balance is reported. */
    fun recordBalance(name: RpcAccountName, read: BalanceRead) {
        change(name) { state, now -> RpcAccountRules.afterRead(state, read, now, resumeCredits) }
    }

    /** [name] answered a request although its balance could not say it has credits: it is back. */
    fun answered(name: RpcAccountName) {
        change(name) { state, now -> RpcAccountRules.afterAnswer(state, now) }
    }

    fun snapshot(): RpcAccountsSnapshot {
        val current = standing
        val active = RpcAccountRules.active(configs, current.states, current.networks)
        return RpcAccountsSnapshot(active, RpcAccountName.entries.map { name -> status(name, current, active) })
    }

    private fun status(name: RpcAccountName, current: Standing, active: RpcAccountName?): RpcAccountStatus {
        val state = current.states.getValue(name)
        val config = configs[name] ?: return RpcAccountStatus(name, RpcAccountStanding.NOT_CONFIGURED, null, RpcAccountState.fresh(name, state.updatedAt))
        val standing = when {
            current.networks.getValue(name) == AccountNetwork.WRONG_NETWORK -> RpcAccountStanding.WRONG_NETWORK
            !state.hasCredits -> RpcAccountStanding.OUT_OF_CREDITS
            name == active -> RpcAccountStanding.ACTIVE
            else -> RpcAccountStanding.STANDBY
        }
        return RpcAccountStatus(name, standing, config.host, state)
    }

    private fun verify(name: RpcAccountName) {
        if (configs[name] == null || standing.networks.getValue(name) != AccountNetwork.UNVERIFIED) return
        synchronized(verification) {
            if (standing.networks.getValue(name) != AccountNetwork.UNVERIFIED) return
            val verified = when (networkCheck.check(name)) {
                NetworkCheck.Mainnet -> AccountNetwork.MAINNET
                NetworkCheck.OtherNetwork -> AccountNetwork.WRONG_NETWORK
                NetworkCheck.Unknown -> return
            }
            recordNetwork(name, verified)
        }
    }

    private fun recordNetwork(name: RpcAccountName, network: AccountNetwork) {
        synchronized(lock) {
            val now = clock.instant()
            val before = standing
            val after = Standing(before.states, before.networks + (name to network), before.returns)
            standing = after
            if (network == AccountNetwork.WRONG_NETWORK) {
                LogLine("An RPC account is not on mainnet, so this process never uses it.").field("account", name.label).warn(log)
            }
            val to = RpcAccountRules.active(configs, after.states, after.networks)
            val from = announced
            if (to == from) return
            announced = to
            persist(after.states.getValue(name), listOf(switchMessage(from, to, after.states, now)))
            reportSwitch(from, to)
        }
    }

    private fun change(name: RpcAccountName, transform: (RpcAccountState, Instant) -> RpcAccountState) {
        synchronized(lock) {
            val now = clock.instant()
            val before = standing
            val old = before.states.getValue(name)
            val settled = before.returns.getValue(name).settle(old, transform(old, now), now)
            val new = settled.state
            if (new == old) return
            val after = Standing(before.states + (name to new), before.networks, before.returns + (name to settled.wait))
            standing = after
            val from = announced
            val to = RpcAccountRules.active(configs, after.states, after.networks)
            announced = to
            persist(new, notifications(old, new, from, to, after.states, now))
            report(old, new, from, to)
        }
    }

    private fun notifications(
        old: RpcAccountState,
        new: RpcAccountState,
        from: RpcAccountName?,
        to: RpcAccountName?,
        after: Map<RpcAccountName, RpcAccountState>,
        now: Instant,
    ): List<OutboundNotification> = buildList {
        if (from != to) add(switchMessage(from, to, after, now))
        val balance = new.balance
        if (balance != null && new.balanceProblem == null && new.balanceCheckedAt != old.balanceCheckedAt && RpcAccountRules.isLow(balance, lowBalancePercent)) {
            add(RpcAccountMessages.lowBalance(new))
        }
    }

    private fun switchMessage(from: RpcAccountName?, to: RpcAccountName?, states: Map<RpcAccountName, RpcAccountState>, now: Instant) =
        RpcAccountMessages.switched(from, to, now, configs.configured.map { states.getValue(it.name) }, zone)

    private fun persist(state: RpcAccountState, notifications: List<OutboundNotification>) {
        try {
            store.save(state, notifications)
        } catch (e: RuntimeException) {
            LogLine("Could not store the state of an RPC account; it is kept in memory and stored again by the next balance read.")
                .field("account", state.name.label).error(log, e)
        }
    }

    private fun reportSwitch(from: RpcAccountName?, to: RpcAccountName?) {
        if (to == null) {
            LogLine("No RPC account has credits, so capture is held until one has again.").field("previous", from?.label).warn(log)
        } else {
            LogLine("The active RPC account changed.").field("account", to.label).field("previous", from?.label).warn(log)
        }
    }

    private fun report(old: RpcAccountState, new: RpcAccountState, from: RpcAccountName?, to: RpcAccountName?) {
        if (from != to) reportSwitch(from, to)
        if (old.balanceProblem != new.balanceProblem) {
            val problem = new.balanceProblem
            if (problem == null) {
                LogLine("The credit balance of an RPC account can be read again.").field("account", new.name.label).info(log)
            } else {
                LogLine("Could not read the credit balance of an RPC account: {}.", problem.meaning)
                    .field("account", new.name.label).field("problem", problem.name).warn(log)
            }
        }
    }

    private fun initial(stored: List<RpcAccountState>): Standing {
        val now = clock.instant()
        val states = RpcAccountName.entries.associateWith { name -> stored.firstOrNull { it.name == name } ?: RpcAccountState.fresh(name, now) }
        val networks = RpcAccountName.entries.associateWith { if (it == RpcAccountName.PRIMARY) AccountNetwork.MAINNET else AccountNetwork.UNVERIFIED }
        return Standing(states, networks, RpcAccountName.entries.associateWith { ReturnWait.NONE })
    }
}
