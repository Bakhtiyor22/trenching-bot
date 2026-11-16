package com.trenching.bot.console.automation.domain

/**
 * What one history event of an item records. Stored by name, so a kind can be added without touching the rows already written;
 * clients render a kind they do not know generically.
 */
enum class AutomationItemEventKind {
    /** The item's state when its history began, for an item that existed before events were recorded. */
    HISTORY_STARTED,

    /** The item was found, or its state or step changed. */
    STATE_CHANGED,

    /** A capture operation was started, or one queued by an earlier operation was adopted, for the item's step. */
    OPERATION_STARTED,

    /** The capture operation the item followed ended, in the state its detail names. */
    OPERATION_ENDED,

    /** A step's attempt failed, whether the step runs again or the item fails. */
    ATTEMPT_FAILED,

    /** A person retried the failed item. */
    RETRY_REQUESTED,

    /** One sweep entry's result was stored for the item. */
    SWEEP_ENTRY_RECORDED,

    /** An earlier buy of the item's mint took its place; the detail names the item that did. The item's events stay after it is deleted. */
    REPLACED,
}
