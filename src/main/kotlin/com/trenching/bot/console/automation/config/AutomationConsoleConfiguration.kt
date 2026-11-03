package com.trenching.bot.console.automation.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/** Binds and validates `trenching.automation.*` wherever the capture console exists, so a bad value fails startup there. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAutomationConsole
@EnableConfigurationProperties(AutomationProperties::class)
class AutomationConsoleConfiguration
