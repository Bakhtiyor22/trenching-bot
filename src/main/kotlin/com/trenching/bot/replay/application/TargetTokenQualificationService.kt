package com.trenching.bot.replay.application

import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.replay.CaptureInspection
import com.trenching.bot.replay.CapturePaths
import com.trenching.bot.replay.CaptureStageState
import com.trenching.bot.replay.CaptureStageSummary
import com.trenching.bot.replay.domain.CaptureVerdict
import com.trenching.bot.replay.domain.CaptureVerdictStage
import com.trenching.bot.replay.domain.CaptureVerdictState
import com.trenching.bot.replay.domain.CaseClassification
import com.trenching.bot.replay.domain.DipVerdict
import com.trenching.bot.replay.domain.EntryVerdict
import com.trenching.bot.replay.domain.ExitVerdict
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.ReplayVerdict
import com.trenching.bot.replay.domain.ScenarioResult
import com.trenching.bot.replay.domain.SimulationReport
import com.trenching.bot.replay.domain.TargetBuyRefusal
import com.trenching.bot.replay.domain.TargetFirstBuyAssessment
import com.trenching.bot.replay.domain.TargetFirstBuyGate
import com.trenching.bot.replay.domain.TargetTokenVerdict
import com.trenching.bot.replay.domain.VerdictProvenance
import com.trenching.bot.replay.domain.VerdictReasons
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.strategy.domain.DipWatchOutcome
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.walletactivity.ActualFirstBuy
import com.trenching.bot.walletactivity.TargetTokenEvidence
import com.trenching.bot.walletactivity.domain.NormalizationOutcome
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory
import tools.jackson.core.JacksonException

/**
 * The offline verdict of a wallet + token capture, per Domain Rules 7–10 of the wallet-token capture plan. [qualify] replays
 * the exported, checksum-verified dataset with [SimulationConfig.mvp]: no provider call, no clock and no database write, so
 * the same dataset always gives the same run and verdict. A replay failure is its own classification, never a negative case;
 * a capture that is not export ready is classified from inspection alone. For a wallet + token capture, a cycle that starts
 * from another buy than the proven actual first buy is a replay failure, since its P0 would not be the buy the strategy requires.
 */
class TargetTokenQualificationService(
    private val loader: ReplayDatasetLoader,
    private val inspect: (CapturePaths) -> CaptureInspection,
    private val runner: ReplayRunner = ReplayRunner(),
) {
    private val log = LoggerFactory.getLogger(TargetTokenQualificationService::class.java)

    fun qualify(dataset: Path, paths: CapturePaths): TargetTokenVerdict {
        val inspection = inspect(paths)
        if (!inspection.canExport) return fromInspection(inspection, datasetExported = true)
        val evidence = inspection.targetToken
        val verdict = try {
            replayed(runner.run(load(dataset), SimulationConfig.mvp()), evidence)
        } catch (exception: Exception) {
            val category = failureCategory(exception)
            log.error("target_token_qualification replay_failed category={} dataset={}", category, dataset.fileName, exception)
            failed(category, VerdictProvenance(), evidence)
        }
        log.info("target_token_qualification finished classification={} runId={} datasetChecksum={}",
            verdict.classification, verdict.provenance.runId, verdict.provenance.datasetChecksum)
        return verdict
    }

    /** [datasetExported] is whether a dataset of the current capture was exported, so only its qualification is missing. */
    fun fromInspection(inspection: CaptureInspection, datasetExported: Boolean): TargetTokenVerdict {
        val evidence = inspection.targetToken
        val (classification, proven) = when (val assessment = evidence?.let(TargetFirstBuyGate::assess)) {
            is TargetFirstBuyAssessment.Refused -> classification(assessment.reason) to null
            is TargetFirstBuyAssessment.Proven -> CaseClassification.INCOMPLETE_CAPTURE to assessment.firstBuy
            null -> CaseClassification.INCOMPLETE_CAPTURE to null
        }
        return TargetTokenVerdict(classification, capture(inspection, datasetExported), evidence?.tokenAccountFirstBuy, evidence?.actualFirstBuy,
            DipVerdict.NotEvaluated(VerdictReasons.REPLAY_NOT_RUN), null, null, ReplayVerdict.NotRun,
            VerdictProvenance(firstBuySignature = proven?.signature, firstBuySlot = proven?.slot))
    }

    private fun load(dataset: Path): ReplayDataset = Files.newInputStream(dataset).use(loader::load)

    private fun replayed(report: SimulationReport, evidence: TargetTokenEvidence?): TargetTokenVerdict {
        val provenance = VerdictProvenance(report.datasetChecksum, report.runId, report.configurationHash, report.engineVersion)
        return when (report.scenarios.size) {
            0 -> ran(CaseClassification.NEGATIVE_CASE, DipVerdict.NotEvaluated(ingestionReason(report)), null, null, provenance, evidence)
            1 -> cycle(report, report.scenarios.single(), provenance, evidence)
            else -> {
                log.error("target_token_qualification replay_failed category={} runId={} intents={}",
                    VerdictReasons.MULTIPLE_INTENTS, report.runId, report.scenarios.size)
                failed(VerdictReasons.MULTIPLE_INTENTS, provenance, evidence)
            }
        }
    }

    private fun cycle(
        report: SimulationReport,
        scenario: ScenarioResult,
        provenance: VerdictProvenance,
        evidence: TargetTokenEvidence?,
    ): TargetTokenVerdict {
        val intent = scenario.intent
        val buy = checkNotNull(report.ingestion.filterIsInstance<NormalizationOutcome.Accepted>()
            .firstOrNull { it.trade.id == intent.sourceEventId }) {
            "Intent has no accepted source buy intentId=${intent.id} sourceEventId=${intent.sourceEventId}"
        }.trade
        val proven = (evidence?.actualFirstBuy as? ActualFirstBuy.Proven)?.signature
        if (evidence != null && buy.signature != proven) {
            log.error("target_token_qualification replay_failed category={} runId={} cycleBuy={} provenFirstBuy={}",
                VerdictReasons.FIRST_BUY_MISMATCH, report.runId, buy.signature.value, proven?.value)
            return failed(VerdictReasons.FIRST_BUY_MISMATCH, provenance, evidence)
        }
        val cycleProvenance = provenance.copy(referencePrice = intent.referencePrice, firstBuySignature = buy.signature,
            firstBuySlot = buy.slot)
        return when (intent.watch) {
            DipWatchOutcome.TRIGGERED -> {
                val trigger = checkNotNull(intent.trigger) { "Triggered intent has no trigger intentId=${intent.id}" }
                ran(CaseClassification.QUALIFYING_DIP,
                    DipVerdict.Qualified(trigger.slot, trigger.price, trigger.marketCapUsd, trigger.rate, trigger.observationId),
                    entry(scenario.outcome), exit(scenario), cycleProvenance, evidence)
            }
            DipWatchOutcome.NO_DIP, DipWatchOutcome.DIP_BELOW_MIN_MARKET_CAP, DipWatchOutcome.MIGRATED_BEFORE_ENTRY,
            DipWatchOutcome.UNSUPPORTED_SUPPLY ->
                ran(CaseClassification.NEGATIVE_CASE, DipVerdict.NotQualified(intent.watch), null, null, cycleProvenance, evidence)
        }
    }

    private fun entry(outcome: FillOutcome): EntryVerdict = when (outcome) {
        is FillOutcome.Filled -> EntryVerdict.Filled(outcome.fill.actualSlot, outcome.fill.executionPrice, outcome.fill.observationId)
        is FillOutcome.Rejected -> EntryVerdict.NotFilled(outcome.reason)
    }

    private fun exit(scenario: ScenarioResult): ExitVerdict? = when (scenario.outcome) {
        is FillOutcome.Rejected -> null
        is FillOutcome.Filled -> {
            val position = checkNotNull(scenario.position) { "Filled intent has no position intentId=${scenario.intent.id}" }
            position.close?.let { ExitVerdict.Closed(it.reason, it.slot, it.realizedPnl) }
                ?: ExitVerdict.Open(checkNotNull(scenario.exitUnavailable) { "Open position has no reason positionId=${position.id}" })
        }
    }

    /** Why no buy started a cycle: the first ignored transaction's ingestion reason, in canonical order. */
    private fun ingestionReason(report: SimulationReport): String =
        report.ingestion.filterIsInstance<NormalizationOutcome.Ignored>().firstOrNull()?.reason?.name ?: VerdictReasons.NO_SOURCE_BUY

    private fun ran(
        classification: CaseClassification,
        dip: DipVerdict,
        entry: EntryVerdict?,
        exit: ExitVerdict?,
        provenance: VerdictProvenance,
        evidence: TargetTokenEvidence?,
    ) = TargetTokenVerdict(classification, CaptureVerdict(CaptureVerdictState.COMPLETE), evidence?.tokenAccountFirstBuy,
        evidence?.actualFirstBuy, dip, entry, exit, ReplayVerdict.Ran, provenance)

    private fun failed(category: String, provenance: VerdictProvenance, evidence: TargetTokenEvidence?) =
        TargetTokenVerdict(CaseClassification.REPLAY_FAILED, CaptureVerdict(CaptureVerdictState.COMPLETE),
            evidence?.tokenAccountFirstBuy, evidence?.actualFirstBuy, DipVerdict.NotEvaluated(VerdictReasons.REPLAY_FAILED), null, null,
            ReplayVerdict.Failed(category), provenance)

    private fun failureCategory(exception: Exception): String = when (exception) {
        is IOException -> VerdictReasons.DATASET_UNREADABLE
        is IllegalArgumentException, is JacksonException -> VerdictReasons.DATASET_INVALID
        else -> VerdictReasons.REPLAY_ERROR
    }

    /** The first stage that is not ready decides the capture state; with every stage ready, only the export or its qualification is missing. */
    private fun capture(inspection: CaptureInspection, datasetExported: Boolean): CaptureVerdict {
        val stages = listOf(CaptureVerdictStage.WALLET to inspection.wallet, CaptureVerdictStage.MARKET to inspection.market,
            CaptureVerdictStage.RATES to inspection.rates)
        val (stage, summary) = stages.firstOrNull { it.second.state != CaptureStageState.READY }
            ?: return if (datasetExported) CaptureVerdict(CaptureVerdictState.COMPLETE, CaptureVerdictStage.EXPORT, VerdictReasons.QUALIFICATION_PENDING)
            else CaptureVerdict(CaptureVerdictState.INCOMPLETE, CaptureVerdictStage.EXPORT, VerdictReasons.NOT_EXPORTED)
        return CaptureVerdict(captureState(summary), stage, summary.reason ?: summary.state.name)
    }

    private fun captureState(summary: CaptureStageSummary): CaptureVerdictState = when (summary.state) {
        CaptureStageState.NOT_STARTED, CaptureStageState.INCOMPLETE, CaptureStageState.INVALID -> CaptureVerdictState.INCOMPLETE
        CaptureStageState.UNUSABLE -> CaptureVerdictState.UNUSABLE
        CaptureStageState.READY -> CaptureVerdictState.COMPLETE
    }

    private fun classification(refusal: TargetBuyRefusal): CaseClassification = when (refusal) {
        TargetBuyRefusal.NO_TARGET_BUY -> CaseClassification.NO_TARGET_BUY
        TargetBuyRefusal.FIRST_BUY_UNPROVEN -> CaseClassification.FIRST_BUY_UNPROVEN
        TargetBuyRefusal.FIRST_BUY_OUTSIDE_TOKEN_ACCOUNT -> CaseClassification.FIRST_BUY_OUTSIDE_TOKEN_ACCOUNT
    }
}
