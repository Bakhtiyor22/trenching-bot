package com.trenching.bot.console.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/** Registers the annotated bean only when `trenching.console.enabled=true`. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(prefix = ConsoleProperties.PREFIX, name = ["enabled"], havingValue = "true")
annotation class ConditionalOnConsoleEnabled
