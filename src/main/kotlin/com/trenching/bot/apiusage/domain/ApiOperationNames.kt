package com.trenching.bot.apiusage.domain

/** An operation is stored only when it is a plain method name or path, so a URL, query string or key is never recorded. */
object ApiOperationNames {
    const val OTHER = "other"
    private val RECORDABLE = Regex("[A-Za-z/0-9_.]{1,64}")

    fun isRecordable(operation: String): Boolean = RECORDABLE.matches(operation)
}
