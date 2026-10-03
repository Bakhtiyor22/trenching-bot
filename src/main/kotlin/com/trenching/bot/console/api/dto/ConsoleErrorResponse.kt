package com.trenching.bot.console.api.dto

data class ConsoleErrorResponse(
    val code: String,
    val message: String,
    val fields: Map<String, String>,
)
