package com.trenching.bot.console.automation.application

import com.trenching.bot.common.LogLine
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
            LogLine("Capture operation cancelled: no buy follows it any more.").field("itemId", operation.itemId).field("operationId", operation.operationId)
                .field("state", cancelled.state).info(log)
        } catch (e: CaptureRequestException) {
            LogLine("Capture operation cancel was refused by the capture console; the operation is left as it is.").field("code", e.code)
                .field("itemId", operation.itemId).field("operationId", operation.operationId).warn(log)
        } catch (e: Exception) {
            LogLine("Unexpected error while cancelling a capture operation.").field("itemId", operation.itemId).field("operationId", operation.operationId).error(log, e)
        }
    }

    private companion object {
        const val CANCEL = "cancel"
    }
}
