package com.trenching.bot.console.automation.domain

import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** Ids derived from their input (plan Rules 8, 10 and 21), so a restart, a repeated scan or a repeated sweep converges on the same rows. */
object AutomationIds {
    private const val SEPARATOR = "|"
    private const val ALGORITHM = "SHA-256"

    fun item(wallet: String, cursor: DiscoveryCursor): String =
        sha256(wallet, cursor.signature, cursor.instructionIndex.toString(), cursor.innerIndex.toString())

    fun session(wallet: String, firstItemId: String): String = sha256(wallet, firstItemId)

    /** Plan Rule 43: a backfill's id is derived from the wallet and the caller's request id, so a repeated create finds the first one. */
    fun backfill(wallet: String, requestId: String): String = sha256(wallet, requestId)

    /** A variation's id, so the row for a label of a plan is the same one on every start. */
    fun variation(planId: String, label: String): String = sha256(planId, label)

    fun sweepRun(itemId: String, planId: String, label: String): String = sha256(itemId, planId, label)

    /** A history event's id, a name-based UUID of the item and the event's order, so it fits the column and a repeat of the write gets the same one. */
    fun itemEvent(itemId: String, seq: Int): String = UUID.nameUUIDFromBytes("$itemId$SEPARATOR$seq".toByteArray(Charsets.UTF_8)).toString()

    private fun sha256(vararg parts: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance(ALGORITHM).digest(parts.joinToString(SEPARATOR).toByteArray(Charsets.UTF_8)))
}
