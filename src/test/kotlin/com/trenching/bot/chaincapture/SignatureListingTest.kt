package com.trenching.bot.chaincapture

import com.trenching.bot.common.Base58
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class SignatureListingTest {
    private val signature = Base58.encode(ByteArray(64) { 5 })

    @Test
    fun `an entry keeps the listing's block time, and null when the listing has none`() {
        val entries = SignatureListing.parse(testMapper.readTree("""[
            {"signature":"$signature","slot":12,"err":null,"blockTime":1790985600},
            {"signature":"$signature","slot":11,"err":null,"blockTime":null},
            {"signature":"$signature","slot":10,"err":null}
        ]"""))

        assertEquals(Instant.ofEpochSecond(1790985600), entries[0].blockTime)
        assertNull(entries[1].blockTime)
        assertNull(entries[2].blockTime)
    }

    @Test
    fun `a block time that is not a whole number of seconds is refused`() {
        val failure = assertFailsWith<CaptureValidationException> {
            SignatureListing.parse(testMapper.readTree("""[{"signature":"$signature","slot":12,"err":null,"blockTime":"soon"}]"""))
        }

        assertEquals("SIGNATURE_ENTRY", failure.category)
    }

    @Test
    fun `entries are the same listing entry whatever block time they carry`() {
        val listed = SignatureListingEntry(signature, 12, failed = false, blockTime = Instant.ofEpochSecond(1790985600))
        val rebuilt = SignatureListingEntry(signature, 12, failed = false)

        assertEquals(rebuilt, listed)
        assertEquals(rebuilt.hashCode(), listed.hashCode())
    }
}
