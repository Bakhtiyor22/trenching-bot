package com.trenching.bot.common

/** A replay persistence batch that a feature store refuses to write. */
open class RecordBatchException(message: String) : RuntimeException(message)

class RecordBatchTooLargeException(records: String, runId: String, size: Int, limit: Int) :
    RecordBatchException("Record batch exceeds limit records=$records runId=$runId size=$size limit=$limit")

class RecordBatchMismatchException(records: String, runId: String, detail: String) :
    RecordBatchException("Record batch is inconsistent records=$records runId=$runId $detail")

object RecordBatch {
    fun requireWithinLimit(records: String, runId: String, size: Int, limit: Int) {
        if (size > limit) throw RecordBatchTooLargeException(records, runId, size, limit)
    }
}
