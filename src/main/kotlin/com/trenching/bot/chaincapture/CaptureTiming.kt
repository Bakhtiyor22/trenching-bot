package com.trenching.bot.chaincapture

import java.time.Duration

interface CaptureTiming {
    fun nanoTime(): Long
    fun pause(duration: Duration)
    fun jitterMillis(attempt: Int): Long
}

internal object SystemCaptureTiming : CaptureTiming {
    override fun nanoTime(): Long = System.nanoTime()
    override fun pause(duration: Duration) { Thread.sleep(duration.toMillis()) }
    override fun jitterMillis(attempt: Int): Long = java.util.concurrent.ThreadLocalRandom.current().nextLong(100)
}
