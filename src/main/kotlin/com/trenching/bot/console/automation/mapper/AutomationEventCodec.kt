package com.trenching.bot.console.automation.mapper

import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonTypeRef
import tools.jackson.module.kotlin.kotlinModule

/**
 * The JSON text of a history event's `detail` column: a flat object of string values (`{"label": "V00", "outcome": "NO_CYCLE"}`),
 * at most [MAX_DETAIL_CHARS] long. Conversions only.
 */
object AutomationEventCodec {
    /** The column's bound (plan Data Model): 4 KB of JSON text. */
    const val MAX_DETAIL_CHARS = 4096

    private val JSON: JsonMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    /** The detail as stored; null for none. */
    @JvmStatic fun detailJson(detail: Map<String, String>): String? {
        if (detail.isEmpty()) return null
        val json = JSON.writeValueAsString(detail)
        check(json.length <= MAX_DETAIL_CHARS) { "A history event's detail passes its bound length=${json.length}" }
        return json
    }

    @JvmStatic fun detail(json: String?): Map<String, String>? = json?.let { JSON.readValue(it, jacksonTypeRef<Map<String, String>>()) }
}
