package com.trenching.bot.notification.domain

/**
 * Why a delivery did not complete, and what that means in a log sentence. Only the name is stored and logged beside the sentence,
 * never a provider message, URL or exception text.
 */
enum class DeliveryCategory(val meaning: String) {
    RATE_LIMITED("the provider asked to slow down"),
    TIMEOUT("the provider did not answer in time"),
    UNAVAILABLE("the provider could not be reached or failed"),
    BAD_REQUEST("the provider refused the message"),
    UNAUTHORIZED("the provider does not accept the bot token"),
    FORBIDDEN("the bot may not write to the chat"),
    REJECTED("the provider refused the request"),
    UNEXPECTED("the delivery failed in an unexpected way"),
    MALFORMED_PAYLOAD("the stored message cannot be read"),
}
