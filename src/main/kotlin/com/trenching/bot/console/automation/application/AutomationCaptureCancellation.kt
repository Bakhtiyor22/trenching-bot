package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationOperationOutcome
import com.trenching.bot.console.automation.domain.AutomationOperationRef
import com.trenching.bot.console.capture.CaptureOperationService
import com.trenching.bot.console.capture.CaptureRequestException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Cancels capture operations no item follows any more, through the capture console's own `cancel` control, so an abandoned
 * operation never spends requests (or retries automatically) beyond what the request cap admitted. An operation already ended
 * is left as it is. Called outside any transaction; a refused or failed cancel is logged and the caller goes on.
 */
@Service
@ConditionalOnAutomationConsole
class AutomationCaptureCancellation(private val operations: CaptureOperationService) {
    private val log = LoggerFactory.getLogger(AutomationCaptureCancellation::class.java)

    fun cancel(operation: AutomationOperationRef) {
        try {
            val current = operations.find(operation.captureSessionId, operation.operationId)
            when (AutomationOperationOutcome.of(current.state)) {
                AutomationOperationOutcome.SUCCEEDED, AutomationOperationOutcome.FAILED -> return
                AutomationOperationOutcome.RUNNING, AutomationOperationOutcome.PAUSED -> Unit
            }
            val cancelled = operations.control(operation.captureSessionId, operation.operationId, CANCEL, operation.cancelRequestId)
            log.info("automation operation cancelled itemId={} operationId={} state={}", operation.itemId, operation.operationId, cancelled.state)
        } catch (e: CaptureRequestException) {
            log.warn("automation operation cancel refused itemId={} operationId={} code={}", operation.itemId, operation.operationId, e.code)
        } catch (e: Exception) {
            log.error("automation operation cancel failed itemId={} operationId={}", operation.itemId, operation.operationId, e)
        }
    }

    private companion object {
        const val CANCEL = "cancel"
    }
}
