package com.trenching.bot.replay.infrastructure.fixture

import com.trenching.bot.replay.domain.BuyCalibration
import com.trenching.bot.replay.domain.CalibrationReport
import com.trenching.bot.replay.domain.CalibrationSummary
import com.trenching.bot.replay.domain.LatencyCheck
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import tools.jackson.core.util.DefaultIndenter
import tools.jackson.core.util.DefaultPrettyPrinter
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Writes a [CalibrationReport] as canonical JSON: fixed field order, raw amounts and basis points as exact decimal strings,
 * two-space indentation and `\n` line ends on every platform, so the same captures always give the same bytes.
 */
class CalibrationReportWriter(private val mapper: ObjectMapper) {
    private val printer = DefaultPrettyPrinter()
        .withObjectIndenter(DefaultIndenter(INDENT, LINE_END))
        .withArrayIndenter(DefaultIndenter(INDENT, LINE_END))

    fun write(report: CalibrationReport): String {
        val root = mapper.createObjectNode()
        root.put("wallet", report.wallet.value)
        root.put("decoder", report.decoder.name)
        root.put("extractor", report.extractor.name)
        summary(root.putObject("summary"), report.summary)
        val mismatches = root.putArray("mathMismatches")
        report.buys.filterNot { it.math.exact }.forEach { mismatches.add(it.signature.value) }
        val buys = root.putArray("buys")
        report.buys.forEach { buys.add(buy(it)) }
        return mapper.writer().with(printer).writeValueAsString(root) + LINE_END
    }

    /** Replaces [target] with the report, through a temporary file in the same directory. */
    fun save(target: Path, report: CalibrationReport) {
        val parent = target.toAbsolutePath().normalize().parent
        Files.createDirectories(parent)
        val pending = Files.createTempFile(parent, ".calibration-", ".pending")
        try {
            Files.writeString(pending, write(report))
            Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(pending) }
    }

    private fun summary(node: ObjectNode, summary: CalibrationSummary) {
        node.put("buys", summary.buys)
        node.put("mathExact", summary.mathExact)
        node.put("mathMismatches", summary.mathMismatches)
        node.put("latencyChecked", summary.latencyChecked)
        node.put("noPriorState", summary.noPriorState)
        node.put("unpredictable", summary.unpredictable)
        decimal(node, "medianErrorBps", summary.medianErrorBps)
        decimal(node, "maxErrorBps", summary.maxErrorBps)
        decimal(node, "medianTransactionFeeLamports", summary.medianTransactionFeeLamports)
        decimal(node, "medianOtherLamports", summary.medianOtherLamports)
        node.putObject("instructions").apply { summary.instructions.forEach { (name, count) -> put(name, count) } }
    }

    private fun buy(buy: BuyCalibration): ObjectNode = mapper.createObjectNode().apply {
        put("signature", buy.signature.value)
        put("slot", buy.slot.value)
        put("mint", buy.mint.value)
        put("solAmount", buy.solAmount.toString())
        put("instruction", buy.instruction.name)
        putObject("math").apply {
            put("exact", buy.math.exact)
            put("tokenAmount", buy.math.tokenAmount.toString())
            integer(this, "computedTokenAmount", buy.math.computedTokenAmount)
            put("feeLamports", buy.math.feeLamports.toString())
            put("computedFeeLamports", buy.math.computedFeeLamports.toString())
            put("creatorFeeLamports", buy.math.creatorFeeLamports.toString())
            put("computedCreatorFeeLamports", buy.math.computedCreatorFeeLamports.toString())
        }
        latency(putObject("latency"), buy.latency)
        putObject("costs").apply {
            put("transactionFeeLamports", buy.costs.transactionFeeLamports.toString())
            put("otherLamports", buy.costs.otherLamports.toString())
        }
    }

    private fun latency(node: ObjectNode, latency: LatencyCheck) {
        when (latency) {
            LatencyCheck.NoPriorState -> node.put(STATUS, "NO_PRIOR_STATE")
            is LatencyCheck.Checked -> {
                node.put(STATUS, "CHECKED")
                node.put(OBSERVATION_ID, latency.observationId)
                node.put(OBSERVATION_SLOT, latency.observationSlot.value)
                node.put("predictedTokenAmount", latency.predictedTokenAmount.toString())
                node.put("errorBps", latency.errorBps.toPlainString())
            }
            is LatencyCheck.Unpredictable -> {
                node.put(STATUS, "UNPREDICTABLE")
                node.put(OBSERVATION_ID, latency.observationId)
                node.put(OBSERVATION_SLOT, latency.observationSlot.value)
                node.put("reason", latency.reason.name)
            }
        }
    }

    private fun decimal(node: ObjectNode, field: String, value: BigDecimal?) {
        if (value == null) node.putNull(field) else node.put(field, value.toPlainString())
    }

    private fun integer(node: ObjectNode, field: String, value: BigInteger?) {
        if (value == null) node.putNull(field) else node.put(field, value.toString())
    }

    private companion object {
        const val INDENT = "  "
        const val LINE_END = "\n"
        const val STATUS = "status"
        const val OBSERVATION_ID = "observationId"
        const val OBSERVATION_SLOT = "observationSlot"
    }
}
