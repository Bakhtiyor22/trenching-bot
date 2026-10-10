package com.trenching.bot.console.api

import com.trenching.bot.console.api.dto.*
import com.trenching.bot.console.capture.*
import com.trenching.bot.console.config.*
import com.trenching.bot.console.mapper.CaptureResponseMapper
import com.trenching.bot.console.mapper.DatasetResponseMapper
import com.trenching.bot.marketdata.ReferenceRateProperties
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
class CaptureController(private val sessions: CaptureSessionService, private val operations: CaptureOperationService, private val catalog: CaptureCatalogService, private val mapper: CaptureResponseMapper, private val datasets: DatasetResponseMapper, private val rpc: SolanaRpcProperties, private val rates: ReferenceRateProperties) {
    @GetMapping("/config") fun config(): CaptureConfigResponse = mapper.config(!rpc.rpcUrl.isNullOrBlank(),rates.enabled,CaptureWalletSettings(""),CaptureMarketSettings(),CaptureBudget(),CaptureValidation.bounds,4500)
    @GetMapping fun page(@RequestParam(defaultValue="0") page: Int,@RequestParam(defaultValue="20") size: Int): CaptureSessionPageResponse = mapper.sessions(sessions.page(page,size))
    @PostMapping fun create(@RequestBody request: CaptureCreateRequest): CaptureSessionResponse = mapper.session(sessions.create(request))
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) fun delete(@PathVariable id: String) = sessions.delete(id)
    @GetMapping("/{id}") fun find(@PathVariable id: String): CaptureSessionResponse = mapper.session(sessions.find(id))
    @GetMapping("/{id}/attempts") fun attempts(@PathVariable id: String,@RequestParam(defaultValue="0") page: Int,@RequestParam(defaultValue="20") size: Int): CaptureAttemptPageResponse = mapper.attempts(sessions.attempts(id,page,size))
    @PostMapping("/{id}/attempts") fun attempt(@PathVariable id: String,@RequestBody request: CaptureAttemptRequest): CaptureSessionResponse = mapper.session(sessions.newAttempt(id,request))
    @PostMapping("/{id}/operations") @ResponseStatus(HttpStatus.ACCEPTED) fun operate(@PathVariable id: String,@RequestBody request: CaptureOperationRequest): CaptureOperationResponse = mapper.operation(operations.start(id,request))
    @GetMapping("/{id}/operations/{operationId}") fun operation(@PathVariable id: String,@PathVariable operationId: String): CaptureOperationResponse = mapper.operation(operations.find(id,operationId))
    @PostMapping("/{id}/dataset") fun register(@PathVariable id: String): DatasetRegistrationResponse = datasets.toResponse(catalog.register(id))
    @GetMapping("/{id}/downloads/{kind}") fun download(@PathVariable id: String,@PathVariable kind: String): ResponseEntity<ByteArray> = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"capture-$kind.json\"").body(operations.artifact(id,kind))
}
