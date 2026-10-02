package com.trenching.bot.replay.domain

open class ReplayException(message: String) : RuntimeException(message)

class SimulationRunConflictException(runId: String) :
    ReplayException("Stored simulation result conflicts with replay runId=$runId")
