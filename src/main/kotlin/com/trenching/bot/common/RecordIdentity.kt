package com.trenching.bot.common

@ConsistentCopyVisibility
data class RecordIdentity private constructor(val value: String) {
    companion object {
        fun of(runId: String, recordId: String): RecordIdentity {
            require(runId.length == 64 && recordId.isNotBlank()) { "Invalid run record identity" }
            return RecordIdentity("$runId:$recordId")
        }
    }
}
