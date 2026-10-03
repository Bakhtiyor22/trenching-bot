package com.trenching.bot.common

/** One 0-based page of a list, with the total number of items across all pages. */
data class PageResult<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val total: Long,
)
