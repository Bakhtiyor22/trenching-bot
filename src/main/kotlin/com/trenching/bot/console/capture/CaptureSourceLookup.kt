package com.trenching.bot.console.capture

import com.trenching.bot.console.domain.CaptureSource
import com.trenching.bot.console.domain.CaptureSources

/**
 * Scans the capture files for the session or case that registered a dataset. When several did, the earliest capture by
 * creation time then id wins, and within it a case over the whole session. A deleted capture is simply not found.
 */
class CaptureSourceLookup(private val store: CaptureFileStore) : CaptureSources {
    override fun forDataset(datasetId: String): CaptureSource? = synchronized(store) {
        val records = store.sessionRecords().sortedWith(compareBy<CaptureSessionRecord> { it.createdAt }.thenBy { it.id })
        val displayNames by lazy { CaptureNamePolicy.displayNames(records) }
        records.firstNotNullOfOrNull { session ->
            val caseId = store.captureCases(session.id).filter { it.datasetId == datasetId }.minOfOrNull { it.caseId }
            if (caseId == null && session.datasetId != datasetId) null
            else CaptureSource(session.id, displayNames[session.id] ?: session.name, caseId)
        }
    }
}
