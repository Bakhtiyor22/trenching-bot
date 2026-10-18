package com.trenching.bot.console.capture

import com.trenching.bot.console.domain.CaptureSource
import com.trenching.bot.console.domain.CaptureSourceLookup

/**
 * Scans the session and case records on every call, so a deleted capture stops naming its dataset at once. A dataset exported by
 * several captures names the oldest session, then the lowest case id; a session's own export comes before its cases'. While any
 * of those files cannot be read, no dataset names a capture.
 */
class CaptureFileSourceLookup(private val store: CaptureFileStore) : CaptureSourceLookup {
    override fun find(datasetId: String): CaptureSource? = synchronized(store) {
        CaptureEnrichment.readOr(fallback = null, VIEW, datasetId) {
            val sessions = store.sessionRecords()
            val displayNames = CaptureNamePolicy.displayNames(sessions)
            sessions.sortedWith(compareBy<CaptureSessionRecord> { it.createdAt }.thenBy { it.id })
                .firstNotNullOfOrNull { session -> source(session, datasetId, displayNames) }
        }
    }

    private fun source(session: CaptureSessionRecord, datasetId: String, displayNames: Map<String, String>): CaptureSource? {
        val caseId = if (session.datasetId == datasetId) {
            null
        } else {
            store.captureCases(session.id).sortedBy { it.caseId }.firstOrNull { it.datasetId == datasetId }?.caseId ?: return null
        }
        return CaptureSource(session.id, displayNames.getValue(session.id), caseId)
    }

    private companion object {
        const val VIEW = "dataset_source"
    }
}
