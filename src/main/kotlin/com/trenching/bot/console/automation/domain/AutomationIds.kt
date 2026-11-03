package com.trenching.bot.console.automation.domain

import java.security.MessageDigest
import java.util.HexFormat

/** Ids derived from their input (plan Rules 8, 10, 21 and 24), so a restart, a repeated scan or a repeated sweep converges on the same rows. */
object AutomationIds {
    private const val SEPARATOR = "|"
    private const val ALGORITHM = "SHA-256"

    fun item(wallet: String, cursor: DiscoveryCursor): String =
        sha256(wallet, cursor.signature, cursor.instructionIndex.toString(), cursor.innerIndex.toString())

    fun session(wallet: String, firstItemId: String): String = sha256(wallet, firstItemId)

    fun sweepRun(itemId: String, planId: String, label: String): String = sha256(itemId, planId, label)

    fun evaluation(sessionId: String, planId: String, label: String): String = sha256(sessionId, planId, label)

    private fun sha256(vararg parts: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance(ALGORITHM).digest(parts.joinToString(SEPARATOR).toByteArray(Charsets.UTF_8)))
}
