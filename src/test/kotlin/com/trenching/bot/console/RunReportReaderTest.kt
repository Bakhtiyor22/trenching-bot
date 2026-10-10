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
        val dataset = requireNotNull(javaClass.getResourceAsStream("/replay/samples/dip-sample-v3.json")).use {
            ReplayDatasetLoader(json).load(it)
        }
        val config = SimulationConfig.of(BigDecimal("12345678901234.123456789"), 1, 1400, 2500, 1200, BigDecimal("12000.5"), 900, 7,
            BigDecimal("0.012345678901"))
        val report = json.writeValueAsString(ReplayRunner().run(dataset, config))

        val facts = reader.read(report)

        assertEquals(config, facts.config)
        assertEquals("12345678901234.123456789000", facts.config.notional.toPlainString())
        assertEquals("0.012345678901", facts.config.transactionCostSol.toPlainString())
    }

    @Test
    fun `reads configuration while ignoring scenario fields it does not need`() {
        val report = """
            {"runId":"run","configuration":{"latencySlots":1,"maxAgeSlots":2,"dipBps":1500,"takeProfitBps":3000,"stopLossBps":1500,
              "minMarketCapUsd":10000.000000000000,"horizonSlots":4500,
              "notional":0.100000000000,"quoteMint":{"value":"${MintAddress.WRAPPED_SOL.value}"},"transactionCostSol":0.001000000000,
              "strategyName":"dip-entry","strategyVersion":"1"},
             "scenarios":[
              {"intent":{"id":"intent-1","entryOffset":1},"outcome":{},"position":{},"exitUnavailable":"MISSING_OBSERVATION"},
              {"intent":{"id":"intent-2","entryOffset":2},"outcome":{},"position":null,"exitUnavailable":null}]}
        """.trimIndent()

        val facts = reader.read(report)

        assertEquals(SimulationConfig.mvp(), facts.config)
    }
}
