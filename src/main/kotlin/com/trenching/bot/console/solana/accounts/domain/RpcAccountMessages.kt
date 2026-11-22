package com.trenching.bot.console.solana.accounts.domain

import com.trenching.bot.notification.NotificationKind
import com.trenching.bot.notification.NotificationLink
import com.trenching.bot.notification.OutboundNotification
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The two messages about the RPC accounts. They name an account `primary` or `backup` and never carry an endpoint, key or project id. Times
 * read in the zone the caller passes (`trenching.automation.zone`), like the automation's messages; a billing cycle's end is a UTC date.
 */
object RpcAccountMessages {
    const val SOLANA_PAGE_PATH = "/solana"
    private const val NO_ACCOUNT = "none"
    private const val SOLANA_PAGE_LABEL = "Solana page"
    private val MINUTE = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH)

    /** The active account changed from [from] to [to] (null: none) at [at]; [states] are the configured accounts after the change; times read in [zone]. */
    fun switched(from: RpcAccountName?, to: RpcAccountName?, at: Instant, states: List<RpcAccountState>, zone: ZoneId): OutboundNotification =
        OutboundNotification(
            key = "${NotificationKind.RPC_ACCOUNT_SWITCHED.name}|${from.keyPart()}|${to.keyPart()}|$at",
            kind = NotificationKind.RPC_ACCOUNT_SWITCHED,
            title = switchedTitle(from, to),
            lines = listOf(switchedSentence(to)) + states.sortedBy { it.name }.map { accountLine(it, zone) },
            links = listOf(NotificationLink(SOLANA_PAGE_LABEL, SOLANA_PAGE_PATH)),
        )

    /** [state]'s credits are below the low-balance share of its limit; [state] must hold the balance that says so. */
    fun lowBalance(state: RpcAccountState): OutboundNotification {
        val balance = requireNotNull(state.balance) { "A low balance needs a balance account=${state.name.label}" }
        return OutboundNotification(
            key = "${NotificationKind.RPC_BALANCE_LOW.name}|${state.name.label}|${balance.cycleEndsOn}",
            kind = NotificationKind.RPC_BALANCE_LOW,
            title = "RPC credits are low on the ${state.name.label} account",
            lines = listOf(creditsLine(state.name, balance)),
            links = listOf(NotificationLink(SOLANA_PAGE_LABEL, SOLANA_PAGE_PATH)),
        )
    }

    private fun switchedTitle(from: RpcAccountName?, to: RpcAccountName?): String = when {
        to == null -> "No RPC account has credits"
        to == RpcAccountName.PRIMARY -> "RPC is back on the primary account"
        from == RpcAccountName.PRIMARY -> "RPC switched to the backup account"
        else -> "RPC is on the ${to.label} account"
    }

    private fun switchedSentence(to: RpcAccountName?): String =
        if (to == null) "Capture is held until an account has credits again." else "Capture now uses the ${to.label} account."

    private fun accountLine(state: RpcAccountState, zone: ZoneId): String {
        val outSince = state.outOfCreditsSince
        val balance = state.balance
        return when {
            outSince != null -> "${state.name.label}: out of credits since ${MINUTE.withZone(zone).format(outSince)}"
            balance != null -> creditsLine(state.name, balance)
            else -> "${state.name.label}: credits not known"
        }
    }

    private fun creditsLine(name: RpcAccountName, balance: RpcAccountBalance): String =
        "${name.label}: ${count(balance.creditsRemaining)} of ${count(balance.creditsLimit)} credits left, the cycle ends ${balance.cycleEndsOn} (UTC)"

    private fun count(credits: Long): String = String.format(Locale.ROOT, "%,d", credits)

    private fun RpcAccountName?.keyPart(): String = this?.label ?: NO_ACCOUNT
}
