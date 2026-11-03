package com.trenching.bot.console.automation.config

import com.trenching.bot.console.config.ConditionalOnConsoleEnabled
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile

/**
 * Registers the annotated bean where the capture console exists: the local profile with the console enabled, as
 * `CaptureConfiguration` requires. The automation read API and its state live here, enabled or not.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Profile("local & !prod")
@ConditionalOnConsoleEnabled
annotation class ConditionalOnAutomationConsole

/** Registers the annotated bean only when, in addition, `trenching.automation.enabled=true`: the scheduler and discovery. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnAutomationConsole
@ConditionalOnProperty(prefix = AutomationProperties.PREFIX, name = ["enabled"], havingValue = "true")
annotation class ConditionalOnAutomationEnabled
