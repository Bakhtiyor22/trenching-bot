package com.trenching.bot.console.api

import com.trenching.bot.console.api.dto.ConsoleErrorResponse
import com.trenching.bot.console.config.ConditionalOnConsoleEnabled
import com.trenching.bot.console.domain.ConfigInvalidException
import com.trenching.bot.console.domain.EmptyDatasetUploadException
import com.trenching.bot.console.domain.InvalidConsoleRequestException
import com.trenching.bot.console.domain.InvalidWalletAddressException
import com.trenching.bot.console.domain.SampleDatasetNotFoundException
import com.trenching.bot.console.domain.WalletActivityUnavailableException
import com.trenching.bot.console.mapper.ConsoleErrorMapper
import com.trenching.bot.replay.domain.DatasetInvalidException
import com.trenching.bot.replay.domain.DatasetNotFoundException
import com.trenching.bot.replay.domain.MarketCoverageEndedException
import com.trenching.bot.replay.domain.SimulationRunConflictException
import com.trenching.bot.replay.domain.SimulationRunNotFoundException
import com.trenching.bot.walletactivity.WalletActivityUnavailable
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.MultipartException
import org.springframework.web.multipart.support.MissingServletRequestPartException

/**
 * One error envelope for the dataset, run, config, dashboard, usage and wallet activity endpoints. Multipart is resolved
 * lazily (`application.yml`) so an oversized upload fails inside a handler and reaches this advice.
 */
@RestControllerAdvice(assignableTypes = [DatasetController::class, RunController::class, RunSetupController::class, ChartController::class,
    ConfigController::class, DashboardController::class, UsageController::class, WalletController::class])
@ConditionalOnConsoleEnabled
class ConsoleExceptionHandler(private val consoleErrorMapper: ConsoleErrorMapper) {
    @ExceptionHandler
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun datasetInvalid(e: DatasetInvalidException): ConsoleErrorResponse = error(DATASET_INVALID, e.message)

    @ExceptionHandler
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun emptyUpload(e: EmptyDatasetUploadException): ConsoleErrorResponse = error(DATASET_INVALID, e.message)

    @ExceptionHandler
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun datasetNotFound(e: DatasetNotFoundException): ConsoleErrorResponse = error(DATASET_NOT_FOUND, e.message)

    @ExceptionHandler
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun sampleNotFound(e: SampleDatasetNotFoundException): ConsoleErrorResponse = error(SAMPLE_NOT_FOUND, e.message)

    @ExceptionHandler
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun runNotFound(e: SimulationRunNotFoundException): ConsoleErrorResponse = error(RUN_NOT_FOUND, e.message)

    @ExceptionHandler
    @ResponseStatus(HttpStatus.CONFLICT)
    fun runConflict(e: SimulationRunConflictException): ConsoleErrorResponse = error(RUN_CONFLICT, e.message)

    @ExceptionHandler
    @ResponseStatus(HttpStatus.CONFLICT)
    fun coverageEnded(e: MarketCoverageEndedException): ConsoleErrorResponse = error(MARKET_COVERAGE_ENDED, e.message)

    @ExceptionHandler
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun configInvalid(e: ConfigInvalidException): ConsoleErrorResponse =
        consoleErrorMapper.toResponse(e.code.name, e.code.message, e.fields)

    @ExceptionHandler
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidRequest(e: InvalidConsoleRequestException): ConsoleErrorResponse =
        consoleErrorMapper.toResponse(INVALID_REQUEST, e.message.orEmpty(), mapOf(e.field to e.reason))

    @ExceptionHandler
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidWalletAddress(e: InvalidWalletAddressException): ConsoleErrorResponse =
        consoleErrorMapper.toResponse(INVALID_ADDRESS, INVALID_ADDRESS_MESSAGE, mapOf(ADDRESS_FIELD to INVALID_ADDRESS_REASON))

    @ExceptionHandler
    fun walletActivityUnavailable(e: WalletActivityUnavailableException): ResponseEntity<ConsoleErrorResponse> =
        ResponseEntity.status(status(e.reason)).body(consoleErrorMapper.toResponse(e.reason.name, message(e.reason), emptyMap(), e.retryAfterSeconds))

    @ExceptionHandler
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun typeMismatch(e: MethodArgumentTypeMismatchException): ConsoleErrorResponse =
        consoleErrorMapper.toResponse(INVALID_REQUEST, INVALID_PARAMETER_MESSAGE, mapOf(e.name to INVALID_VALUE))

    @ExceptionHandler
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun unreadable(e: HttpMessageNotReadableException): ConsoleErrorResponse = error(INVALID_REQUEST, UNREADABLE_MESSAGE)

    @ExceptionHandler
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun missingPart(e: MissingServletRequestPartException): ConsoleErrorResponse =
        consoleErrorMapper.toResponse(INVALID_REQUEST, MISSING_PART_MESSAGE, mapOf(e.requestPartName to REQUIRED))

    @ExceptionHandler
    @ResponseStatus(HttpStatus.CONTENT_TOO_LARGE)
    fun uploadTooLarge(e: MaxUploadSizeExceededException): ConsoleErrorResponse = error(UPLOAD_TOO_LARGE, TOO_LARGE_MESSAGE)

    @ExceptionHandler
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun multipart(e: MultipartException): ConsoleErrorResponse = error(INVALID_REQUEST, MULTIPART_MESSAGE)

    private fun error(code: String, message: String?): ConsoleErrorResponse =
        consoleErrorMapper.toResponse(code, message.orEmpty(), emptyMap())

    private fun status(reason: WalletActivityUnavailable): HttpStatus = when (reason) {
        WalletActivityUnavailable.RPC_NOT_CONFIGURED, WalletActivityUnavailable.NOT_MAINNET -> HttpStatus.CONFLICT
        WalletActivityUnavailable.PROVIDER_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS
        WalletActivityUnavailable.PROVIDER_UNAVAILABLE -> HttpStatus.BAD_GATEWAY
    }

    private fun message(reason: WalletActivityUnavailable): String = when (reason) {
        WalletActivityUnavailable.RPC_NOT_CONFIGURED -> RPC_NOT_CONFIGURED_MESSAGE
        WalletActivityUnavailable.NOT_MAINNET -> NOT_MAINNET_MESSAGE
        WalletActivityUnavailable.PROVIDER_RATE_LIMITED -> RATE_LIMITED_MESSAGE
        WalletActivityUnavailable.PROVIDER_UNAVAILABLE -> PROVIDER_UNAVAILABLE_MESSAGE
    }

    private companion object {
        const val DATASET_INVALID = "DATASET_INVALID"
        const val DATASET_NOT_FOUND = "DATASET_NOT_FOUND"
        const val SAMPLE_NOT_FOUND = "SAMPLE_NOT_FOUND"
        const val RUN_NOT_FOUND = "RUN_NOT_FOUND"
        const val RUN_CONFLICT = "RUN_CONFLICT"
        const val MARKET_COVERAGE_ENDED = "MARKET_COVERAGE_ENDED"
        const val INVALID_REQUEST = "INVALID_REQUEST"
        const val UPLOAD_TOO_LARGE = "UPLOAD_TOO_LARGE"
        const val REQUIRED = "is required"
        const val INVALID_VALUE = "has an invalid value"
        const val INVALID_PARAMETER_MESSAGE = "Request parameter has an invalid value"
        const val UNREADABLE_MESSAGE = "Request body must be valid JSON of the expected shape"
        const val MISSING_PART_MESSAGE = "Multipart request is missing a required part"
        const val TOO_LARGE_MESSAGE = "Dataset upload exceeds the maximum upload size"
        const val MULTIPART_MESSAGE = "Request must be multipart/form-data with a file part"
        const val INVALID_ADDRESS = "INVALID_ADDRESS"
        const val ADDRESS_FIELD = "address"
        const val INVALID_ADDRESS_REASON = "must be a 32-byte base58 address"
        const val INVALID_ADDRESS_MESSAGE = "Not a valid 32-byte base58 Solana address"
        const val RPC_NOT_CONFIGURED_MESSAGE = "Configure SOLANA_RPC_URL in the backend process to load wallet activity"
        const val NOT_MAINNET_MESSAGE = "Wallet activity requires a Solana mainnet RPC endpoint"
        const val RATE_LIMITED_MESSAGE = "The Solana RPC provider is rate limiting requests; retry later"
        const val PROVIDER_UNAVAILABLE_MESSAGE = "The Solana RPC provider did not answer in time or failed"
    }
}
