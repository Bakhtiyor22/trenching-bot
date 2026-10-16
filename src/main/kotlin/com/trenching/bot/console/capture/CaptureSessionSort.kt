package com.trenching.bot.console.capture

private const val NEWEST_FIRST = "createdAt,desc"

private val ATTENTION_ORDER = listOf(
    CaptureSummaryStatus.NEEDS_ATTENTION, CaptureSummaryStatus.STOPPED_LIMIT, CaptureSummaryStatus.STOPPED_DEADLINE, CaptureSummaryStatus.WAITING_RETRY,
    CaptureSummaryStatus.PAUSED, CaptureSummaryStatus.RUNNING, CaptureSummaryStatus.READY_TO_REPLAY, CaptureSummaryStatus.NOT_STARTED,
    CaptureSummaryStatus.DONE, CaptureSummaryStatus.CANCELLED,
)

/** Capture list orders by their HTTP [value]; every order breaks ties by session id. */
enum class CaptureSessionSort(val value: String, val comparator: Comparator<CaptureSessionView>) {
    CREATED_DESC(NEWEST_FIRST, compareByDescending<CaptureSessionView> { it.createdAt }.thenBy { it.id }),
    CREATED_ASC("createdAt,asc", compareBy<CaptureSessionView> { it.createdAt }.thenBy { it.id }),
    NAME_ASC("name,asc", compareBy<CaptureSessionView> { CaptureNamePolicy.key(it.displayName) }.thenBy { it.id }),
    ATTENTION("attention", compareBy<CaptureSessionView> { ATTENTION_ORDER.indexOf(it.summary.status) }.thenByDescending { it.createdAt }.thenBy { it.id });

    companion object {
        const val DEFAULT_VALUE = NEWEST_FIRST

        fun of(value: String): CaptureSessionSort? = entries.firstOrNull { it.value == value }
    }
}
