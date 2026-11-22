package com.trenching.bot.notification.mapper

import com.trenching.bot.notification.domain.NotificationPayload
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/** The JSON text of a stored message's `payload` column. Conversions only. */
object NotificationPayloadCodec {
    private val JSON: JsonMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    fun encode(payload: NotificationPayload): String = JSON.writeValueAsString(payload)

    /** Null when the text is not a payload, as an old row from a version that stored something else would be. */
    fun decode(json: String): NotificationPayload? = try {
        JSON.readValue(json, NotificationPayload::class.java)
    } catch (_: JacksonException) {
        null
    }
}
