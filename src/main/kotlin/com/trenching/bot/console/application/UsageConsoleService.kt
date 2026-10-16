package com.trenching.bot.console.application

import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.apiusage.ApiUsagePeriod
import com.trenching.bot.apiusage.ApiUsageReport
import com.trenching.bot.apiusage.ApiUsageReports
import com.trenching.bot.console.domain.InvalidConsoleRequestException
import org.springframework.stereotype.Service

@Service
class UsageConsoleService(private val reports: ApiUsageReports) {
    /** A blank [captureId] asks for every capture; any other value must be a capture identifier. */
    fun report(period: ApiUsagePeriod, captureId: String?): ApiUsageReport {
        val capture = captureId?.takeUnless { it.isBlank() }
        if (capture != null && !ApiUsageContext.isValidCaptureId(capture)) {
            throw InvalidConsoleRequestException(CAPTURE_ID, "must be a capture identifier")
        }
        return reports.report(period, capture)
    }

    private companion object {
        const val CAPTURE_ID = "captureId"
    }
}
