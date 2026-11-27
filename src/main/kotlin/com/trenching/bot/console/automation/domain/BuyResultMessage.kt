package com.trenching.bot.console.automation.domain

import com.trenching.bot.notification.NotificationKind
import com.trenching.bot.notification.NotificationLink
import com.trenching.bot.notification.OutboundNotification
import com.trenching.bot.portfolio.domain.PositionState
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The simulated buy a message reports; [backfillId] is set for a buy a backfill found. */
data class BuySubject(val itemId: String, val mint: String, val boughtAt: Instant, val backfillId: String?)

/** One variation's stored sweep row for the buy, under the variation's [name]. [realizedPnl] is SOL at scale 12. */
data class BuyVariationResult(
    val name: VariationName,
    val runId: String?,
    val outcome: String,
    val positionState: PositionState?,
    val realizedPnl: BigDecimal,
) {
    val profitOrLoss: Boolean get() = positionState == PositionState.CLOSED && realizedPnl.signum() != 0
}

/**
 * The message for a simulated live buy (notification plan Rules 3, 4 and 6): a line for each variation, by its name, whose position closed with a
 * profit or a loss, one line naming the others with their outcome, and links to the buy and to each run. Nothing for a backfilled buy
 * or one where no position closed with a profit or a loss, since then there is nothing to report. A pure function of the stored rows.
 */
class BuyResultMessage(private val calendar: TradingCalendar) {
    private val timeFormat = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH).withZone(calendar.zone)

    fun of(buy: BuySubject, results: List<BuyVariationResult>): OutboundNotification? {
        if (buy.backfillId != null) return null
        val ordered = results.sortedWith(compareBy(VariationName.ORDER, BuyVariationResult::name))
        val (traded, idle) = ordered.partition { it.profitOrLoss }
        if (traded.isEmpty()) return null
        val profits = traded.count { it.realizedPnl.signum() > 0 }
        val lines = traded.map(::tradedLine) + listOfNotNull(idle.takeIf { it.isNotEmpty() }?.let(::idleLine))
        val title = "Buy ${NotificationText.short(buy.mint)} at ${timeFormat.format(buy.boughtAt)}: $profits profit, ${traded.size - profits} loss"
        return OutboundNotification(key(buy.itemId), NotificationKind.BUY_RESULT, title, lines, links(buy, ordered))
    }

    private fun tradedLine(result: BuyVariationResult): String {
        val verdict = if (result.realizedPnl.signum() > 0) PROFIT else LOSS
        return "${result.name.text} $verdict ${NotificationText.signedSol(result.realizedPnl)}, ${NotificationText.words(result.outcome)}"
    }

    private fun idleLine(idle: List<BuyVariationResult>): String =
        NO_TRADE + idle.joinToString(", ") { "${it.name.text} ${NotificationText.words(it.outcome)}" }

    private fun links(buy: BuySubject, ordered: List<BuyVariationResult>): List<NotificationLink> {
        val day = calendar.dayOf(buy.boughtAt).date
        val buyPage = NotificationLink(BUY_LINK, "/automation/days/$day?item=${buy.itemId}")
        return listOf(buyPage) + ordered.mapNotNull { result -> result.runId?.let { NotificationLink(result.name.text, "/runs/$it") } }
    }

    companion object {
        private const val PROFIT = "profit"
        private const val LOSS = "loss"
        private const val NO_TRADE = "No trade: "
        private const val BUY_LINK = "Buy"

        fun key(itemId: String): String = "${NotificationKind.BUY_RESULT.name}|$itemId"
    }
}
