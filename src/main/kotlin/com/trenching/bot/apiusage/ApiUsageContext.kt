package com.trenching.bot.apiusage

/** What a request was sent for: the [task] and, when it served a capture, that capture's id. */
data class ApiUsageContext(val task: ApiTask, val captureId: String?) {
    init {
        require(captureId == null || isValidCaptureId(captureId)) { "captureId must match ${CAPTURE_ID.pattern}" }
    }

    companion object {
        val UNATTRIBUTED = ApiUsageContext(ApiTask.UNATTRIBUTED, null)

        private val CAPTURE_ID = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}")

        fun isValidCaptureId(value: String): Boolean = CAPTURE_ID.matches(value)
    }
}
