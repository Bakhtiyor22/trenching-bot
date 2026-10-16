package com.trenching.bot.apiusage

fun interface ApiUsageReports {
    /** Usage in [period], counting requests not yet flushed; [captureId] limits it to one capture, null to everything. */
    fun report(period: ApiUsagePeriod, captureId: String?): ApiUsageReport
}
