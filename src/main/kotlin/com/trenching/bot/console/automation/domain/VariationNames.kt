package com.trenching.bot.console.automation.domain

/**
 * A variation's name: the first variation of its edit [line] at [revision] 0 (`V03`), then one more revision for each edit since
 * (`V03.1`, `V03.2`). Derived from the replaces links and never stored.
 */
data class VariationName(val line: String, val revision: Int) {
    val text: String get() = if (revision == 0) line else "$line.$revision"

    val next: VariationName get() = VariationName(line, revision + 1)

    companion object {
        /** By the line's number, then the revision, so `V02` comes before `V10`; a line that is not `V` and digits comes after the numbered ones, by its text. */
        val ORDER: Comparator<VariationName> = compareBy<VariationName> { VariationNames.numberOf(it.line) ?: Int.MAX_VALUE }
            .thenBy { it.line }
            .thenBy { it.revision }
    }
}

object VariationNames {
    private val LABEL = Regex("""V(\d+)(?:\.\d+)*""")

    /**
     * The name of every variation of one plan, by label. A `replacesLabel` that names no row of [variations], or a replaces chain
     * that loops, fails the read naming the label: the edit writes both rows in one transaction, so neither is possible through the
     * API, and a name is never invented.
     */
    fun of(variations: List<Variation>): Map<String, VariationName> {
        val byLabel = variations.associateBy { it.label }
        val names = HashMap<String, VariationName>()
        variations.forEach { nameLine(it, byLabel, names) }
        return names
    }

    /** The label an edit of the variation named [target] is stored under: its line, one revision on. */
    fun editLabel(target: VariationName): String = target.next.text

    /** The label of the next new variation: one past the highest number any of [labels] starts with, two digits at least. Labels not shaped `V3`, `V03` or `V03.1` are ignored. */
    fun nextBaseLabel(labels: Collection<String>): String =
        VariationEditRules.labelOf((labels.mapNotNull(::numberOf).maxOrNull() ?: -1) + 1)

    /** The digits after `V`, up to the dot, or null when [label] is not `V`, digits and an optional dotted tail. */
    fun numberOf(label: String): Int? = LABEL.matchEntire(label)?.groupValues?.get(1)?.toIntOrNull()

    /** Names [start] and every variation it replaces that has no name yet, walking the chain to a variation that replaces nothing or is named. */
    private fun nameLine(start: Variation, byLabel: Map<String, Variation>, names: MutableMap<String, VariationName>) {
        val unnamed = LinkedHashSet<String>()
        var cursor: Variation? = start
        var name: VariationName? = null
        while (cursor != null && name == null) {
            val current: Variation = cursor
            name = names[current.label]
            if (name != null) break
            check(unnamed.add(current.label)) { "The replaces chain of a variation loops label=${current.label}" }
            cursor = current.replacesLabel?.let { replaced ->
                checkNotNull(byLabel[replaced]) { "A variation replaces a label the plan does not have label=${current.label} replacesLabel=$replaced" }
            }
        }
        for (label in unnamed.reversed()) {
            val named = name?.next ?: VariationName(label, 0)
            names[label] = named
            name = named
        }
    }
}
