package com.trenching.bot.chaincapture

sealed class CaptureException(message: String) : RuntimeException(message)

class CaptureValidationException(category: String) :
    CaptureException("Capture validation failed category=$category")

class CaptureProviderException(val category: String) :
    CaptureException("Capture provider failed category=$category")

class CaptureBudgetException(category: String) :
    CaptureException("Capture budget exhausted category=$category")
