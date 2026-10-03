package com.trenching.bot.console.domain

open class ConsoleException(message: String) : RuntimeException(message)

/** [fields] holds one message per offending configuration field, keyed by its JSON property name. */
class ConfigInvalidException(val fields: Map<String, String>) :
    ConsoleException("Invalid simulation configuration fields=${fields.keys}")

class InvalidConsoleRequestException(val field: String, val reason: String) :
    ConsoleException("Invalid request field=$field reason=$reason")

class EmptyDatasetUploadException : ConsoleException("Uploaded dataset file is empty")

class SampleDatasetNotFoundException(sample: String) : ConsoleException("Unknown sample dataset sample=$sample")
