package com.trenching.bot.apiusage

/** Counts provider requests where they are sent. It only touches memory, so it is safe on any thread and inside any call. */
fun interface ApiUsageRecorder {
    fun record(call: ApiCall)

    companion object {
        /** For tests and command-line processes, whose requests are not counted. */
        val NONE = ApiUsageRecorder { }
    }
}
