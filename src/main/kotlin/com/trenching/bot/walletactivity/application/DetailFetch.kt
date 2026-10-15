package com.trenching.bot.walletactivity.application

import tools.jackson.databind.JsonNode

/** What one detail request returned: a transaction already classified as [O], or the provider's answer that its version is unsupported. */
internal sealed interface DetailFetch<out O> {
    data class Fetched<O>(val raw: JsonNode, val outcome: O) : DetailFetch<O>

    data object UnsupportedVersion : DetailFetch<Nothing>
}
