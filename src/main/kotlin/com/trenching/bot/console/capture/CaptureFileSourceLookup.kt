package com.trenching.bot.console.capture

import com.trenching.bot.console.domain.CaptureSource

/**
 * Scans the session and case records on every call, so a deleted capture stops naming its dataset at once. A dataset exported by
 * several captures names the oldest session, then the lowest case id; a session's own export comes before its cases'.
 */
class CaptureFileSourceLookup(private val store: CaptureFileStore) : CaptureSourceLookup {
    override fun find(datasetId: String): CaptureSource? = synchronized(store) {
        val sessions = store.sessionRecords()
        val displayNames = CaptureNamePolicy.displayNames(sessions)
        sessions.sortedWith(compareBy<CaptureSessionRecord> { it.createdAt }.thenBy { it.id })
            .firstNotNullOfOrNull { session -> source(session, datasetId, displayNames) }
    }

    private fun source(session: CaptureSessionRecord, datasetId: String, displayNames: Map<String, String>): CaptureSource? {
        val caseId = if (session.datasetId == datasetId) {
            null
        } else {
            store.captureCases(session.id).sortedBy { it.caseId }.firstOrNull { it.datasetId == datasetId }?.caseId ?: return null
        }
        return CaptureSource(session.id, displayNames.getValue(session.id), caseId)
    }
}
