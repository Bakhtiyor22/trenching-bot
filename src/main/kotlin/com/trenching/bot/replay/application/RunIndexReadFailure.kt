package com.trenching.bot.replay.application

import java.time.DateTimeException
import tools.jackson.core.JacksonException

/** Which failures mean a stored dataset cannot be read, the set `ReplayDatasetCatalogService` treats as an invalid dataset. Anything else, a database error among it, is not the run's fault. */
object RunIndexReadFailure {
    fun isUnreadable(exception: RuntimeException): Boolean =
        exception is IllegalArgumentException || exception is DateTimeException || exception is ArithmeticException || exception is JacksonException
}
