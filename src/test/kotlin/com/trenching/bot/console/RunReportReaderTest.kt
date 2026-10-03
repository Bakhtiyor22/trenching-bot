package com.trenching.bot.console

import com.trenching.bot.common.MintAddress
import com.trenching.bot.console.report.RunReportReader
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.strategy.domain.SimulationConfig
import java.math.BigDecimal
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

class RunReportReaderTest {
    private val json = JsonMapper.builder().addModule(kotlinModule()).build()
    private val reader = RunReportReader(json)

    @Test
    fun `reads the configuration of a replay report back exactly`() {
        val dataset = requireNotNull(javaClass.getResourceAsStream("/replay/samples/weekly-buy-v1.json")).use {
            ReplayDatasetLoader(json).load(it)
        }
        val config = SimulationConfig.of(BigDecimal("12345678901234.123456789012"), 7, 40, 3, 1, 9)
        val report = json.writeValueAsString(ReplayRunner().run(dataset, config))

        val facts = reader.read(report)

        assertEquals(config, facts.config)
        assertEquals("12345678901234.123456789012", facts.config.notional.toPlainString())
    }

    @Test
    fun `reads configuration while ignoring legacy scenario fields`() {
        val report = """
            {"runId":"run","configuration":{"feeBps":10,"holdingSlots":5,"maxAgeSlots":2,"maxWaitSlots":2,
              "notional":100.000000000000,"quoteMint":{"value":"${MintAddress.WRAPPED_SOL.value}"},"slippageBps":50,
              "strategyName":"confirmed-buy","strategyVersion":"1"},
             "scenarios":[
              {"intent":{"id":"intent-1","entryOffset":1},"outcome":{},"position":{},"exitUnavailable":"MISSING_OBSERVATION"},
              {"intent":{"id":"intent-2","entryOffset":2},"outcome":{},"position":null,"exitUnavailable":null}]}
        """.trimIndent()

        val facts = reader.read(report)

        assertEquals(SimulationConfig.mvp(), facts.config)
    }
}
