package com.trenching.bot.replay.domain

/** The reasons and failure categories a [TargetTokenVerdict] reports where the replay gives no outcome of its own. */
object VerdictReasons {
    /** Dip reason: the capture was not export ready, so nothing was replayed. */
    const val REPLAY_NOT_RUN = "REPLAY_NOT_RUN"

    /** Dip reason: the replay failed, so no strategy outcome exists. */
    const val REPLAY_FAILED = "REPLAY_FAILED"

    /** Dip reason: the dataset holds no transaction the strategy ignored, so none explains the missing cycle. */
    const val NO_SOURCE_BUY = "NO_SOURCE_BUY"

    /** Capture reason: every stage is ready, but no dataset has been exported yet. */
    const val NOT_EXPORTED = "NOT_EXPORTED"

    /** Capture reason: the dataset is exported and every stage is ready, but its qualification has not run yet. */
    const val QUALIFICATION_PENDING = "QUALIFICATION_PENDING"

    /** Capture reason: the market capture ends before a scenario's outcome is decided, so the window must be extended. */
    const val MARKET_COVERAGE_ENDED = "MARKET_COVERAGE_ENDED"

    /** Replay failure categories. */
    const val DATASET_UNREADABLE = "DATASET_UNREADABLE"
    const val DATASET_INVALID = "DATASET_INVALID"
    const val MULTIPLE_INTENTS = "MULTIPLE_INTENTS"

    /** The replayed cycle started from another buy than the proven actual first buy. */
    const val FIRST_BUY_MISMATCH = "FIRST_BUY_MISMATCH"
    const val REPLAY_ERROR = "REPLAY_ERROR"
}
