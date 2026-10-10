package com.trenching.bot.console.capture

import com.trenching.bot.console.api.dto.*
import com.trenching.bot.console.mapper.CaptureResponseMapper
import com.trenching.bot.replay.CaptureInspection

class CaptureInspectionMapper(private val mapper: CaptureResponseMapper) {
    fun map(value: CaptureInspection): CaptureInspectionResponse {
        val wallet = buildMap {
            value.walletCapture?.manifest?.let { manifest ->
                put("selected", manifest.selection.size.toString()); put("requestsUsed", manifest.requestsUsed.toString()); put("bytesUsed", manifest.bytesUsed.toString())
                put("scannedSignatures", manifest.scannedSignatures.toString()); put("skippedFailed", manifest.skippedFailed.toString())
                manifest.records.groupingBy { it.outcome.name }.eachCount().forEach { (key, count) -> put("outcome.$key", count.toString()) }
            }
            value.walletDecoded?.let { decoded -> put("buys", decoded.transactions.size.toString()); put("decoder", decoded.decoder.name); decoded.records.groupingBy { it.outcome.name }.eachCount().forEach { (key, count) -> put("decode.$key", count.toString()) }
                decoded.rejections.forEach { (key, count) -> put("rejection.$key", count.toString()) }
                decoded.rejectedInstructions.forEach { (key, count) -> put("instruction.$key", count.toString()) } }
        }
        val market = buildMap {
            value.marketCapture?.manifest?.let { manifest ->
                put("windows", manifest.windows.size.toString()); put("requestsUsed", manifest.requestsUsed.toString()); put("bytesUsed", manifest.bytesUsed.toString()); put("scanPages", manifest.scanPageLimit.toString()); put("maxTransactions", manifest.maxTransactions.toString())
                manifest.windows.forEachIndexed { index, window ->
                    val key = "window.$index"
                    put("$key.mint", window.baseMint); put("$key.fromSlot", window.fromSlot.toString()); put("$key.toSlot", window.toSlot.toString()); put("$key.coverage", window.coverage.name); put("$key.scanned", window.scanned.toString()); put("$key.selected", window.selected.toString())
                }
            }
            value.marketDecoded?.let { decoded ->
                put("observations", decoded.observations.size.toString()); put("extractor", decoded.extractor.name)
                decoded.exclusions.forEach { (key, count) -> put("exclusion.${key.name}", count.toString()) }
                decoded.windows.forEachIndexed { index, window -> put("window.$index.detailGaps", window.detailGaps.toString()); put("window.$index.observations", window.observations.toString()) }
            }
        }
        val rates = buildMap {
            value.ratesCapture?.manifest?.let { manifest ->
                put("minutes", manifest.records.size.toString()); put("recorded", manifest.records.count { it.rate != null }.toString())
                manifest.records.filter { it.rate == null }.groupingBy { it.unavailableReason ?: "UNAVAILABLE" }.eachCount().forEach { (key,count) -> put("unavailable.$key", count.toString()) }
            }
        }
        return mapper.inspection(mapper.stage(value.wallet), mapper.stage(value.market), mapper.stage(value.rates), value.canCalibrate, value.canExport, wallet, market, rates, value.marketCapture?.manifest?.windows.orEmpty().mapIndexed { index, window -> val decoded = value.marketDecoded?.windows?.getOrNull(index); mapper.window(window.baseMint, window.fromSlot, window.toSlot, window.coverage.name, window.scanned, window.selected, decoded?.detailGaps, decoded?.observations) })
    }
}

