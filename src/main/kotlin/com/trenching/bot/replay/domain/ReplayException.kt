package com.trenching.bot.replay.domain

open class ReplayException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class SimulationRunConflictException(runId: String) :
    ReplayException("Stored simulation result conflicts with replay runId=$runId")

class SimulationRunNotFoundException(runId: String) :
    ReplayException("Simulation run not found runId=$runId")

class DatasetNotFoundException(id: String) :
    ReplayException("Replay dataset not found datasetId=$id")

/** [message] is the loader's validation message, unchanged. */
class DatasetInvalidException(message: String, cause: Throwable? = null) : ReplayException(message, cause)
