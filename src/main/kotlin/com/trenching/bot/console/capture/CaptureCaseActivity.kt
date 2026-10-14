package com.trenching.bot.console.capture

interface CaptureCaseActivity {
    fun requireIdle()
    fun requireNotRunning(sessionId: String)

    companion object {
        val NONE = object : CaptureCaseActivity {
            override fun requireIdle() = Unit
            override fun requireNotRunning(sessionId: String) = Unit
        }
    }
}
