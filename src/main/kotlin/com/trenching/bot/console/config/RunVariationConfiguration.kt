package com.trenching.bot.console.config

import com.trenching.bot.console.application.RunVariationLabels
import com.trenching.bot.console.domain.RunVariationLookup
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Run views name their variations in every profile; without the automation console no run has one. */
@Configuration(proxyBeanMethods = false)
class RunVariationConfiguration {
    @Bean
    fun runVariationLabels(lookups: ObjectProvider<RunVariationLookup>): RunVariationLabels =
        RunVariationLabels(lookups.getIfAvailable { RunVariationLookup.ABSENT })
}
