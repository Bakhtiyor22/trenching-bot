package com.trenching.bot.console.capture

/**
 * Reads what a capture summary needs about its cases from the case records and their operations, so listing captures never
 * inspects or hashes capture artifacts. Callers hold the store lock.
 */
class CaptureCaseFactReader(private val store: CaptureFileStore) {
    fun facts(session: CaptureSessionRecord): List<CaptureCaseFact> =
        store.captureCases(session.id).filter { belongsTo(it, session) }.sortedBy { it.caseId }.map { record ->
            val rates = record.ratesOperationId?.let { store.operationOrNull(session.id, it) }
            val export = record.exportOperationId?.let { store.operationOrNull(session.id, it) }
            CaptureCaseFact(
                caseId = record.caseId,
                operation = record.operationId?.let { store.operationOrNull(session.id, it) },
                validated = rates?.state == CaptureNames.SUCCEEDED && rates.caseInspection?.ready == true,
                exported = export?.state == CaptureNames.SUCCEEDED && export.artifact != null,
                datasetId = record.datasetId,
            )
        }

    /** A case record belongs to the attempts it was captured under; a new attempt leaves it behind. */
    private fun belongsTo(record: CaptureCaseRecord, session: CaptureSessionRecord): Boolean =
        record.walletAttemptId == session.walletAttemptId && record.marketAttemptId == session.marketAttemptId && record.ratesAttemptId == session.ratesAttemptId
}
