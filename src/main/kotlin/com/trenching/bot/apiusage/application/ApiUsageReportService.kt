package com.trenching.bot.apiusage.application

import com.trenching.bot.apiusage.ApiPacingSettings
import com.trenching.bot.apiusage.ApiUsagePeriod
import com.trenching.bot.apiusage.ApiUsageReport
import com.trenching.bot.apiusage.ApiUsageReports
import com.trenching.bot.apiusage.domain.ApiUsageAggregates
import com.trenching.bot.apiusage.domain.ApiUsageReportAssembler
import com.trenching.bot.apiusage.domain.ApiUsageWindow
import com.trenching.bot.apiusage.infrastructure.persistence.ApiUsageBucketRepository
import com.trenching.bot.apiusage.mapper.ApiUsageMapper
import java.time.Clock

/** Stored buckets plus the deltas not yet flushed, so a report counts every request already sent. */
class ApiUsageReportService(
    private val repository: ApiUsageBucketRepository,
    private val accumulator: ApiUsageAccumulator,
    private val mapper: ApiUsageMapper,
    private val pacing: ApiPacingSettings,
    private val clock: Clock,
) : ApiUsageReports {
    override fun report(period: ApiUsagePeriod, captureId: String?): ApiUsageReport = accumulator.withPending { pending ->
        val now = clock.instant()
        val recordingStartedAt = listOfNotNull(repository.findOldestBucketStart(), pending.keys.minOfOrNull { it.bucketStart }).minOrNull()
        val window = ApiUsageWindow.of(period, captureId, now, recordingStartedAt)
        val stored = ApiUsageAggregates(
            repository.sumByCall(window.from, window.to, captureId).map(mapper::toCall),
            repository.sumByTask(window.from, window.to, captureId).map(mapper::toTask),
            repository.sumByBucket(window.from, window.to, captureId).map(mapper::toSeries),
        )
        val unflushed = ApiUsageAggregates.of(pending.filterKeys(window::includes))
        ApiUsageReportAssembler.assemble(window, stored + unflushed, recordingStartedAt, pacing.pacing())
    }
}
