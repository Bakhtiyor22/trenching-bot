package com.trenching.bot.chaincapture

import tools.jackson.databind.JsonNode

/** Typed, bounded readers for capture manifest fields; any mismatch is a [CaptureValidationException]. */
object ManifestFields {
    private const val MAX_STRING_LENGTH = 256

    fun string(node: JsonNode, field: String): String {
        val value = node.path(field)
        if (!value.isString || value.asString().length > MAX_STRING_LENGTH) fail("MANIFEST_STRING")
        return value.asString()
    }

    fun optionalString(node: JsonNode, field: String): String? =
        if (node.path(field).isNull || node.path(field).isMissingNode) null else string(node, field)

    fun integer(node: JsonNode, field: String): Long {
        val value = node.path(field)
        if (!value.isIntegralNumber || !value.canConvertToLong()) fail("MANIFEST_INTEGER")
        return value.asLong()
    }

    fun smallInteger(node: JsonNode, field: String): Int {
        val value = integer(node, field)
        if (value !in 0..Int.MAX_VALUE) fail("MANIFEST_INTEGER")
        return value.toInt()
    }

    fun boolean(node: JsonNode, field: String): Boolean {
        val value = node.path(field)
        if (!value.isBoolean) fail("MANIFEST_BOOLEAN")
        return value.asBoolean()
    }

    fun array(node: JsonNode, field: String, maxSize: Int): List<JsonNode> {
        val value = node.path(field)
        if (!value.isArray || value.size() > maxSize) fail("MANIFEST_ARRAY")
        return value.toList()
    }

    /** Reads the manifest's integer schema version, failing with `MANIFEST_VERSION` unless it is [expected]. */
    fun requireVersion(node: JsonNode, expected: Int) {
        requireVersion(node, setOf(expected))
    }

    /** Reads the manifest's integer schema version, failing with `MANIFEST_VERSION` unless it is one of [accepted]. */
    fun requireVersion(node: JsonNode, accepted: Set<Int>): Int {
        val version = node.path("schemaVersion")
        if (!version.isIntegralNumber || !version.canConvertToInt() || version.asInt() !in accepted) fail("MANIFEST_VERSION")
        return version.asInt()
    }

    private fun fail(category: String): Nothing = throw CaptureValidationException(category)
}
