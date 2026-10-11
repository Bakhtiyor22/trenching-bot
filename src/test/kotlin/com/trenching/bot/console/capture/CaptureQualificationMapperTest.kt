package com.trenching.bot.console.capture

import com.trenching.bot.console.mapper.CaptureResponseMapperImpl
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.replay.domain.CaseClassification
import com.trenching.bot.replay.domain.DipVerdict
import com.trenching.bot.replay.domain.TargetTokenVerdict
import com.trenching.bot.strategy.domain.DipWatchOutcome
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

class CaptureQualificationMapperTest {
    private val json = JsonMapper.builder().addModule(kotlinModule()).build()
    private val mapper = CaptureQualificationMapper(CaptureResponseMapperImpl())

    private fun tree(verdict: TargetTokenVerdict): JsonNode = json.readTree(json.writeValueAsString(mapper.map(verdict)))
    private fun JsonNode.keys(): List<String> = propertyNames().toList()

    @Test
    fun `a qualifying verdict has the contract's field names with exact decimal strings and numeric slots`() {
        val node = tree(CaptureTokenFixtures.takeProfit)

        assertEquals(listOf("classification", "capture", "dip", "entry", "exit", "replay", "provenance"), node.keys())
        assertEquals("QUALIFYING_DIP", node["classification"].asString())
        assertEquals(listOf("state", "stage", "reason"), node["capture"].keys())
        assertEquals("COMPLETE", node["capture"]["state"].asString())

        val dip = node["dip"]
        assertEquals(listOf("state", "reason", "triggerSlot", "triggerPrice", "marketCapUsd", "rate", "observationId"), dip.keys())
        assertEquals("QUALIFIED", dip["state"].asString())
        assertTrue(dip["triggerSlot"].isNumber)
        assertEquals(1002L, dip["triggerSlot"].asLong())
        assertEquals("0.0000000123", dip["triggerPrice"].asString())
        assertEquals("4100.50", dip["marketCapUsd"].asString())
        assertEquals("152.31", dip["rate"].asString())
        assertEquals("observation-1002", dip["observationId"].asString())

        val entry = node["entry"]
        assertEquals(listOf("state", "reason", "slot", "executionPrice", "observationId"), entry.keys())
        assertEquals("FILLED", entry["state"].asString())
        assertEquals("0.0000000125", entry["executionPrice"].asString())

        val exit = node["exit"]
        assertEquals(listOf("state", "reason", "slot", "realizedPnl"), exit.keys())
        assertEquals("CLOSED", exit["state"].asString())
        assertEquals("TAKE_PROFIT", exit["reason"].asString())
        assertEquals(1006L, exit["slot"].asLong())
        assertEquals("0.012500000", exit["realizedPnl"].asString())

        assertEquals(listOf("state", "category"), node["replay"].keys())
        assertEquals("RAN", node["replay"]["state"].asString())

        val provenance = node["provenance"]
        assertEquals(listOf("datasetChecksum", "runId", "configurationHash", "engineVersion", "referencePrice", "firstBuySignature", "firstBuySlot"),
            provenance.keys())
        assertEquals("0.000000025", provenance["referencePrice"].asString())
        assertEquals(CaptureTokenFixtures.BUY.value, provenance["firstBuySignature"].asString())
        assertEquals(CaptureTokenFixtures.BUY_SLOT, provenance["firstBuySlot"].asLong())
    }

    @Test
    fun `a rejected entry and an open exit report the rejection names`() {
        val node = tree(CaptureTokenFixtures.notFilledOpen)

        assertEquals("NOT_FILLED", node["entry"]["state"].asString())
        assertEquals(RejectionReason.entries.first().name, node["entry"]["reason"].asString())
        assertTrue(node["entry"]["slot"].isNull)
        assertEquals("OPEN", node["exit"]["state"].asString())
        assertEquals(RejectionReason.entries.last().name, node["exit"]["reason"].asString())
        assertTrue(node["exit"]["realizedPnl"].isNull)
    }

    @Test
    fun `a dip that did not qualify reports the watch outcome name and no entry or exit`() {
        val node = tree(CaptureTokenFixtures.takeProfit.copy(classification = CaseClassification.NEGATIVE_CASE,
            dip = DipVerdict.NotQualified(DipWatchOutcome.NO_DIP), entry = null, exit = null))

        assertEquals("NEGATIVE_CASE", node["classification"].asString())
        assertEquals("NOT_QUALIFIED", node["dip"]["state"].asString())
        assertEquals("NO_DIP", node["dip"]["reason"].asString())
        assertTrue(node["dip"]["triggerSlot"].isNull)
        assertTrue(node["entry"].isNull)
        assertTrue(node["exit"].isNull)
    }

    @Test
    fun `a replay failure and an inspection-only verdict report their states`() {
        val failed = tree(CaptureTokenFixtures.replayFailed)
        assertEquals("REPLAY_FAILED", failed["classification"].asString())
        assertEquals("NOT_EVALUATED", failed["dip"]["state"].asString())
        assertEquals("FAILED", failed["replay"]["state"].asString())
        assertEquals("DATASET_INVALID", failed["replay"]["category"].asString())
        assertEquals("COMPLETE", failed["capture"]["state"].asString())

        val pending = tree(CaptureTokenFixtures.inspectionOnly())
        assertEquals("INCOMPLETE", pending["capture"]["state"].asString())
        assertEquals("EXPORT", pending["capture"]["stage"].asString())
        assertEquals("NOT_EXPORTED", pending["capture"]["reason"].asString())
        assertEquals("NOT_RUN", pending["replay"]["state"].asString())
        assertTrue(pending["provenance"]["runId"].isNull)

        val awaiting = tree(CaptureTokenFixtures.inspectionOnly(datasetExported = true))
        assertEquals("COMPLETE", awaiting["capture"]["state"].asString())
        assertEquals("EXPORT", awaiting["capture"]["stage"].asString())
        assertEquals("QUALIFICATION_PENDING", awaiting["capture"]["reason"].asString())
    }
}
