package com.trenching.bot.strategy

import com.trenching.bot.marketdata.domain.ObservedTrade
import com.trenching.bot.marketdata.domain.TradeSide
import com.trenching.bot.portfolio.PositionStore
import com.trenching.bot.replay.application.PersistedReplayService
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.strategy.domain.PriceMeasure
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.AgreedDipSample
import com.trenching.bot.support.DipSample
import com.trenching.bot.support.DipSampleV4
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import java.math.BigDecimal
import java.math.BigInteger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Import(PostgresTestDatabaseConfiguration::class)
class TradeIntentPersistenceIntegrationTest {
    @Autowired private lateinit var application: PersistedReplayService
    @Autowired private lateinit var intents: TradeIntentStore
    @Autowired private lateinit var positions: PositionStore
    @Autowired private lateinit var dataSource: DataSource

    @Test
    fun `a post-trade run with the wallet-sell rule stores the wallet, the reference observation, the sell, the signal size and the new reasons`() {
        val dataset = dataset()
        val report = ReplayRunner().run(dataset, SimulationConfig.agreed())

        val run = application.execute(dataset, SimulationConfig.agreed())

        val jdbc = JdbcTemplate(dataSource)
        val takeProfit = intentRow(jdbc, run.id, DipSample.TAKE_PROFIT)
        assertEquals(listOf<Any?>("WALLET_SOLD", dataset.trackedWallet.value, TAKE_PROFIT_SELL, 1001L),
            listOf(takeProfit["watch"], takeProfit["wallet"], takeProfit["wallet_sell_event_id"], takeProfit["wallet_sell_slot"]))
        assertEquals(assertNotNull(report.scenarios.single { it.intent.pair == DipSample.pair(DipSample.TAKE_PROFIT) }.intent.referenceObservationId),
            takeProfit["reference_observation_id"])
        val horizon = intentRow(jdbc, run.id, DipSample.HORIZON)
        assertEquals("REFERENCE_UNAVAILABLE", horizon["watch"])
        assertNull(horizon["reference_observation_id"])
        assertNull(horizon["wallet_sell_event_id"])
        assertNull(horizon["wallet_sell_slot"])
        assertEquals(0, BigDecimal("2025000000").compareTo(takeProfit["signal_lamports"] as BigDecimal))
        assertNull(horizon["signal_lamports"])
        assertEquals(listOf("WALLET_SOLD", "REFERENCE_UNAVAILABLE"), listOf(DipSample.TAKE_PROFIT, DipSample.HORIZON).map { cycle ->
            jdbc.queryForObject("select o.rejection_reason from paper_orders o join trade_intents i on i.id = o.id where i.run_id = ? and i.base_mint = ?",
                String::class.java, run.id, DipSample.mint(cycle).value)
        })
        val stopLoss = jdbc.queryForMap("select close_reason, exit_trigger_reason, exit_trigger_observation_id from positions where run_id = ? and base_mint = ?",
            run.id, DipSample.mint(DipSample.STOP_LOSS).value)
        assertEquals(listOf("WALLET_SOLD", "WALLET_SOLD", STOP_LOSS_SELL),
            listOf(stopLoss["close_reason"], stopLoss["exit_trigger_reason"], stopLoss["exit_trigger_observation_id"]))
        assertEquals(report.scenarios.map { it.intent }, intents.intents(run.id))
        assertEquals(report.scenarios.mapNotNull { it.position }, positions.positions(run.id))
    }

    @Test
    fun `a buy at the minimum signal size stores its size and the below-minimum reason and reads back as replayed`() {
        val dataset = AgreedDipSample.shape(DipSampleV4.dataset(), curvePayment = BigInteger.valueOf(1_876_543_209))
        val report = ReplayRunner().run(dataset, SimulationConfig.agreed())

        val run = application.execute(dataset, SimulationConfig.agreed())

        val jdbc = JdbcTemplate(dataSource)
        val takeProfit = intentRow(jdbc, run.id, DipSample.TAKE_PROFIT)
        assertEquals("SIGNAL_BELOW_MIN_SIZE", takeProfit["watch"])
        assertEquals(0, BigDecimal("1900000000").compareTo(takeProfit["signal_lamports"] as BigDecimal))
        assertEquals("SIGNAL_BELOW_MIN_SIZE", jdbc.queryForObject("select o.rejection_reason from paper_orders o join trade_intents i " +
            "on i.id = o.id where i.run_id = ? and i.base_mint = ?", String::class.java, run.id, DipSample.mint(DipSample.TAKE_PROFIT).value))
        assertEquals(report.scenarios.map { it.intent }, intents.intents(run.id))
    }

    @Test
    fun `an intent stored before the wallet column reads the wallet of its own source event`() {
        val dataset = dataset()
        // Its own configuration, so the rows aged here belong to no run another test reads.
        val config = SimulationConfig.of(BigDecimal("0.105"), 2, 2000, 5500, 4000, BigDecimal("10000"), 4500, 1, BigDecimal("0.001"),
            dipWindowMs = 30_000, maxHoldMs = 180_000, hardExitStartMs = 240_000, priceMeasure = PriceMeasure.POST_TRADE,
            followWalletSells = true)
        val expected = ReplayRunner().run(dataset, config).scenarios.map { it.intent }
        val run = application.execute(dataset, config)

        JdbcTemplate(dataSource).update("update trade_intents set wallet = null where run_id = ?", run.id)

        assertEquals(expected, intents.intents(run.id))
        assertEquals(setOf(dataset.trackedWallet), intents.intents(run.id).map { it.wallet }.toSet())
    }

    private fun intentRow(jdbc: JdbcTemplate, runId: String, cycle: Int): Map<String, Any?> = jdbc.queryForMap("""
        select watch, wallet, reference_observation_id, wallet_sell_event_id, wallet_sell_slot, signal_lamports
        from trade_intents where run_id = ? and base_mint = ?""".trimIndent(), runId, DipSample.mint(cycle).value)

    /**
     * The agreed sample with the tracked wallet selling the take-profit mint before its dip and the stop-loss mint while held, and
     * the horizon mint's buy left out of the market data.
     */
    private fun dataset(): ReplayDataset {
        val agreed = AgreedDipSample.dataset()
        val wallet = agreed.trackedWallet.value
        val horizon = DipSample.pair(DipSample.HORIZON)
        return agreed.copy(observations = agreed.observations
            .filterNot { it.pair == horizon && it.trade?.trader == wallet }
            .map { if (it.id in SELLS) it.copy(trade = ObservedTrade.of(wallet, TradeSide.SELL, BigInteger.ONE, BigInteger.ONE)) else it })
    }

    private companion object {
        const val TAKE_PROFIT_SELL = "fixture:TP:1001:0"
        const val STOP_LOSS_SELL = "fixture:SL:2003:0"
        val SELLS = setOf(TAKE_PROFIT_SELL, STOP_LOSS_SELL)
    }
}
