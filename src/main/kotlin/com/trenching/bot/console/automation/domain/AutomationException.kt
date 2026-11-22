package com.trenching.bot.console.automation.domain

import com.trenching.bot.console.domain.ConfigErrorCode
import com.trenching.bot.console.domain.ConsoleException
import java.time.Instant

/** The base of every error in the console's automation area. */
abstract class AutomationException(message: String) : ConsoleException(message)

class AutomationSessionNotFoundException(sessionId: String) : AutomationException("Unknown automation session sessionId=$sessionId")

class UnknownSweepPlanException(planId: String) : AutomationException("Unknown sweep plan trenching.automation.sweep-plan=$planId")

class InvalidAutomationRequestException(val field: String, val reason: String) :
    AutomationException("Invalid automation request field=$field reason=$reason")

class VariationNotFoundException(label: String) : AutomationException("Unknown automation variation label=$label")

class VariationArchivedException(label: String) : AutomationException("An archived variation cannot be edited label=$label")

class VariationUnchangedException(label: String) : AutomationException("The edit's configuration equals the variation's own label=$label")

/** [label] is the other active variation that already has the edit's configuration. */
class VariationDuplicateException(val label: String) :
    AutomationException("Another active variation already has the edit's configuration duplicateOfLabel=$label")

/** A plan holds at most [AutomationLimits.MAX_VARIATIONS_PER_PLAN] variations; the edit that would number one past it is refused. */
class VariationLimitReachedException(val planId: String) :
    AutomationException("A plan holds at most ${AutomationLimits.MAX_VARIATIONS_PER_PLAN} variations planId=$planId")

class VariationBaselineException(label: String) : AutomationException("The agreed baseline cannot be edited label=$label")

/** [code] is the most specific reason the edit's configuration was refused and [fields] the reason of each offending field. */
class InvalidVariationConfigException(val fields: Map<String, String>, val code: ConfigErrorCode) :
    AutomationException("Invalid variation configuration code=$code fields=${fields.keys}")

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

/** [failureCode] is the item's failure; [fields] names it in the error body only when it is what makes the retry not allowed. */
class AutomationRetryRefusedException(itemId: String, val refusal: AutomationRetryRefusal, val failureCode: String?) :
    AutomationException("Automation item cannot be retried itemId=$itemId refusal=${refusal.code} failureCode=$failureCode") {
    val fields: Map<String, String> =
        failureCode?.takeIf { refusal == AutomationRetryRefusal.RETRY_NOT_ALLOWED }?.let { mapOf("failureCode" to it) }.orEmpty()
}
