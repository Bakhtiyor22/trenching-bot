package com.trenching.bot.support

import com.trenching.bot.console.domain.RunVariationLookup

/** A lookup whose labels come from [labelsOf] and whose hashes come from [hashes], by label; a label without an entry is unknown. */
class FakeRunVariationLookup(
    private val hashes: Map<String, Set<String>> = emptyMap(),
    private val labelsOf: (Collection<String>) -> Map<String, List<String>> = { emptyMap() },
) : RunVariationLookup {
    override fun labels(runIds: Collection<String>): Map<String, List<String>> = labelsOf(runIds)

    override fun configurationHashes(label: String): Set<String>? = hashes[label]
}
