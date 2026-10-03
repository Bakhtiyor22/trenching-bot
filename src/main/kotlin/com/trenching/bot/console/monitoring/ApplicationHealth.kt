package com.trenching.bot.console.monitoring

import com.trenching.bot.console.domain.HealthSnapshot

fun interface ApplicationHealth {
    fun current(): HealthSnapshot
}
