package com.trenching.bot.console.mapper

import com.trenching.bot.common.WalletAddress
import com.trenching.bot.console.domain.CaptureSource
import com.trenching.bot.console.domain.DatasetDetail
import com.trenching.bot.console.domain.DatasetSource
import com.trenching.bot.replay.domain.DatasetRegistration
import com.trenching.bot.replay.domain.DatasetSummary
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import tools.jackson.databind.json.JsonMapper

class DatasetResponseMapperTest {
    private val json = JsonMapper.builder().build()
    private val mapper = DatasetResponseMapperImpl().also {
        ReflectionTestUtils.setField(it, "pairResponseMapper", PairResponseMapperImpl())
        ReflectionTestUtils.setField(it, "datasetSourceResponseMapper", DatasetSourceResponseMapperImpl())
    }
    private val summary = DatasetSummary("dataset-1", "Decu case dataset", WalletAddress.of(WALLET), 4, 2, 3, null, null, emptyList(),
        Instant.parse("2026-10-16T20:30:00Z"))

    @Test
    fun `a dataset detail adds the capture and case it came from`() {
        val detail = DatasetDetail(summary, DatasetSource("dataset-1", "Decu case dataset", CaptureSource("s1", "Decu · Pump.fun buys · 16 Oct 20:13", "case-1")))

        val body = json.readTree(json.writeValueAsString(mapper.toDetail(detail)))

        assertEquals("dataset-1", body["id"].asString())
        assertEquals("""{"datasetId":"dataset-1","datasetName":"Decu case dataset","captureId":"s1","captureDisplayName":"Decu · Pump.fun buys · 16 Oct 20:13","caseId":"case-1"}""",
            body["source"].toString())
    }

    @Test
    fun `list and registration responses are unchanged and carry no source`() {
        val listed = json.readTree(json.writeValueAsString(mapper.toResponse(summary)))
        val registered = json.readTree(json.writeValueAsString(mapper.toResponse(DatasetRegistration(summary, true))))

        assertEquals(listOf("id", "name", "trackedWallet", "schemaVersion", "transactionCount", "observationCount", "firstSlot", "lastSlot", "pairs",
            "createdAt"), listed.propertyNames().toList())
        assertFalse(registered["dataset"].has("source"))
    }

    private companion object {
        const val WALLET = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
    }
}
