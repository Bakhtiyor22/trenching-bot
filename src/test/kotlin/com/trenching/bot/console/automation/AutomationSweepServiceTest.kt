package com.trenching.bot.console.automation

import com.trenching.bot.console.automation.AutomationFixtures.EVENING
import com.trenching.bot.console.automation.AutomationFixtures.properties
import com.trenching.bot.console.automation.application.AutomationSweepService
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.domain.AutomationFailures
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationLimits
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.domain.AutomationStep
import com.trenching.bot.console.automation.domain.EvaluationScoring
import com.trenching.bot.console.automation.domain.SweepPlans
import com.trenching.bot.console.automation.domain.WindowItem
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSweepRunEntity
import com.trenching.bot.console.capture.MutableClock
import com.trenching.bot.papertrading.PaperOrderStore
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.domain.PositionState
import com.trenching.bot.replay.ReplayDatasetCatalog
import com.trenching.bot.replay.SimulationRuns
import com.trenching.bot.replay.domain.DatasetInvalidException
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.RunStart
import com.trenching.bot.replay.domain.RunStorage
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetWriter
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.AgreedDipSample
import com.trenching.bot.support.DipSample
import com.trenching.bot.support.DipSampleV4
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Pageable
import tools.jackson.databind.json.JsonMapper

/**
 * Plan Rules 21–23 and Acceptance Criterion 6: the sweep over the real run service and catalog. Each dataset is the take-profit
 * cycle of the agreed-strategy sample alone, the shape of a wallet + token capture. Under `decu-sweep-1` it closes at take
 * profit for most entries, sees no dip with the 25% dip (V02), stays open to the horizon with the +80% take profit (V04), and
 * is below the $20,000 minimum market cap (V09).
 */
class AutomationSweepServiceTest : AutomationDatabaseTest() {
    @Autowired private lateinit var catalog: ReplayDatasetCatalog
    @Autowired private lateinit var simulationRuns: SimulationRuns
    @Autowired private lateinit var paperOrders: PaperOrderStore

    private val clock = MutableClock(EVENING.plus(Duration.ofHours(1)))
    private val plan = SweepPlans.DECU_SWEEP_1

    @Test
    fun `every entry runs in label order as a dataset-referenced run, each with one row, and the item is simulated`() {
        val runs = RecordingRuns(simulationRuns)
        captured(1, register("take-profit", takeProfit()))

        service(runs).sweep()

        assertEquals(plan.entries.map { it.config }, runs.started.map { it.first })
        assertTrue(runs.started.all { it.second == RunStorage.DATASET_REFERENCE })
        val rows = rows(1)
        assertEquals(plan.entries.map { it.label }, rows.map { it.label })
        rows.forEach { row ->
            val run = assertNotNull(simulationRuns.find(assertNotNull(row.runId)), "run ${row.label} opens by its id")
            assertEquals(RunStorage.DATASET_REFERENCE, run.storage)
            assertEquals(run.configurationHash, row.configurationHash)
            assertEquals(run.engineVersion, row.engineVersion)
            assertEquals("session-1", row.sessionId)
        }
        val item = items.findOneById("item-1")
        assertEquals(AutomationItemState.SIMULATED, item?.state)
        assertEquals(AutomationStep.SWEEP, item?.step)
    }

    @Test
    fun `each row carries its cycle's outcome, signal and P&L`() {
        captured(1, register("take-profit", takeProfit()))

        service().sweep()

        val byLabel = rows(1).associateBy { it.label }
        val agreed = byLabel.getValue("V00")
        assertEquals("TAKE_PROFIT", agreed.outcome)
        assertEquals(PositionState.CLOSED, agreed.positionState)
        assertTrue(agreed.isSignal)
        assertEquals(BigDecimal(DipSample.TAKE_PROFIT_PNL), agreed.realizedPnl)
        assertEquals(0, simulationRuns.find(assertNotNull(agreed.runId))?.realizedPnl?.compareTo(agreed.realizedPnl))
        assertEquals(0, agreed.worstCasePnl.compareTo(agreed.realizedPnl))
        listOf("V02" to "NO_DIP", "V09" to "REFERENCE_BELOW_MIN_MARKET_CAP").forEach { (label, outcome) ->
            val skipped = byLabel.getValue(label)
            assertEquals(outcome, skipped.outcome)
            assertNull(skipped.positionState)
            assertTrue(skipped.isSignal, "a later skip is a signal")
            assertEquals(0, skipped.realizedPnl.signum())
            assertEquals(0, skipped.worstCasePnl.signum())
        }
    }

    @Test
    fun `a position still open counts at minus its entry's whole cost`() {
        captured(1, register("take-profit", takeProfit()))

        service().sweep()

        val open = rows(1).single { it.label == "V04" }
        val fill = paperOrders.fills(assertNotNull(open.runId)).single()
        assertEquals("HORIZON_REACHED", open.outcome)
        assertEquals(PositionState.OPEN, open.positionState)
        assertTrue(open.isSignal)
        assertEquals(0, open.realizedPnl.signum())
        assertEquals(BigDecimal("-0.102250000000"), open.worstCasePnl)
        assertEquals(0, open.worstCasePnl.compareTo(fill.costBasis.negate()))
        assertEquals(0, fill.costBasis.compareTo(BigDecimal("0.1").add(fill.fee).add(fill.priorityFee)))
    }

    @Test
    fun `a rerun replays only the entries without a row, gets the same run ids and adds no rows`() {
        captured(1, register("take-profit", takeProfit()))
        service().sweep()
        val first = rows(1).associate { it.label to it.runId }
        sweepRuns.deleteAll(rows(1).filter { it.label in setOf("V03", "V04") })
        items.findOneById("item-1")?.let { it.state = AutomationItemState.CAPTURED; it.step = AutomationStep.REGISTER; items.save(it) }
        val runs = RecordingRuns(simulationRuns)

        service(runs).sweep()
        service(runs).sweep()

        assertEquals(listOf(plan.entries[3].config, plan.entries[4].config), runs.started.map { it.first })
        assertTrue(runs.starts.none { it.created }, "the runs already existed")
        assertEquals(first, rows(1).associate { it.label to it.runId })
        assertEquals(10, sweepRuns.count())
        assertEquals(AutomationItemState.SIMULATED, items.findOneById("item-1")?.state)
    }

    @Test
    fun `an entry the replay refuses is stored with its code, no run and no P&L, and leaves the item unscored for it`() {
        captured(1, register("schema-v3", DipSample.load().only(DipSample.TAKE_PROFIT)))

        service().sweep()

        val rows = rows(1)
        assertEquals(10, rows.size)
        rows.forEach { row ->
            assertEquals("SELL_CHECK_NEEDS_TRADES", row.outcome)
            assertNull(row.runId)
            assertNull(row.configurationHash)
            assertFalse(row.isSignal)
            assertEquals(0, row.realizedPnl.signum())
            assertEquals(0, row.worstCasePnl.signum())
        }
        assertEquals(1, tally("V00").unscored)
        assertEquals(AutomationItemState.SIMULATED, items.findOneById("item-1")?.state)
    }

    @Test
    fun `a buy whose post-trade reference is unavailable is no signal and unscored`() {
        captured(1, register("no-reference", DipSampleV4.dataset().only(DipSample.TAKE_PROFIT)))

        service().sweep()

        rows(1).forEach { row ->
            assertEquals("REFERENCE_UNAVAILABLE", row.outcome)
            assertNotNull(row.runId)
            assertFalse(row.isSignal)
        }
        val tally = tally("V00")
        assertEquals(listOf(0, 0, 1), listOf(tally.signals, tally.scored, tally.unscored), "a run without a usable result is unscored only")
    }

    @Test
    fun `a buy at or below the minimum signal size is a valid non-signal, and the 1,5 SOL entry still trades it`() {
        val small = AgreedDipSample.shape(DipSampleV4.dataset(), curvePayment = BigInteger.valueOf(1_876_543_209)).only(DipSample.TAKE_PROFIT)
        captured(1, register("below-minimum", small))

        service().sweep()

        val byLabel = rows(1).associateBy { it.label }
        (plan.entries.map { it.label } - "V08").forEach { label ->
            assertEquals(RejectionReason.SIGNAL_BELOW_MIN_SIZE.name, byLabel.getValue(label).outcome, label)
            assertFalse(byLabel.getValue(label).isSignal, label)
        }
        assertEquals("TAKE_PROFIT", byLabel.getValue("V08").outcome)
        val tally = tally("V00")
        assertEquals(listOf(0, 1, 0), listOf(tally.signals, tally.scored, tally.unscored))
        assertEquals(1, tally("V08").signals)
    }

    @Test
    fun `an unexpected failure stores no row, the item is swept again after a delay under its next attempt, then fails`() {
        captured(1, register("take-profit", takeProfit()))
        val broken = FailingRuns(simulationRuns)

        service(broken).sweep()
        val retrying = items.findOneById("item-1")
        clock.now = clock.now.plus(AutomationLimits.SWEEP_RETRY_DELAY).minusSeconds(1)
        service(broken).sweep()
        val startsEarly = broken.starts
        clock.now = clock.now.plusSeconds(1)
        service(broken).sweep()

        assertEquals(0, sweepRuns.count())
        assertEquals(AutomationItemState.CAPTURED, retrying?.state)
        assertEquals(AutomationStep.SWEEP, retrying?.step)
        assertEquals(2, retrying?.stepAttempt)
        assertEquals(AutomationFailures.SWEEP_FAILED, retrying?.failureCode)
        assertEquals(EVENING.plus(Duration.ofHours(1)).plus(AutomationLimits.SWEEP_RETRY_DELAY), retrying?.nextActionAt)
        assertEquals(plan.entries.size, startsEarly, "not swept before its delay")
        val failed = items.findOneById("item-1")
        assertEquals(AutomationItemState.FAILED, failed?.state)
        assertEquals(2, failed?.stepAttempt)
        assertEquals("replay broke", failed?.failureMessage)
        assertNull(failed?.nextActionAt)
    }

    @Test
    fun `an item waiting after a failed sweep leaves the tick's slots to the items behind it`() {
        val datasetId = register("take-profit", takeProfit())
        (1..AutomationLimits.SWEEP_ITEMS_PER_TICK + 1).forEach { n -> captured(n, datasetId, blockOffsetSeconds = n.toLong()) }
        items.findOneById("item-1")?.let {
            it.step = AutomationStep.SWEEP
            it.stepAttempt = 2
            it.nextActionAt = clock.now.plusSeconds(60)
            items.save(it)
        }

        service().sweep()

        val simulated = items.findAll().filter { it.state == AutomationItemState.SIMULATED }.map { it.id }.toSet()
        assertEquals((2..AutomationLimits.SWEEP_ITEMS_PER_TICK + 1).map { "item-$it" }.toSet(), simulated)
        assertEquals(AutomationItemState.CAPTURED, items.findOneById("item-1")?.state)
    }

    @Test
    fun `a dataset gone from the catalog is stored as a DATASET_NOT_FOUND refusal, never retried`() {
        captured(1, "f".repeat(64))

        service().sweep()

        assertEquals(plan.entries.map { "DATASET_NOT_FOUND" }, rows(1).map { it.outcome })
        assertTrue(rows(1).all { it.runId == null && !it.isSignal })
        assertEquals(AutomationItemState.SIMULATED, items.findOneById("item-1")?.state)
        assertEquals(1, tally("V00").unscored)
    }

    @Test
    fun `an unreadable dataset is stored as a DATASET_INVALID refusal`() {
        captured(1, register("take-profit", takeProfit()))
        val unreadable = object : SimulationRuns by simulationRuns {
            override fun start(datasetId: String, config: SimulationConfig, storage: RunStorage): RunStart =
                throw DatasetInvalidException("Replay dataset cannot be read datasetId=$datasetId")
        }

        service(unreadable).sweep()

        assertEquals(plan.entries.map { "DATASET_INVALID" }, rows(1).map { it.outcome })
        assertEquals(AutomationItemState.SIMULATED, items.findOneById("item-1")?.state)
    }

    @Test
    fun `a tick sweeps the oldest captured buys first, a few at a time, and nothing with capture off`() {
        val datasetId = register("take-profit", takeProfit())
        (1..AutomationLimits.SWEEP_ITEMS_PER_TICK + 1).forEach { n -> captured(n, datasetId, blockOffsetSeconds = 100L - n) }

        service(properties = properties(captureEnabled = false)).sweep()
        val untouched = sweepRuns.count()
        service().sweep()

        assertEquals(0, untouched)
        val simulated = items.findAll().filter { it.state == AutomationItemState.SIMULATED }.map { it.id }.toSet()
        assertEquals((2..AutomationLimits.SWEEP_ITEMS_PER_TICK + 1).map { "item-$it" }.toSet(), simulated)
        assertEquals(AutomationItemState.CAPTURED, items.findOneById("item-1")?.state)
    }

    @Test
    fun `a tick sweeps live captured buys before those of a backfill, however old the backfill's are`() {
        val datasetId = register("take-profit", takeProfit())
        captured(1, datasetId, blockOffsetSeconds = -86_400)
        items.findOneById("item-1")?.let { items.save(it.also { backfillItem -> backfillItem.backfillId = "backfill" }) }
        (2..AutomationLimits.SWEEP_ITEMS_PER_TICK + 1).forEach { n -> captured(n, datasetId, blockOffsetSeconds = 100L + n) }

        service().sweep()

        val simulated = items.findAll().filter { it.state == AutomationItemState.SIMULATED }.map { it.id }.toSet()
        assertEquals((2..AutomationLimits.SWEEP_ITEMS_PER_TICK + 1).map { "item-$it" }.toSet(), simulated)
        assertEquals(AutomationItemState.CAPTURED, items.findOneById("item-1")?.state, "the backfill's item waits for a tick with a free slot")
    }

    private fun service(runs: SimulationRuns = simulationRuns, properties: AutomationProperties = properties(captureEnabled = true)) =
        AutomationSweepService(sweepTransaction, pipelineTransaction, runs, runCycles, properties, clock)

    private fun takeProfit(): ReplayDataset = AgreedDipSample.dataset().only(DipSample.TAKE_PROFIT)

    private fun ReplayDataset.only(cycle: Int): ReplayDataset {
        val pair = DipSample.pair(cycle)
        return copy(transactions = transactions.filter { it.pair == pair }, observations = observations.filter { it.pair == pair },
            coverage = coverage.filter { it.pair == pair })
    }

    private fun register(name: String, dataset: ReplayDataset): String =
        catalog.register(name, ReplayDatasetWriter(JsonMapper.builder().build()).write(dataset).byteInputStream()).dataset.id

    private fun captured(n: Int, datasetId: String, blockOffsetSeconds: Long = 0) {
        if (!sessions.existsById("session-1")) sessions.save(AutomationRows.session("session-1", AutomationSessionState.OPEN, EVENING))
        items.save(AutomationRows.item(n, "session-1", AutomationItemState.CAPTURED, blockTime = EVENING.plusSeconds(blockOffsetSeconds)).apply {
            step = AutomationStep.REGISTER
            stepAttempt = 1
            captureSessionId = "capture-$n"
            this.datasetId = datasetId
        })
    }

    private fun rows(n: Int): List<AutomationSweepRunEntity> = sweepRuns.findAllByItemIdOrderByPlanIdAscLabelAsc("item-$n", Pageable.unpaged())

    /** The stored rows of every item, scored for [label] as an evaluation would. */
    private fun tally(label: String) = EvaluationScoring.tally(label, items.findAll().map { item: AutomationItemEntity ->
        WindowItem(item.id, item.state, sweepRuns.findAll().filter { it.itemId == item.id }.map(records::toSweepScore).associateBy { it.label })
    })

    /** A run service whose every start fails unexpectedly, counting the starts. */
    private class FailingRuns(private val delegate: SimulationRuns) : SimulationRuns by delegate {
        var starts = 0

        override fun start(datasetId: String, config: SimulationConfig, storage: RunStorage): RunStart {
            starts += 1
            throw IllegalStateException("replay broke")
        }
    }

    /** The real run service, recording each start's configuration, storage and result. */
    private class RecordingRuns(private val delegate: SimulationRuns) : SimulationRuns by delegate {
        val started = mutableListOf<Pair<SimulationConfig, RunStorage>>()
        val starts = mutableListOf<RunStart>()

        override fun start(datasetId: String, config: SimulationConfig, storage: RunStorage): RunStart {
            started += config to storage
            return delegate.start(datasetId, config, storage).also { starts += it }
        }
    }
}
