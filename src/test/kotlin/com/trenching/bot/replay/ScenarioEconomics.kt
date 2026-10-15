package com.trenching.bot.replay

import com.trenching.bot.replay.domain.ScenarioResult
import com.trenching.bot.replay.domain.SimulationReport
import kotlin.test.assertTrue

/** A scenario's full state with the run id, intent id and position id, which derive from the dataset hash, named. */
internal object ScenarioEconomics {
    fun of(report: SimulationReport, scenario: ScenarioResult): String {
        val ids = listOfNotNull(report.runId to "<run>", scenario.intent.id to "<intent>", scenario.position?.id?.let { it to "<position>" })
        return ids.fold(scenario.toString()) { text, (id, label) -> text.replace(id, label) }.also { text ->
            assertTrue(ids.none { (id, _) -> id in text }, "an id survived the substitution")
        }
    }
}
