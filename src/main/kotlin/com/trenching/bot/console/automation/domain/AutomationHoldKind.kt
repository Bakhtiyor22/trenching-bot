package com.trenching.bot.console.automation.domain

/**
 * What can hold the automation's capture back for a while, each with the sentences that say so. [paused] is logged when the hold starts and
 * [resumed] when it ends, with how long it lasted in its `{}`; [inSummary] names the hold in the hourly summary.
 *
 * The scan of a backfill's history has a kind of its own: its reserve check is made in another place than the pipeline's and may
 * disagree with it for a while, so the two must not share one hold and make it flap.
 */
enum class AutomationHoldKind(val paused: String, val resumed: String, val inSummary: String) {
    /** Live items wait: the rolling 24-hour request total leaves no room for another operation. */
    REQUEST_CAP(
        "Capture paused: the 24-hour request cap is used up; waiting buys go on as older requests age out.",
        "Capture resumed: the request cap has room again after {}.",
        "capture paused by the request cap",
    ),

    /** A backfill's items wait: only the live reserve is left under the cap. */
    BACKFILL_RESERVE(
        "Backfill capture paused: only the live reserve is left under the request cap.",
        "Backfill capture resumed: there is room above the live reserve again after {}.",
        "backfill capture paused to keep the live reserve",
    ),

    /** The capture console refuses wallet and market steps: no RPC URL. */
    RPC_NOT_CONFIGURED(
        "Capture paused: the capture console is not configured ({}).",
        "Capture resumed: the capture console is configured again after {}.",
        "capture paused because the capture console has no RPC URL",
    ),

    /** The capture console refuses the rates step: reference rates are off. A hold of its own, so a wallet or market step that starts does not end it. */
    RATES_NOT_CONFIGURED(
        "Capture paused: the capture console is not configured ({}).",
        "Capture resumed: the capture console is configured again after {}.",
        "capture paused because reference rates are off",
    ),

    /** The capture console refuses wallet and market steps: every RPC account has used up its credits. */
    RPC_CREDITS(
        "Capture paused: no RPC account has credits left; waiting buys go on once one has credits again.",
        "Capture resumed: an RPC account has credits again after {}.",
        "capture paused because no RPC account has credits",
    ),

    /** A backfill's history scan waits: only the live reserve is left under the cap. */
    BACKFILL_SCAN_RESERVE(
        "Backfill scan paused: only the live reserve is left under the request cap.",
        "Backfill scan resumed: there is room above the live reserve again after {}.",
        "backfill scan paused to keep the live reserve",
    );

    /** Whether the hold's sentence names the capture console's [AutomationHoldContext.reason]. */
    val namesReason: Boolean get() = this == RPC_NOT_CONFIGURED || this == RATES_NOT_CONFIGURED

    companion object {
        /** The hold a console refusal `NOT_CONFIGURED` of [step] belongs to, null for a step the console never refuses for configuration. */
        fun notConfiguredFor(step: AutomationStep): AutomationHoldKind? = when (step) {
            AutomationStep.WALLET, AutomationStep.MARKET -> RPC_NOT_CONFIGURED
            AutomationStep.RATES -> RATES_NOT_CONFIGURED
            AutomationStep.CREATE_SESSION, AutomationStep.EXPORT, AutomationStep.QUALIFY, AutomationStep.REGISTER, AutomationStep.SWEEP -> null
        }

        /** The hold a console refusal `RPC_CREDITS_EXHAUSTED` of [step] belongs to, null for a step that spends no RPC credits. */
        fun creditsFor(step: AutomationStep): AutomationHoldKind? = when (step) {
            AutomationStep.WALLET, AutomationStep.MARKET -> RPC_CREDITS
            AutomationStep.RATES, AutomationStep.CREATE_SESSION, AutomationStep.EXPORT, AutomationStep.QUALIFY, AutomationStep.REGISTER, AutomationStep.SWEEP -> null
        }
    }
}
