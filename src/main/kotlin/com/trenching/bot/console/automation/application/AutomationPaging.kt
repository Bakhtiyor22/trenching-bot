package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.domain.InvalidAutomationRequestException
import org.springframework.data.domain.PageRequest

/** The automation lists' paging: pages count from 0 and hold at most [MAX_PAGE_SIZE] rows. */
object AutomationPaging {
    const val MAX_PAGE_SIZE = 100
    const val PAGE = "page"
    const val SIZE = "size"

    fun pageable(page: Int, size: Int): PageRequest {
        if (page < 0) throw InvalidAutomationRequestException(PAGE, "must not be negative")
        if (size <= 0) throw InvalidAutomationRequestException(SIZE, "must be positive")
        return PageRequest.of(page, size.coerceAtMost(MAX_PAGE_SIZE))
    }
}
