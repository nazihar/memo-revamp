package com.memo.org.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.util.UUID

/** Legal entities (design E04 §6): updates need `If-Match`, creating needs an `Idempotency-Key`. */
@RestController
@RequestMapping("/api/v1/org/legal-entities")
@Tag(name = "org")
class LegalEntitiesController(
    private val entities: LegalEntities,
    private val store: LegalEntityStore,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    @GetMapping
    @RequiresPermission(Permission.DIRECTORY_READ)
    @Operation(operationId = "listLegalEntities", summary = "Legal entities")
    fun list(): ResponseEntity<List<LegalEntityResponse>> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(transactions.readInTenant(TenantContext.current()) { store.all().map { it.toResponse() } })

    @PostMapping
    @RequiresPermission(Permission.ORG_LEGAL_ENTITIES_MANAGE)
    @Operation(operationId = "createLegalEntity", summary = "Create a legal entity")
    fun create(
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: LegalEntityRequest,
    ): ResponseEntity<LegalEntityResponse> {
        val entity =
            transactions.inTenant(TenantContext.current()) {
                val id =
                    idempotency.once("org.legal_entity.create", key, json.writeValueAsString(body)) {
                        entities
                            .create(
                                requiredText(body.name),
                                optionalText(body.shortName),
                                optionalText(body.registrationNumber),
                                body.validFrom,
                            ).id
                            .toString()
                    }
                entities.get(UUID.fromString(id))
            }
        return ResponseEntity
            .created(URI.create("/api/v1/org/legal-entities/${entity.id}"))
            .eTag(etagOf(entity.version))
            .body(entity.toResponse())
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.ORG_LEGAL_ENTITIES_MANAGE)
    @Operation(operationId = "updateLegalEntity", summary = "Rename a legal entity")
    fun update(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: LegalEntityRequest,
    ): ResponseEntity<LegalEntityResponse> {
        val version = ifMatchVersion(ifMatch)
        val entity =
            transactions.inTenant(TenantContext.current()) {
                entities.update(id, version, requiredText(body.name), optionalText(body.shortName), optionalText(body.registrationNumber))
            }
        return ResponseEntity.ok().eTag(etagOf(entity.version)).body(entity.toResponse())
    }

    @PostMapping("/{id}/close")
    @RequiresPermission(Permission.ORG_LEGAL_ENTITIES_MANAGE)
    @Operation(operationId = "closeLegalEntity", summary = "Close a legal entity")
    fun close(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: CloseRequest,
    ): ResponseEntity<LegalEntityResponse> {
        val version = ifMatchVersion(ifMatch)
        val entity = transactions.inTenant(TenantContext.current()) { entities.close(id, version, body.validTo) }
        return ResponseEntity.ok().eTag(etagOf(entity.version)).body(entity.toResponse())
    }
}
