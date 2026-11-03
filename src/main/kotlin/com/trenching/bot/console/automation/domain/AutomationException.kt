package com.trenching.bot.console.automation.domain

import com.trenching.bot.console.domain.ConsoleException

/** The base of every error in the console's automation area. */
abstract class AutomationException(message: String) : ConsoleException(message)

class AutomationSessionNotFoundException(sessionId: String) : AutomationException("Unknown automation session sessionId=$sessionId")

class UnknownSweepPlanException(planId: String) : AutomationException("Unknown sweep plan trenching.automation.sweep-plan=$planId")

class InvalidAutomationRequestException(val field: String, val reason: String) :
    AutomationException("Invalid automation request field=$field reason=$reason")

class AutomationItemNotFoundException(itemId: String) : AutomationException("Unknown automation item itemId=$itemId")

class IllegalItemTransitionException(itemId: String, from: AutomationItemState, to: AutomationItemState) :
    AutomationException("Illegal automation item transition itemId=$itemId from=$from to=$to")
