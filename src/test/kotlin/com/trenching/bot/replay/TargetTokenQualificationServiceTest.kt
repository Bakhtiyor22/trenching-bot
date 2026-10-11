package com.trenching.bot.replay

import com.trenching.bot.common.Slot
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.domain.CloseReason
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.replay.application.TargetTokenQualificationService
import com.trenching.bot.replay.domain.CaptureVerdict
import com.trenching.bot.replay.domain.CaptureVerdictStage
import com.trenching.bot.replay.domain.CaptureVerdictState
import com.trenching.bot.replay.domain.CaseClassification
import com.trenching.bot.replay.domain.DipVerdict
import com.trenching.bot.replay.domain.EntryVerdict
import com.trenching.bot.replay.domain.ExitVerdict
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.ReplayVerdict
import com.trenching.bot.replay.domain.TargetTokenVerdict
import com.trenching.bot.replay.domain.VerdictProvenance
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetWriter
import com.trenching.bot.strategy.domain.DipWatchOutcome
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import com.trenching.bot.walletactivity.TargetTokenEvidence
import com.trenching.bot.walletactivity.domain.WalletTransaction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper

class TargetTokenQualificationServiceTest {
    @TempDir lateinit var root: Path
    private val mapper = JsonMapper.builder().build()
    private val sample = DipSample.load()
    private val paths get() = CapturePaths(root.resolve("wallet"), root.resolve("market"), root.resolve("rates"))

    @Test
    fun `a take profit cycle is a qualifying dip with the report's fill, exit and pnl`() {
        val dataset = dataset(DipSample.TAKE_PROFIT)
        val verdict = qualify(DipSample.TAKE_PROFIT, dataset)
        val report = ReplayRunner().run(load(dataset), SimulationConfig.mvp())
        val scenario = report.scenarios.single()
        val fill = (scenario.outcome as FillOutcome.Filled).fill
        val close = assertNotNull(assertNotNull(scenario.position).close)
        val trigger = assertNotNull(scenario.intent.trigger)
        val buy = firstBuy(DipSample.TAKE_PROFIT)
        val evidence = evidence(DipSample.TAKE_PROFIT)
        assertEquals(TargetTokenVerdict(
            classification = CaseClassification.QUALIFYING_DIP,
            capture = CaptureVerdict(CaptureVerdictState.COMPLETE),
            tokenAccountFirstBuy = evidence.tokenAccountFirstBuy,
            actualFirstBuy = evidence.actualFirstBuy,
            dip = DipVerdict.Qualified(trigger.slot, trigger.price, trigger.marketCapUsd, trigger.rate, trigger.observationId),
            entry = EntryVerdict.Filled(fill.actualSlot, fill.executionPrice, fill.observationId),
            exit = ExitVerdict.Closed(CloseReason.TAKE_PROFIT, close.slot, close.realizedPnl),
            replay = ReplayVerdict.Ran,
            provenance = VerdictProvenance(report.datasetChecksum, report.runId, report.configurationHash, report.engineVersion,
                scenario.intent.referencePrice, buy.signature, buy.slot),
        ), verdict)
        assertEquals(DipSample.TAKE_PROFIT_PNL, (verdict.exit as ExitVerdict.Closed).realizedPnl.toPlainString())
        assertEquals(Slot.of(1002), (verdict.dip as DipVerdict.Qualified).triggerSlot)
        assertEquals("16800.000000000000", (verdict.dip as DipVerdict.Qualified).marketCapUsd.toPlainString())
        assertEquals(Slot.of(1000), verdict.provenance.firstBuySlot)
        assertEquals(ReplayRunner.ENGINE_VERSION, verdict.provenance.engineVersion)
    }

    @Test
    fun `a stop loss cycle is still a qualifying dip`() {
        val verdict = qualify(DipSample.STOP_LOSS)
        assertEquals(CaseClassification.QUALIFYING_DIP, verdict.classification)
        val exit = verdict.exit as ExitVerdict.Closed
        assertEquals(CloseReason.STOP_LOSS, exit.reason)
        assertEquals(Slot.of(2005), exit.slot)
        assertEquals(DipSample.STOP_LOSS_PNL, exit.realizedPnl.toPlainString())
    }

    @Test
    fun `a dip that never triggers is a negative case with no entry or exit`() {
        mapOf(
            DipSample.NO_DIP to DipWatchOutcome.NO_DIP,
            DipSample.LOW_CAP to DipWatchOutcome.DIP_BELOW_MIN_MARKET_CAP,
            DipSample.MIGRATED_EARLY to DipWatchOutcome.MIGRATED_BEFORE_ENTRY,
            DipSample.UNSUPPORTED_SUPPLY to DipWatchOutcome.UNSUPPORTED_SUPPLY,
        ).forEach { (cycle, outcome) ->
            val verdict = qualify(cycle)
            assertEquals(CaseClassification.NEGATIVE_CASE, verdict.classification, "cycle=$cycle")
            assertEquals(DipVerdict.NotQualified(outcome), verdict.dip)
            assertNull(verdict.entry)
            assertNull(verdict.exit)
            assertEquals(ReplayVerdict.Ran, verdict.replay)
            assertEquals(CaptureVerdict(CaptureVerdictState.COMPLETE), verdict.capture)
        }
    }

    @Test
    fun `a triggered dip without a fill qualifies with the fill rejection`() {
        val verdict = qualify(DipSample.NO_FILL)
        assertEquals(CaseClassification.QUALIFYING_DIP, verdict.classification)
        assertEquals(EntryVerdict.NotFilled(RejectionReason.NO_FILL_AFTER_TRIGGER), verdict.entry)
        assertNull(verdict.exit)
    }

    @Test
    fun `a position left open qualifies with the reason it stayed open`() {
        mapOf(
            DipSample.HORIZON to RejectionReason.HORIZON_REACHED,
            DipSample.NO_EXIT_FILL to RejectionReason.NO_EXIT_FILL,
            DipSample.MIGRATED_IN_POSITION to RejectionReason.MIGRATED,
        ).forEach { (cycle, reason) ->
            val verdict = qualify(cycle)
            assertEquals(CaseClassification.QUALIFYING_DIP, verdict.classification, "cycle=$cycle")
            assertTrue(verdict.entry is EntryVerdict.Filled)
            assertEquals(ExitVerdict.Open(reason), verdict.exit)
        }
    }

    @Test
    fun `a dataset whose buy starts no cycle is a negative case not evaluated for its ingestion reason`() {
        val dataset = dataset(DipSample.TAKE_PROFIT) { it.copy(transactions = it.transactions.map { tx -> tx.copy(signer = false) }) }
        val verdict = qualify(DipSample.TAKE_PROFIT, dataset)
        assertEquals(CaseClassification.NEGATIVE_CASE, verdict.classification)
        assertEquals(DipVerdict.NotEvaluated("NOT_SIGNER"), verdict.dip)
        assertNull(verdict.entry)
        assertNull(verdict.exit)
        assertEquals(ReplayVerdict.Ran, verdict.replay)
        assertNull(verdict.provenance.firstBuySignature)
    }

    @Test
    fun `a corrupted or missing dataset is a replay failure that keeps the capture complete`() {
        val valid = Files.readString(dataset(DipSample.TAKE_PROFIT))
        val checksum = requireNotNull(Regex("\"checksum\"\\s*:\\s*\"([0-9a-f]{64})\"").find(valid)).groupValues[1]
        val tampered = root.resolve("tampered.json").also { Files.writeString(it, valid.replace(checksum, "0".repeat(64))) }
        val truncated = root.resolve("truncated.json").also { Files.writeString(it, valid.substring(0, valid.length / 2)) }
        mapOf(tampered to "DATASET_INVALID", truncated to "DATASET_INVALID", root.resolve("missing.json") to "DATASET_UNREADABLE")
            .forEach { (path, category) ->
            val verdict = qualify(DipSample.TAKE_PROFIT, path)
            assertEquals(CaseClassification.REPLAY_FAILED, verdict.classification)
            assertEquals(ReplayVerdict.Failed(category), verdict.replay, path.toString())
            assertEquals(CaptureVerdict(CaptureVerdictState.COMPLETE), verdict.capture)
            assertEquals(DipVerdict.NotEvaluated("REPLAY_FAILED"), verdict.dip)
            assertNull(verdict.entry)
            assertNull(verdict.exit)
            assertEquals(VerdictProvenance(), verdict.provenance)
            assertEquals(evidence(DipSample.TAKE_PROFIT).actualFirstBuy, verdict.actualFirstBuy)
        }
    }

    @Test
    fun `more than one intent is a replay failure, never a strategy outcome`() {
        val takeProfit = DipSample.pair(DipSample.TAKE_PROFIT)
        val stopLoss = DipSample.pair(DipSample.STOP_LOSS)
        val dataset = write("two-mints", sample.copy(transactions = sample.transactions.filter { it.pair == takeProfit || it.pair == stopLoss },
            observations = sample.observations.filter { it.pair == takeProfit || it.pair == stopLoss }))
        val verdict = qualify(DipSample.TAKE_PROFIT, dataset)
        assertEquals(CaseClassification.REPLAY_FAILED, verdict.classification)
        assertEquals(ReplayVerdict.Failed("MULTIPLE_INTENTS"), verdict.replay)
        assertEquals(DipVerdict.NotEvaluated("REPLAY_FAILED"), verdict.dip)
        assertNull(verdict.entry)
        assertNotNull(verdict.provenance.runId)
    }

    @Test
    fun `a cycle that starts from another buy than the proven first buy is a replay failure`() {
        val dataset = dataset(DipSample.TAKE_PROFIT)
        val buy = firstBuy(DipSample.TAKE_PROFIT)
        val otherProven = TargetTokenFixtures.provenEvidence(DipSample.mint(DipSample.TAKE_PROFIT).value, TargetTokenFixtures.EARLIER.value, buy.slot.value)

        val verdict = service(ready(otherProven)).qualify(dataset, paths)

        assertEquals(CaseClassification.REPLAY_FAILED, verdict.classification)
        assertEquals(ReplayVerdict.Failed("FIRST_BUY_MISMATCH"), verdict.replay)
        assertEquals(CaptureVerdict(CaptureVerdictState.COMPLETE), verdict.capture)
        assertEquals(DipVerdict.NotEvaluated("REPLAY_FAILED"), verdict.dip)
        assertNull(verdict.entry)
        assertNull(verdict.exit)
        assertNotNull(verdict.provenance.runId)
    }

    @Test
    fun `an exported capture still awaiting qualification says the qualification is pending, never not exported`() {
        val inspection = ready(evidence(DipSample.TAKE_PROFIT))

        val verdict = service(inspection).fromInspection(inspection, datasetExported = true)

        assertEquals(CaptureVerdict(CaptureVerdictState.COMPLETE, CaptureVerdictStage.EXPORT, "QUALIFICATION_PENDING"), verdict.capture)
        assertEquals(CaseClassification.INCOMPLETE_CAPTURE, verdict.classification)
        assertEquals(ReplayVerdict.NotRun, verdict.replay)
        assertEquals(DipVerdict.NotEvaluated("REPLAY_NOT_RUN"), verdict.dip)
        val notReady = inspection.copy(rates = summary(CaptureStageState.INCOMPLETE, "REFERENCE_RATES_INCOMPLETE"), canExport = false)
        assertEquals(CaptureVerdict(CaptureVerdictState.INCOMPLETE, CaptureVerdictStage.RATES, "REFERENCE_RATES_INCOMPLETE"),
            service(notReady).fromInspection(notReady, datasetExported = true).capture)
    }

    @Test
    fun `the same dataset gives the same run and verdict`() {
        val dataset = dataset(DipSample.TAKE_PROFIT)
        val first = qualify(DipSample.TAKE_PROFIT, dataset)
        val second = qualify(DipSample.TAKE_PROFIT, dataset)
        assertEquals(first, second)
        assertEquals(ReplayRunner().run(load(dataset), SimulationConfig.mvp()).runId, second.provenance.runId)
    }

    @Test
    fun `a capture that is not export ready is not replayed`() {
        val inspection = ready(evidence(DipSample.TAKE_PROFIT)).copy(rates = summary(CaptureStageState.INCOMPLETE, "REFERENCE_RATES_INCOMPLETE"),
            canExport = false)
        val verdict = service(inspection).qualify(dataset(DipSample.TAKE_PROFIT), paths)
        assertEquals(CaseClassification.INCOMPLETE_CAPTURE, verdict.classification)
        assertEquals(ReplayVerdict.NotRun, verdict.replay)
        assertEquals(CaptureVerdict(CaptureVerdictState.INCOMPLETE, CaptureVerdictStage.RATES, "REFERENCE_RATES_INCOMPLETE"), verdict.capture)
    }

    @Test
    fun `inspection alone classifies each capture that is not export ready and never as a negative case`() {
        val mint = DipSample.mint(DipSample.TAKE_PROFIT).value
        val buy = firstBuy(DipSample.TAKE_PROFIT)
        val notReady = summary(CaptureStageState.NOT_STARTED, "WALLET_CAPTURE_NOT_READY")
        val walletIncomplete = CaptureInspection(summary(CaptureStageState.INCOMPLETE, "WALLET_CAPTURE_INCOMPLETE"), notReady, notReady,
            canCalibrate = false, canExport = false)
        val cases = TargetTokenFixtures.refusals(buy.signature.value, buy.slot.value).map { (parts, reason) ->
            walletIncomplete.copy(wallet = summary(CaptureStageState.UNUSABLE, reason), targetToken = TargetTokenFixtures.evidence(mint, parts)) to
                Triple(CaseClassification.valueOf(reason), CaptureVerdict(CaptureVerdictState.UNUSABLE, CaptureVerdictStage.WALLET, reason), null)
        } + listOf(
            walletIncomplete to Triple(CaseClassification.INCOMPLETE_CAPTURE,
                CaptureVerdict(CaptureVerdictState.INCOMPLETE, CaptureVerdictStage.WALLET, "WALLET_CAPTURE_INCOMPLETE"), null),
            walletIncomplete.copy(wallet = summary(CaptureStageState.INVALID, "ARTIFACT_HASH")) to Triple(CaseClassification.INCOMPLETE_CAPTURE,
                CaptureVerdict(CaptureVerdictState.INCOMPLETE, CaptureVerdictStage.WALLET, "ARTIFACT_HASH"), null),
            ready(evidence(DipSample.TAKE_PROFIT)).copy(market = summary(CaptureStageState.INCOMPLETE, "MARKET_CAPTURE_INCOMPLETE"),
                rates = notReady, canCalibrate = false, canExport = false) to Triple(CaseClassification.INCOMPLETE_CAPTURE,
                CaptureVerdict(CaptureVerdictState.INCOMPLETE, CaptureVerdictStage.MARKET, "MARKET_CAPTURE_INCOMPLETE"), buy),
            ready(evidence(DipSample.TAKE_PROFIT)).copy(market = summary(CaptureStageState.UNUSABLE, "OVER_CAP"), rates = notReady,
                canCalibrate = false, canExport = false) to Triple(CaseClassification.INCOMPLETE_CAPTURE,
                CaptureVerdict(CaptureVerdictState.UNUSABLE, CaptureVerdictStage.MARKET, "OVER_CAP"), buy),
            ready(evidence(DipSample.TAKE_PROFIT)) to Triple(CaseClassification.INCOMPLETE_CAPTURE,
                CaptureVerdict(CaptureVerdictState.INCOMPLETE, CaptureVerdictStage.EXPORT, "NOT_EXPORTED"), buy),
        )
        cases.forEach { (inspection, expected) ->
            val (classification, capture, provenBuy) = expected
            val verdict = service(inspection).fromInspection(inspection, datasetExported = false)
            assertEquals(classification, verdict.classification, inspection.toString())
            assertEquals(capture, verdict.capture)
            assertEquals(ReplayVerdict.NotRun, verdict.replay)
            assertEquals(DipVerdict.NotEvaluated("REPLAY_NOT_RUN"), verdict.dip)
            assertNull(verdict.entry)
            assertNull(verdict.exit)
            assertEquals(inspection.targetToken?.tokenAccountFirstBuy, verdict.tokenAccountFirstBuy)
            assertEquals(inspection.targetToken?.actualFirstBuy, verdict.actualFirstBuy)
            assertEquals(VerdictProvenance(firstBuySignature = provenBuy?.signature, firstBuySlot = provenBuy?.slot), verdict.provenance)
        }
    }

    private fun qualify(cycle: Int, dataset: Path = dataset(cycle)): TargetTokenVerdict =
        service(ready(evidence(cycle))).qualify(dataset, paths)

    private fun service(inspection: CaptureInspection) =
        TargetTokenQualificationService(ReplayDatasetLoader(mapper), { requested -> inspection.also { assertEquals(paths, requested) } })

    private fun ready(evidence: TargetTokenEvidence): CaptureInspection {
        val ready = summary(CaptureStageState.READY, null)
        return CaptureInspection(ready, ready, ready, canCalibrate = true, canExport = true, targetToken = evidence)
    }

    private fun summary(state: CaptureStageState, reason: String?) = CaptureStageSummary(state, reason)

    private fun evidence(cycle: Int): TargetTokenEvidence = firstBuy(cycle).let {
        TargetTokenFixtures.provenEvidence(DipSample.mint(cycle).value, it.signature.value, it.slot.value)
    }

    private fun firstBuy(cycle: Int): WalletTransaction = sample.transactions.filter { it.pair == DipSample.pair(cycle) }
        .minWith(compareBy({ it.slot.value }, { it.signature.value }, { it.instructionIndex }))

    private fun dataset(cycle: Int, edit: (ReplayDataset) -> ReplayDataset = { it }): Path {
        val pair = DipSample.pair(cycle)
        return write("cycle-$cycle", edit(sample.copy(transactions = sample.transactions.filter { it.pair == pair },
            observations = sample.observations.filter { it.pair == pair })))
    }

    private fun write(name: String, dataset: ReplayDataset): Path =
        root.resolve("$name.json").also { Files.writeString(it, ReplayDatasetWriter(mapper).write(dataset)) }

    private fun load(path: Path): ReplayDataset = Files.newInputStream(path).use { ReplayDatasetLoader(mapper).load(it) }
}
