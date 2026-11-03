package com.trenching.bot.console.automation.domain

import com.trenching.bot.console.domain.ConsoleException
import java.time.Instant

/** The base of every error in the console's automation area. */
abstract class AutomationException(message: String) : ConsoleException(message)

class AutomationSessionNotFoundException(sessionId: String) : AutomationException("Unknown automation session sessionId=$sessionId")

class UnknownSweepPlanException(planId: String) : AutomationException("Unknown sweep plan trenching.automation.sweep-plan=$planId")

class InvalidAutomationRequestException(val field: String, val reason: String) :
    AutomationException("Invalid automation request field=$field reason=$reason")

class AutomationItemNotFoundException(itemId: String) : AutomationException("Unknown automation item itemId=$itemId")

class IllegalItemTransitionException(itemId: String, from: AutomationItemState, to: AutomationItemState) :
    AutomationException("Illegal automation item transition itemId=$itemId from=$from to=$to")

class AutomationDisabledException : AutomationException("Automation is not enabled trenching.automation.enabled=false")

class BackfillNotFoundException(backfillId: String) : AutomationException("Unknown automation backfill backfillId=$backfillId")

class BackfillActiveException(wallet: String, val activeId: String?) :
    AutomationException("The wallet already has an active backfill wallet=$wallet activeBackfillId=$activeId")

/** [coveredFrom] is where live coverage starts, null before it is known; [overlappingId] is the capturing backfill the range overlaps, if that is what it meets. */
class BackfillOverlapsCoverageException(val from: Instant, val to: Instant, val coveredFrom: Instant?, val overlappingId: String? = null) :
    AutomationException("A capturing backfill must end at or before live coverage and overlap no other one from=$from to=$to coveredFrom=$coveredFrom overlappingBackfillId=$overlappingId")

class CaptureDisabledException : AutomationException("A capturing backfill needs capture trenching.automation.capture-enabled=true")
