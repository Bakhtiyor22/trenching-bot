package com.trenching.bot.replay

import com.trenching.bot.common.MintAddress
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import com.trenching.bot.support.runRowCounts
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.ObjectMapper

/**
 * The dataset `exportRecordedReplay` produced from the user's live captures: Decu's one decoded Pump.fun buy at slot
 * 453004996 and the 17 same-mint bonding-curve trades in its window [453004997, 453005008], the last at 453005007. Expected economics are hand-checked
 * from those observed prices with the MVP model (100 notional, 10 bps fee, 50 bps slippage, hold 5, wait 2).
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Import(PostgresTestDatabaseConfiguration::class)
class RecordedReplayIntegrationTest {
    @Autowired private lateinit var runs: SimulationRuns
    @Autowired private lateinit var catalog: ReplayDatasetCatalog
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var dataSource: DataSource

    @Test
    fun `the recorded real dataset imports by checksum and replays to the hand-checked closes idempotently`() {
        val registration = register("decu-recorded")
        assertEquals(CHECKSUM, registration.dataset.id)
        assertFalse(register("same content, other name").created)

        val first = runs.start(CHECKSUM, SimulationConfig.mvp())
        val run = first.run
        assertEquals(MintAddress.WRAPPED_SOL, run.quoteMint)
        assertEquals(listOf(3, 3, 0, 0, 3), listOf(run.scenarioCount, run.filledCount, run.rejectedCount, run.openCount, run.closedCount))
        assertEquals("-3.580241077133", run.realizedPnl.toPlainString())

        val scenarios = json.readTree(runs.report(run.id)).path("scenarios").toList()
        assertEquals(listOf(1, 2, 3), scenarios.map { it.path("intent").path("entryOffset").asInt() })
        assertEquals(listOf(trade("2YHkUxxjxax3y2f8efvMgzboRQ6XDMQpqck7pNpBbEawhFhgAzB32Lh7eRX7RhfkvgjARf2kHgQJWz7rZPDnhvxM", 4, 15),
            trade("3dGw1o8DFaMd1SMLz7PA9uWm54m9ByaKNLAH35m26owRWiDDWQKAdE1siqiLtUXnW3Dj4AsGrU1cKR8UjH21qYM6", 1, 7),
            trade("593Sk2g8jiCKSkhE2tZHUJwneNr6nNtpNzanwuwhHDbMeiuiTADmwHfv7uAj1ApvKwtfvgr74TCNSBRS494HzpKJ", 1, 7)),
            scenarios.map { it.path("position").path("fill").path("observationId").asString() }.map(::shortTrade))
        val closes = scenarios.map { it.path("position").path("close") }
        assertEquals(listOf(453005002L, 453005003L, 453005005L), closes.map { it.path("slot").path("value").asLong() })
        assertEquals(List(3) { "MAX_HOLDING_SLOTS" }, closes.map { it.path("reason").asString() })
        assertEquals(listOf("-0.469035946332", "-0.689058352601", "-2.4221467782"),
            closes.map { it.path("realizedPnl").decimalValue().stripTrailingZeros().toPlainString() })

        val stored = runRowCounts(dataSource, run.id)
        assertEquals(listOf(1, 1, 17, 3, 3, 3, 3), stored)
        val again = runs.start(CHECKSUM, SimulationConfig.mvp())
        assertFalse(again.created)
        assertEquals(run, again.run)
        assertEquals(stored, runRowCounts(dataSource, run.id))
        assertTrue(first.created)
    }

    private fun register(name: String) =
        requireNotNull(javaClass.getResourceAsStream("/replay/recorded/decu-pumpfun-buy-v1.json")).use { catalog.register(name, it) }

    private fun trade(signature: String, topLevel: Int, inner: Int) = "$signature:$topLevel:$inner"

    /** Drops the source prefix and mint suffix of an observation id, keeping the trade's signature and instruction indexes. */
    private fun shortTrade(id: String) = id.removePrefix("pumpfun-bonding-curve-trade/v1:").removeSuffix(":$MINT")

    private companion object {
        const val CHECKSUM = "3a4771170535f8c6b8d9828e01c61bee876938105eb65f532338285129d12e2d"
        const val MINT = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
    }
}
