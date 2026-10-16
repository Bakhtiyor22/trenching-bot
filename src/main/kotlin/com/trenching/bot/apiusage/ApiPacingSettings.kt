package com.trenching.bot.apiusage

/** The pacing reports show, supplied by the module that owns the provider settings. */
fun interface ApiPacingSettings {
    fun pacing(): List<ApiPacing>

    companion object {
        val NONE = ApiPacingSettings { emptyList() }
    }
}
