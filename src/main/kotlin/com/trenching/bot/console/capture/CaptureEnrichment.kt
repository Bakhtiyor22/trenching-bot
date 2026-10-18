package com.trenching.bot.console.capture

import org.slf4j.LoggerFactory
import tools.jackson.core.JacksonException

/**
 * Reads capture files only to enrich another view: a run's or dataset's source, or the captures holding a wallet's mints. A
 * session or case file that cannot be read gives the view's fallback and a WARN instead of failing the view; the capture
 * list itself still reports the file.
 */
internal object CaptureEnrichment {
    private val log = LoggerFactory.getLogger(CaptureEnrichment::class.java)

    fun <T> readOr(fallback: T, view: String, subject: String, read: () -> T): T = try {
        read()
    } catch (exception: CaptureRequestException) {
        unreadable(view, subject, exception.code, exception)
        fallback
    } catch (exception: JacksonException) {
        unreadable(view, subject, UNREADABLE_JSON, exception)
        fallback
    }

    private fun unreadable(view: String, subject: String, code: String, exception: Exception) =
        log.warn("capture_enrichment unreadable_capture_files view={} subject={} code={}", view, subject, code, exception)

    private const val UNREADABLE_JSON = "UNREADABLE_JSON"
}
