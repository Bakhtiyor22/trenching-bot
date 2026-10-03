package com.trenching.bot.console.solana

import java.time.Instant

/** [cached] is true when the result was served from the probe cache rather than a fresh RPC call. */
data class ProbeCheck(val outcome: ProbeOutcome, val checkedAt: Instant, val cached: Boolean)
