package com.trenching.bot.console.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

object ConsoleResponses {
    const val DEFAULT_PAGE = "0"
    const val DEFAULT_PAGE_SIZE = "20"

    /** 201 when the request created the resource, 200 when it already existed. */
    fun <T : Any> createdOrExisting(created: Boolean, body: T): ResponseEntity<T> =
        ResponseEntity.status(if (created) HttpStatus.CREATED else HttpStatus.OK).body(body)
}
