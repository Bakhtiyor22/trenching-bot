package com.trenching.bot.marketdata.application

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.marketdata.*
import com.trenching.bot.marketdata.infrastructure.capture.ReferenceRateArtifactStore
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import org.slf4j.LoggerFactory

/**
 * Records the SOL/USDT rate of every minute in which a verified market capture has a trade. A rerun asks the source only
 * for the minutes still without a rate, so a rate limit or an outage costs nothing already recorded.
 */
internal class ReferenceRateCaptureService(
    private val source: SolUsdtRateSource,
    private val markets: RecordedMarketHistory,
    private val store: ReferenceRateArtifactStore,
    private val clock: Clock,
    private val observer: com.trenching.bot.chaincapture.CaptureCheckpointObserver = com.trenching.bot.chaincapture.CaptureCheckpointObserver.NONE,
) : ReferenceRateCapture, RecordedReferenceRates {
    private val log = LoggerFactory.getLogger(ReferenceRateCaptureService::class.java)

    override fun capture(request: ReferenceRateCaptureRequest): ReferenceRateCaptureResult {
        val directory = request.directory.toAbsolutePath().normalize()
        if (directory == request.marketCaptureDirectory.toAbsolutePath().normalize()) throw CaptureValidationException("SAME_DIRECTORY")
        val marketObservations = request.caseId?.let { caseId ->
            markets.decodeCase(request.marketCaptureDirectory, caseId).let { decoded ->
                if (!decoded.ready) throw CaptureValidationException(decoded.blocker ?: "MARKET_CASE_INCOMPLETE")
                decoded.observations
            }
        } ?: markets.decode(request.marketCaptureDirectory).observations
        val minutes = marketObservations
            .map { ReferenceRate.minuteOf(it.observedTime) }.toSortedSet().toList()
        if (minutes.isEmpty()) throw CaptureValidationException("NO_OBSERVATIONS")
        return store.lock(request.directory).use {
            val saved = store.loadIfPresent(request.directory)
            if (saved != null && saved.records.map { Instant.parse(it.minute) } != minutes) throw CaptureValidationException("RESUME_IDENTITY")
            val previous = saved?.records?.associateBy { Instant.parse(it.minute) }.orEmpty()
            val missing = minutes.filter { previous[it]?.rate == null }.toSet()
            val looked = if (missing.isEmpty()) emptyMap() else source.minuteRates(missing, request.usage)
            val records = minutes.map { minute ->
                previous[minute]?.takeIf { it.rate != null } ?: record(minute, looked[minute])
            }
            val manifest = ReferenceRateManifest(ReferenceRateArtifactStore.VERSION, saved?.capturedAt ?: clock.instant().toString(),
                records, records.all { it.rate != null })
            store.save(request.directory, manifest)
            observer.checkpoint(com.trenching.bot.chaincapture.CaptureCheckpoint(
                phase = if (manifest.complete) "RATES_COMPLETE" else "RATES_INCOMPLETE", at = clock.instant(),
                processed = records.count { it.rate != null }, selected = records.size))
            log.info("reference_rates capture finished minutes={} requested={} unavailable={} complete={}",
                records.size, missing.size, records.count { it.rate == null }, manifest.complete)
            ReferenceRateCaptureResult(directory, manifest)
        }
    }

    override fun inspect(directory: Path): ReferenceRateCaptureResult = store.inspect(directory)

    override fun rates(directory: Path): List<ReferenceRate> = store.rates(store.inspect(directory).manifest)

    private fun record(minute: Instant, lookup: RateLookup?): ReferenceRateRecord = when (lookup) {
        is RateLookup.Available -> ReferenceRateRecord(minute.toString(), lookup.rate.toPlainString(), lookup.source, null)
        is RateLookup.Unavailable -> ReferenceRateRecord(minute.toString(), null, null, lookup.reason.name)
        null -> ReferenceRateRecord(minute.toString(), null, null, RateUnavailableReason.NO_MARKET_DATA.name)
    }
}
