package com.trenching.bot.console.capture

import com.trenching.bot.console.api.dto.CaptureAttachment
import com.trenching.bot.console.api.dto.CaptureWalletSettings
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CaptureNamePolicyTest {
    private val at = Instant.parse("2026-10-16T20:13:00Z")
    private val decu = CaptureSuggestedWallets(listOf(CaptureWalletSuggestion("Decu", CaptureConsoleFixture.WALLET)))
    private val tokenScope = CaptureWalletSettings(CaptureConsoleFixture.WALLET, "wallet-token", 100, 5, MINT)

    private fun parts(wallet: CaptureWalletSettings? = tokenScope, attach: CaptureAttachment? = null, suggested: CaptureSuggestedWallets = decu) =
        CaptureNamePolicy.parts(wallet, attach, suggested)

    @Test
    fun `a generated name joins the wallet label the short mint and the minute`() {
        assertEquals("Decu · 7mup…pump · 16 Oct 20:13", CaptureNamePolicy.generated(parts(), at, ZoneOffset.UTC, emptySet()))
    }

    @Test
    fun `the time part uses the request's zone`() {
        assertEquals("Decu · 7mup…pump · 16 Oct 22:13", CaptureNamePolicy.generated(parts(), at, ZoneId.of("Europe/Berlin"), emptySet()))
        assertEquals("Decu · 7mup…pump · 17 Oct 05:13", CaptureNamePolicy.generated(parts(), at, ZoneId.of("Asia/Tokyo"), emptySet()))
    }

    @Test
    fun `an unsuggested wallet is shortened and scopes without a mint name what they capture`() {
        val plain = CaptureSuggestedWallets()

        assertEquals(CaptureNameParts("4vw5…9Ud9", "7mup…pump"), parts(suggested = plain))
        assertEquals(CaptureNameParts("Decu", "Pump.fun buys"), parts(CaptureWalletSettings(CaptureConsoleFixture.WALLET)))
        assertEquals(CaptureNameParts("Decu", "Wallet references"), parts(CaptureWalletSettings(CaptureConsoleFixture.WALLET, "wallet-references")))
        assertEquals(CaptureNameParts("Attached", "decu-wallet"), parts(null, CaptureAttachment("evidence/decu-wallet", "evidence/decu-markets")))
    }

    @Test
    fun `a generated name that collides takes the first free number`() {
        val base = "Decu · 7mup…pump · 16 Oct 20:13"

        assertEquals("$base (2)", CaptureNamePolicy.generated(parts(), at, ZoneOffset.UTC, setOf(CaptureNamePolicy.key(base))))
        assertEquals("$base (3)", CaptureNamePolicy.generated(parts(), at, ZoneOffset.UTC, setOf(base, "$base (2)").map(CaptureNamePolicy::key).toSet()))
    }

    @Test
    fun `a typed name's suggestion counts on from its base name`() {
        val base = "Decu · 7mup…pump · 16 Oct 20:13"
        val taken = setOf(base, "$base (2)").map(CaptureNamePolicy::key).toSet()

        assertEquals("$base (3)", CaptureNamePolicy.suggestion("$base (2)", taken))
        assertEquals("${base.uppercase()} (3)", CaptureNamePolicy.suggestion(base.uppercase(), taken))
        assertEquals("runitback (2)", CaptureNamePolicy.suggestion("  runitback ", setOf("runitback")))
    }

    @Test
    fun `the name key trims collapses whitespace and ignores case`() {
        assertEquals("decu · run 1", CaptureNamePolicy.key("  Decu \t·\n  RUN   1 "))
        assertEquals("Decu · RUN 1", CaptureNamePolicy.normalize("  Decu \t·\n  RUN   1 "))
    }

    @Test
    fun `a long generated name is cut in its middle part and keeps its time and number`() {
        val long = "x".repeat(200)
        val attached = parts(null, CaptureAttachment(long))

        val first = CaptureNamePolicy.generated(attached, at, ZoneOffset.UTC, emptySet())
        val second = CaptureNamePolicy.generated(attached, at, ZoneOffset.UTC, setOf(CaptureNamePolicy.key(first)))

        assertEquals(CaptureNamePolicy.MAX_LENGTH, first.length)
        assertTrue(first.startsWith("Attached · xxx") && first.endsWith("x… · 16 Oct 20:13"), first)
        assertEquals(CaptureNamePolicy.MAX_LENGTH, second.length)
        assertTrue(second.endsWith("x… · 16 Oct 20:13 (2)"), second)
    }

    @Test
    fun `a suggestion for a full-length typed name is cut before its number`() {
        val typed = "a".repeat(CaptureNamePolicy.MAX_LENGTH)

        val suggestion = CaptureNamePolicy.suggestion(typed, setOf(CaptureNamePolicy.key(typed)))

        assertEquals(CaptureNamePolicy.MAX_LENGTH, suggestion.length)
        assertTrue(suggestion.endsWith("a (2)"))
    }

    @Test
    fun `legacy duplicates are numbered by creation time then id and the first keeps its bare name`() {
        val records = listOf(
            record("c", "runitback", "2026-10-10T10:00:00Z"),
            record("a", "Runitback", "2026-10-10T09:00:00Z"),
            record("b", "runitback", "2026-10-10T10:00:00Z"),
            record("d", "gigapump", "2026-10-11T09:00:00Z"),
        )

        assertEquals(mapOf("a" to "Runitback", "b" to "runitback (2)", "c" to "runitback (3)", "d" to "gigapump"), CaptureNamePolicy.displayNames(records))
    }

    @Test
    fun `taken keys include stored names and the numbered display names except the excluded session`() {
        val records = listOf(record("a", "runitback", "2026-10-10T09:00:00Z"), record("b", "runitback", "2026-10-10T10:00:00Z"), record("c", "solo", "2026-10-10T11:00:00Z"))

        assertEquals(setOf("runitback", "runitback (2)", "solo"), CaptureNamePolicy.takenKeys(records))
        assertEquals(setOf("runitback", "runitback (2)"), CaptureNamePolicy.takenKeys(records, excluding = "c"))
    }

    private fun record(id: String, name: String, createdAt: String) = CaptureSessionRecord(id = id, requestId = id, fingerprint = id, name = name,
        createdAt = Instant.parse(createdAt), wallet = null, walletAttemptId = "w-$id", marketAttemptId = null, ratesAttemptId = null)

    private companion object {
        const val MINT = "7mupUtwu17wevEfnyrE6XsWa1X9WWntAPeqdckd2pump"
    }
}
