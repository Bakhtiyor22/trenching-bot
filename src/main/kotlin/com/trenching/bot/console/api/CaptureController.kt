package com.trenching.bot.console.api

import com.trenching.bot.console.api.dto.*
import com.trenching.bot.console.capture.*
import com.trenching.bot.console.config.*
import com.trenching.bot.console.mapper.CaptureResponseMapper
import com.trenching.bot.console.mapper.DatasetResponseMapper
import com.trenching.bot.console.mapper.RecommendationResponseMapper
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/captures")
@Profile("local & !prod")
@ConditionalOnConsoleEnabled
class CaptureController(private val sessions: CaptureSessionService, private val setup: CaptureSetupService, private val operations: CaptureOperationService, private val cases: CaptureCaseService, private val catalog: CaptureCatalogService, private val mapper: CaptureResponseMapper, private val datasets: DatasetResponseMapper, private val recommendations: RecommendationResponseMapper) {
    @GetMapping("/config") fun config(): CaptureConfigResponse = mapper.config(setup.config())
    @GetMapping("/recommendations") fun recommendations(@RequestParam(required=false) wallet: String?,@RequestParam(required=false) scope: String?,@RequestParam(required=false) mint: String?): List<RecommendationResponse> = recommendations.toResponses(setup.recommendations(wallet,scope,mint))
    @GetMapping("/names/check") fun checkName(@RequestParam name: String): CaptureNameCheckResponse = mapper.nameCheck(sessions.checkName(name))
    @GetMapping fun page(@RequestParam(defaultValue="0") page: Int,@RequestParam(defaultValue="20") size: Int,@RequestParam(defaultValue=CaptureSessionSort.DEFAULT_VALUE) sort: String,@RequestParam(required=false) status: String?): CaptureSessionPageResponse = mapper.sessions(sessions.page(page,size,sort,status))
    @PostMapping fun create(@RequestBody request: CaptureCreateRequest): CaptureSessionResponse = mapper.session(sessions.create(request))
    @PatchMapping("/{id}") fun rename(@PathVariable id: String,@RequestBody request: CaptureRenameRequest): CaptureSessionResponse = mapper.session(sessions.rename(id,request))
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) fun delete(@PathVariable id: String) = sessions.delete(id)
    @GetMapping("/{id}") fun find(@PathVariable id: String): CaptureSessionResponse = mapper.session(sessions.find(id))
    @GetMapping("/{id}/attempts") fun attempts(@PathVariable id: String,@RequestParam(defaultValue="0") page: Int,@RequestParam(defaultValue="20") size: Int): CaptureAttemptPageResponse = mapper.attempts(sessions.attempts(id,page,size))
    @PostMapping("/{id}/attempts") fun attempt(@PathVariable id: String,@RequestBody request: CaptureAttemptRequest): CaptureSessionResponse = mapper.session(sessions.newAttempt(id,request))
    @PostMapping("/{id}/operations") @ResponseStatus(HttpStatus.ACCEPTED) fun operate(@PathVariable id: String,@RequestBody request: CaptureOperationRequest): CaptureOperationResponse = mapper.operation(operations.start(id,request))
    @GetMapping("/{id}/operations/{operationId}") fun operation(@PathVariable id: String,@PathVariable operationId: String): CaptureOperationResponse = mapper.operation(operations.find(id,operationId))
    @PostMapping("/{id}/operations/{operationId}/{action}") fun control(@PathVariable id: String,@PathVariable operationId: String,@PathVariable action: String,@RequestBody request: CaptureOperationControlRequest): CaptureOperationResponse = mapper.operation(operations.control(id,operationId,action,request.requestId))
    @GetMapping("/{id}/cases") fun cases(@PathVariable id: String): CaptureCasesResponse = mapper.cases(cases.cases(id))
    @PostMapping("/{id}/cases/{caseId}/rates") @ResponseStatus(HttpStatus.ACCEPTED) fun caseRates(@PathVariable id: String,@PathVariable caseId: String,@RequestBody request: CaptureCaseCommandRequest): CaptureOperationResponse = mapper.operation(cases.captureRates(id,caseId,request.requestId))
    @PostMapping("/{id}/cases/{caseId}/export") @ResponseStatus(HttpStatus.ACCEPTED) fun caseExport(@PathVariable id: String,@PathVariable caseId: String,@RequestBody request: CaptureCaseCommandRequest): CaptureOperationResponse = mapper.operation(cases.export(id,caseId,request.requestId))
    @PostMapping("/{id}/cases/{caseId}/dataset") fun caseRegister(@PathVariable id: String,@PathVariable caseId: String): DatasetRegistrationResponse = cases.register(id,caseId)
    @GetMapping("/{id}/cases/{caseId}/downloads/dataset") fun caseDownload(@PathVariable id: String,@PathVariable caseId: String): ResponseEntity<ByteArray> = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"capture-case-$caseId.json\"").body(cases.artifact(id,caseId))
    @PostMapping("/{id}/dataset") fun register(@PathVariable id: String): DatasetRegistrationResponse = datasets.toResponse(catalog.register(id))
    @GetMapping("/{id}/downloads/{kind}") fun download(@PathVariable id: String,@PathVariable kind: String): ResponseEntity<ByteArray> = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"capture-$kind.json\"").body(operations.artifact(id,kind))
}
