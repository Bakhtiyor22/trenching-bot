package com.trenching.bot.console.automation.mapper

import com.trenching.bot.console.automation.domain.DryRunSummary
import com.trenching.bot.console.automation.domain.DryRunTally
import java.time.LocalDate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * The JSON text a backfill row keeps: its token list (`["mint", ...]`) and a dry run's tally
 * (`{"buysPerDay": {"2026-11-01": 3}, "mints": ["mint", ...]}`), which is also the dry run's result. Conversions only.
 */
object AutomationBackfillCodec {
    private val JSON: JsonMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    private data class TallyJson(val buysPerDay: Map<String, Int> = emptyMap(), val mints: List<String> = emptyList())

    @JvmStatic fun mints(json: String?): List<String> = json?.let { JSON.readValue(it, Array<String>::class.java).toList() }.orEmpty()

    /** The token list as stored; null for no list. */
    @JvmStatic fun mintsJson(mints: List<String>): String? = mints.takeIf { it.isNotEmpty() }?.let(JSON::writeValueAsString)

    @JvmStatic fun summary(json: String?): DryRunSummary? = json?.let { tally(it).summary() }

    @JvmStatic fun tally(json: String?): DryRunTally {
        val stored = json?.let { JSON.readValue(it, TallyJson::class.java) } ?: return DryRunTally.EMPTY
        return DryRunTally(stored.buysPerDay.mapKeys { LocalDate.parse(it.key) }.toSortedMap(), stored.mints.toSet())
    }

    @JvmStatic fun tallyJson(tally: DryRunTally): String =
        JSON.writeValueAsString(TallyJson(tally.buysPerDay.mapKeys { it.key.toString() }, tally.mints.sorted()))
}
