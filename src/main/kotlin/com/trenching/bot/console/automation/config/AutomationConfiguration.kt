package com.trenching.bot.console.automation.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Turns on the automation scheduler. Its ticks use fixed delays on Spring's single scheduling thread, so they never overlap;
 * a single application instance is assumed (plan, Transactions & Integration).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAutomationEnabled
@EnableScheduling
class AutomationConfiguration
