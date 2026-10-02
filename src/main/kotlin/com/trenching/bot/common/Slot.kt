package com.trenching.bot.common

@ConsistentCopyVisibility
data class Slot private constructor(val value: Long) : Comparable<Slot> {
    override fun compareTo(other: Slot): Int = value.compareTo(other.value)
    fun plus(offset: Long): Slot {
        require(offset >= 0) { "Negative slot offset" }
        return of(Math.addExact(value, offset))
    }

    companion object {
        fun of(value: Long): Slot {
            require(value >= 0) { "Negative slot" }
            return Slot(value)
        }
    }
}
