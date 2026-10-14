package com.trenching.bot.chaincapture

import java.time.Duration

sealed class CaptureException(message: String) : RuntimeException(message)

class CaptureValidationException(val category: String) :
    CaptureException("Capture validation failed category=$category")

class CaptureProviderException(val category: String, val retryAfter: Duration? = null, val requestsUsed: Int? = null, val bytesUsed: Long? = null) :
    CaptureException("Capture provider failed category=$category")

class CaptureBudgetException(val category: String, val requestsUsed: Int? = null, val bytesUsed: Long? = null) :
    CaptureException("Capture budget exhausted category=$category")
