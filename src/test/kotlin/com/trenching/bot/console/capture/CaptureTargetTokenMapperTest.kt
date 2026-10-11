package com.trenching.bot.console.capture

import com.trenching.bot.common.Slot
import com.trenching.bot.console.mapper.CaptureResponseMapperImpl
import com.trenching.bot.replay.domain.ActualFirstBuyClaim
import com.trenching.bot.walletactivity.ActualFirstBuy
import com.trenching.bot.walletactivity.FirstBuyGap
import com.trenching.bot.walletactivity.TargetTokenEvidence
import com.trenching.bot.walletactivity.TokenAccountFirstBuy
import com.trenching.bot.walletactivity.TokenAccountGap
import com.trenching.bot.walletactivity.VerificationBoundHit
import com.trenching.bot.walletactivity.VerificationLimit
import com.trenching.bot.walletactivity.VerificationListing
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

class CaptureTargetTokenMapperTest {
    private val json = JsonMapper.builder().addModule(kotlinModule()).build()
    private val mapper = CaptureTargetTokenMapper(CaptureResponseMapperImpl())

    private fun tree(evidence: TargetTokenEvidence): JsonNode = json.readTree(json.writeValueAsString(mapper.map(evidence)))
    private fun JsonNode.keys(): List<String> = propertyNames().toList()
    private val actualKeys = listOf("state", "signature", "slot", "creationSignature", "creationSlot", "creationTime", "reason", "blocking", "claim", "bound")

    @Test
    fun `a proven first buy carries the addresses, both results and the coverage claim`() {
        val node = tree(CaptureTokenFixtures.evidence())

        assertEquals(listOf("mint", "tokenProgram", "tokenAccount", "pumpAccumulator", "pumpSwapAccumulator", "tokenAccountFirstBuy", "actualFirstBuy"),
            node.keys())
        assertEquals(CaptureTokenFixtures.MINT, node["mint"].asString())

        val tokenAccount = node["tokenAccountFirstBuy"]
        assertEquals(listOf("state", "signature", "slot", "reason", "blocking"), tokenAccount.keys())
        assertEquals("FOUND", tokenAccount["state"].asString())
        assertEquals(CaptureTokenFixtures.BUY.value, tokenAccount["signature"].asString())
        assertEquals(CaptureTokenFixtures.BUY_SLOT, tokenAccount["slot"].asLong())
        assertTrue(tokenAccount["blocking"].isEmpty)

        val actual = node["actualFirstBuy"]
        assertEquals(actualKeys, actual.keys())
        assertEquals("PROVEN", actual["state"].asString())
        assertEquals(CaptureTokenFixtures.CREATION.value, actual["creationSignature"].asString())
        assertEquals(CaptureTokenFixtures.CREATION_SLOT, actual["creationSlot"].asLong())
        assertEquals("2026-10-01T00:00:00Z", actual["creationTime"].asString())
        assertEquals(ActualFirstBuyClaim.of(CaptureTokenFixtures.proven), actual["claim"].asString())
        assertTrue(actual["bound"].isNull)
        assertTrue(actual["reason"].isNull)
    }

    @Test
    fun `a capped verification reports the limit and how far each listing got, without a claim`() {
        val bound = VerificationBoundHit(VerificationLimit.FETCHES,
            mapOf(VerificationListing.MINT to 3, VerificationListing.PUMP_ACCUMULATOR to 1, VerificationListing.PUMPSWAP_ACCUMULATOR to 1),
            mapOf(VerificationListing.MINT to 2400, VerificationListing.PUMP_ACCUMULATOR to 12, VerificationListing.PUMPSWAP_ACCUMULATOR to 0),
            500, mapOf(VerificationListing.MINT to 1000L, VerificationListing.PUMP_ACCUMULATOR to 950L, VerificationListing.PUMPSWAP_ACCUMULATOR to null))
        val actual = tree(CaptureTokenFixtures.evidence(ActualFirstBuy.Unproven(FirstBuyGap.VERIFICATION_BOUND, listOf(CaptureTokenFixtures.CREATION), bound)))["actualFirstBuy"]

        assertEquals(actualKeys, actual.keys())
        assertEquals("UNPROVEN", actual["state"].asString())
        assertEquals("VERIFICATION_BOUND", actual["reason"].asString())
        assertEquals(listOf(CaptureTokenFixtures.CREATION.value), actual["blocking"].values().map { it.asString() })
        assertTrue(actual["claim"].isNull)
        val capped = actual["bound"]
        assertEquals(listOf("limit", "pages", "entries", "fetches", "oldestSlot"), capped.keys())
        assertEquals("FETCHES", capped["limit"].asString())
        assertEquals(listOf("MINT", "PUMP_ACCUMULATOR", "PUMPSWAP_ACCUMULATOR"), capped["pages"].keys())
        assertEquals(2400, capped["entries"]["MINT"].asInt())
        assertEquals(500, capped["fetches"].asInt())
        assertEquals(950L, capped["oldestSlot"]["PUMP_ACCUMULATOR"].asLong())
        assertTrue(capped["oldestSlot"]["PUMPSWAP_ACCUMULATOR"].isNull)
    }

    @Test
    fun `an earlier buy outside the token account and an unverified buy report their states`() {
        val outside = tree(CaptureTokenFixtures.evidence(ActualFirstBuy.OutsideTokenAccount(CaptureTokenFixtures.CREATION, Slot.of(950))))["actualFirstBuy"]
        assertEquals("OUTSIDE_TOKEN_ACCOUNT", outside["state"].asString())
        assertEquals(CaptureTokenFixtures.CREATION.value, outside["signature"].asString())
        assertEquals(950L, outside["slot"].asLong())
        assertTrue(outside["claim"].isNull)

        val node = tree(CaptureTokenFixtures.evidence(ActualFirstBuy.NotVerified,
            TokenAccountFirstBuy.NotEstablished(TokenAccountGap.EARLIER_UNKNOWN_RECORD, listOf(CaptureTokenFixtures.CREATION))))
        assertEquals("NOT_VERIFIED", node["actualFirstBuy"]["state"].asString())
        assertEquals("NOT_ESTABLISHED", node["tokenAccountFirstBuy"]["state"].asString())
        assertEquals("EARLIER_UNKNOWN_RECORD", node["tokenAccountFirstBuy"]["reason"].asString())
        assertEquals(listOf(CaptureTokenFixtures.CREATION.value), node["tokenAccountFirstBuy"]["blocking"].values().map { it.asString() })

        assertEquals("NO_BUY", tree(CaptureTokenFixtures.evidence(ActualFirstBuy.NotVerified, TokenAccountFirstBuy.NoBuy))["tokenAccountFirstBuy"]["state"].asString())
    }
}
