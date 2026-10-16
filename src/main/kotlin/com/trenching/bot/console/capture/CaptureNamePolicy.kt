package com.trenching.bot.console.capture

import com.trenching.bot.console.api.dto.CaptureAttachment
import com.trenching.bot.console.api.dto.CaptureWalletSettings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The two variable parts of a generated name: who is captured ([label]) and what ([subject]). */
data class CaptureNameParts(val label: String, val subject: String)

/**
 * Capture names: generated as `{label} · {subject} · {d MMM HH:mm}`, compared by their [key], and numbered ` (2)`, ` (3)`…
 * when taken. Sessions named before keys existed may share one; [displayNames] tells them apart without renaming them.
 */
object CaptureNamePolicy {
    const val MAX_LENGTH = 120

    private const val SEPARATOR = " · "
    private const val ELLIPSIS = "…"
    private const val SHORT_PART_LENGTH = 4
    private const val ATTACHED_LABEL = "Attached"
    private const val PUMPFUN_BUYS_SUBJECT = "Pump.fun buys"
    private const val WALLET_REFERENCES_SUBJECT = "Wallet references"
    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH)
    private val WHITESPACE = Regex("\\s+")
    private val NUMBER_SUFFIX = Regex(" \\(\\d+\\)$")
    private val PATH_SEPARATORS = Regex("[/\\\\]")

    fun normalize(name: String): String = name.trim().replace(WHITESPACE, " ")

    fun key(name: String): String = normalize(name).lowercase(Locale.ROOT)

    /** An attachment has no wallet settings until inspected, so it is named after its wallet directory. */
    fun parts(wallet: CaptureWalletSettings?, attach: CaptureAttachment?, suggested: CaptureSuggestedWallets): CaptureNameParts {
        if (wallet == null) return CaptureNameParts(ATTACHED_LABEL, lastSegment(requireNotNull(attach) { "A session has wallet settings or an attachment" }.wallet))
        val scope = requireNotNull(CaptureWalletScope.of(wallet.scope)) { "Unsupported wallet scope scope=${wallet.scope}" }
        val subject = when (scope) {
            CaptureWalletScope.WALLET_TOKEN -> short(requireNotNull(wallet.mint) { "The wallet-token scope has a mint" })
            CaptureWalletScope.PUMPFUN_USER_BUYS -> PUMPFUN_BUYS_SUBJECT
            CaptureWalletScope.WALLET_REFERENCES -> WALLET_REFERENCES_SUBJECT
        }
        return CaptureNameParts(suggested.labelOf(wallet.wallet) ?: short(wallet.wallet), subject)
    }

    /** The first free numbered form; a name too long for its number is cut in [CaptureNameParts.subject], never in its time or number. */
    fun generated(parts: CaptureNameParts, at: Instant, zone: ZoneId, taken: Set<String>): String {
        val time = TIME_FORMAT.format(at.atZone(zone))
        return firstFree(taken) { suffix ->
            val room = MAX_LENGTH - (parts.label.length + 2 * SEPARATOR.length + time.length + suffix.length)
            parts.label + SEPARATOR + fit(parts.subject, room) + SEPARATOR + time + suffix
        }
    }

    /** The first free numbered form of [name], counting on from its own number when it has one. */
    fun suggestion(name: String, taken: Set<String>): String {
        val base = normalize(name).replace(NUMBER_SUFFIX, "")
        return firstFree(taken) { suffix -> numbered(base, suffix) }
    }

    /** Session id to display name: the stored name, numbered by creation time then id among sessions sharing its key. */
    fun displayNames(records: List<CaptureSessionRecord>): Map<String, String> =
        records.groupBy(::storedKey).values.flatMap { group ->
            group.sortedWith(compareBy<CaptureSessionRecord> { it.createdAt }.thenBy { it.id })
                .mapIndexed { index, record -> record.id to numbered(record.name, suffix(index + 1)) }
        }.toMap()

    /** Every key a new name must avoid: stored names and the numbered display names, apart from [excluding]'s own. */
    fun takenKeys(records: List<CaptureSessionRecord>, excluding: String? = null): Set<String> {
        val others = records.filter { it.id != excluding }
        val displayed = displayNames(records).filterKeys { it != excluding }.values.map(::key)
        return others.map(::storedKey).toSet() + displayed
    }

    private fun storedKey(record: CaptureSessionRecord): String = record.nameKey ?: key(record.name)

    private fun firstFree(taken: Set<String>, candidate: (String) -> String): String =
        generateSequence(1) { it + 1 }.map { candidate(suffix(it)) }.first { key(it) !in taken }

    private fun suffix(number: Int): String = if (number == 1) "" else " ($number)"

    private fun numbered(base: String, suffix: String): String =
        if (base.length + suffix.length <= MAX_LENGTH) base + suffix else base.take(MAX_LENGTH - suffix.length).trimEnd() + suffix

    private fun fit(text: String, room: Int): String {
        require(room > ELLIPSIS.length) { "Generated name has no room for its subject room=$room" }
        return if (text.length <= room) text else text.take(room - ELLIPSIS.length) + ELLIPSIS
    }

    private fun short(address: String): String =
        if (address.length <= 2 * SHORT_PART_LENGTH + ELLIPSIS.length) address else address.take(SHORT_PART_LENGTH) + ELLIPSIS + address.takeLast(SHORT_PART_LENGTH)

    private fun lastSegment(path: String): String = path.split(PATH_SEPARATORS).lastOrNull { it.isNotBlank() } ?: path
}
