package com.trenching.bot.console.automation.mapper

import com.trenching.bot.console.automation.domain.DiscoveryMode
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Value conversions for automation responses: zones by id and local times as `HH:mm`. */
object AutomationValueMapper {
    private val LOCAL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    @JvmStatic fun zone(value: ZoneId?): String? = value?.id

    @JvmStatic fun mode(value: DiscoveryMode): String = value.label

    @JvmStatic fun localTime(value: LocalTime?): String? = value?.format(LOCAL_TIME)
}
