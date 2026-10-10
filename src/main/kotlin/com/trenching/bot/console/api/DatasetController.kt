package com.trenching.bot.console.api

import com.trenching.bot.console.api.dto.DatasetPageResponse
import com.trenching.bot.console.api.dto.DatasetRegistrationResponse
import com.trenching.bot.console.api.dto.DatasetResponse
import com.trenching.bot.console.api.dto.ObservationResponse
import com.trenching.bot.console.application.DatasetConsoleService
import com.trenching.bot.console.config.ConditionalOnConsoleEnabled
import com.trenching.bot.console.mapper.DatasetResponseMapper
import com.trenching.bot.console.mapper.ObservationResponseMapper
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/datasets")
@ConditionalOnConsoleEnabled
class DatasetController(
    private val datasetConsoleService: DatasetConsoleService,
    private val datasetResponseMapper: DatasetResponseMapper,
    private val observationResponseMapper: ObservationResponseMapper,
) {
    @GetMapping
    fun page(
        @RequestParam(defaultValue = ConsoleResponses.DEFAULT_PAGE) page: Int,
        @RequestParam(defaultValue = ConsoleResponses.DEFAULT_PAGE_SIZE) size: Int,
    ): DatasetPageResponse = datasetResponseMapper.toPage(datasetConsoleService.page(page, size))

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @RequestPart("file") file: MultipartFile,
        @RequestParam(required = false) name: String?,
    ): ResponseEntity<DatasetRegistrationResponse> {
        val registration = datasetConsoleService.upload(name, file.originalFilename, file.size, file.inputStream)
        return ConsoleResponses.createdOrExisting(registration.created, datasetResponseMapper.toResponse(registration))
    }

    @PostMapping("/samples/{sample}")
    fun importSample(@PathVariable sample: String): ResponseEntity<DatasetRegistrationResponse> {
        val registration = datasetConsoleService.importSample(sample)
        return ConsoleResponses.createdOrExisting(registration.created, datasetResponseMapper.toResponse(registration))
    }

    @GetMapping("/{id}")
    fun find(@PathVariable id: String): DatasetResponse = datasetResponseMapper.toResponse(datasetConsoleService.find(id))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String) = datasetConsoleService.delete(id)

    @GetMapping("/{id}/observations")
    fun observations(@PathVariable id: String, @RequestParam(required = false) pair: String?): List<ObservationResponse> =
        observationResponseMapper.toResponses(datasetConsoleService.observations(id, pair))
}
