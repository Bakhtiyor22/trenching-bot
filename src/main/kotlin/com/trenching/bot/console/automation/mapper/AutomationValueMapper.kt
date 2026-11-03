package com.trenching.bot.console.automation.mapper

import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Value conversions for automation responses: zones by id and local times as `HH:mm`. */
object AutomationValueMapper {
    private val LOCAL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    @JvmStatic fun zone(value: ZoneId?): String? = value?.id

    @JvmStatic fun localTime(value: LocalTime?): String? = value?.format(LOCAL_TIME)
}
