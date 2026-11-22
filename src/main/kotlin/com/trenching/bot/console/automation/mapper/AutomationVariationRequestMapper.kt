package com.trenching.bot.console.automation.mapper

import com.trenching.bot.console.automation.api.dto.AutomationVariationEditRequest
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.VariationEdit
import org.springframework.stereotype.Component

/** An edit request as the service takes it. It only translates: what the configuration means, and when it is refused, is the service's to decide. */
@Component
@ConditionalOnAutomationConsole
class AutomationVariationRequestMapper {
    fun toEdit(request: AutomationVariationEditRequest): VariationEdit = VariationEdit(request.requestId.orEmpty(), request.config)
}
