package com.trenching.bot.console.capture

import com.trenching.bot.console.api.dto.*
import com.trenching.bot.console.mapper.CaptureResponseMapper
import com.trenching.bot.replay.domain.CaptureVerdict
import com.trenching.bot.replay.domain.DipVerdict
import com.trenching.bot.replay.domain.EntryVerdict
import com.trenching.bot.replay.domain.ExitVerdict
import com.trenching.bot.replay.domain.ReplayVerdict
import com.trenching.bot.replay.domain.TargetTokenVerdict
import com.trenching.bot.replay.domain.VerdictProvenance

class CaptureQualificationMapper(private val mapper: CaptureResponseMapper) {
    fun map(value: TargetTokenVerdict): CaptureQualificationResponse =
        mapper.qualification(value.classification.name, capture(value.capture), dip(value.dip), value.entry?.let(::entry), value.exit?.let(::exit), replay(value.replay), provenance(value.provenance))

    private fun capture(value: CaptureVerdict): CaptureVerdictCaptureResponse = mapper.verdictCapture(value.state.name, value.stage?.name, value.reason)

    private fun dip(value: DipVerdict): CaptureDipVerdictResponse = when (value) {
        is DipVerdict.Qualified -> mapper.verdictDip("QUALIFIED", null, value.triggerSlot.value, value.triggerPrice.toPlainString(), value.marketCapUsd.toPlainString(), value.rate.toPlainString(), value.observationId)
        is DipVerdict.NotQualified -> mapper.verdictDip("NOT_QUALIFIED", value.outcome.name, null, null, null, null, null)
        is DipVerdict.NotEvaluated -> mapper.verdictDip("NOT_EVALUATED", value.reason, null, null, null, null, null)
    }

    private fun entry(value: EntryVerdict): CaptureEntryVerdictResponse = when (value) {
        is EntryVerdict.Filled -> mapper.verdictEntry("FILLED", null, value.slot.value, value.executionPrice.toPlainString(), value.observationId)
        is EntryVerdict.NotFilled -> mapper.verdictEntry("NOT_FILLED", value.reason.name, null, null, null)
    }

    private fun exit(value: ExitVerdict): CaptureExitVerdictResponse = when (value) {
        is ExitVerdict.Closed -> mapper.verdictExit("CLOSED", value.reason.name, value.slot.value, value.realizedPnl.toPlainString())
        is ExitVerdict.Open -> mapper.verdictExit("OPEN", value.reason.name, null, null)
    }

    private fun replay(value: ReplayVerdict): CaptureReplayVerdictResponse = when (value) {
        ReplayVerdict.Ran -> mapper.verdictReplay("RAN", null)
        is ReplayVerdict.Failed -> mapper.verdictReplay("FAILED", value.category)
        ReplayVerdict.NotRun -> mapper.verdictReplay("NOT_RUN", null)
    }

    private fun provenance(value: VerdictProvenance): CaptureVerdictProvenanceResponse =
        mapper.verdictProvenance(value.datasetChecksum, value.runId, value.configurationHash, value.engineVersion, value.referencePrice?.toPlainString(), value.firstBuySignature?.value, value.firstBuySlot?.value)
}
