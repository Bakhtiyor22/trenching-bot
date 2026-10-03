package com.trenching.bot.replay.application

import com.trenching.bot.common.PageResult
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort

object ReplayPaging {
    const val MAX_PAGE_SIZE = 100
    private val NEWEST_FIRST: Sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))

    fun request(page: Int, size: Int): PageRequest {
        require(page >= 0) { "Page must not be negative page=$page" }
        require(size > 0) { "Page size must be positive size=$size" }
        return PageRequest.of(page, size.coerceAtMost(MAX_PAGE_SIZE), NEWEST_FIRST)
    }

    fun <T : Any, R> result(page: Page<T>, map: (T) -> R): PageResult<R> =
        PageResult(page.content.map(map), page.number, page.size, page.totalElements)
}
