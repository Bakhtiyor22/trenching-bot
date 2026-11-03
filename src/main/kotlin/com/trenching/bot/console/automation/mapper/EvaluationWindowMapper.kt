package com.trenching.bot.console.automation.mapper

import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** An evaluation window's session ids, stored as an ordered JSON array of strings. */
@Component
class EvaluationWindowMapper(private val json: ObjectMapper) {
    fun toSessionIds(stored: String): List<String> = json.readValue(stored, Array<String>::class.java).toList()

    fun toStored(sessionIds: List<String>): String = json.writeValueAsString(sessionIds)
}
