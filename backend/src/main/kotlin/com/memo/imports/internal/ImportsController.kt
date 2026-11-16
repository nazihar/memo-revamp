package com.memo.imports.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.Cursors
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.util.Base64
import java.util.UUID

/** A batch's settings (design E06 §10). */
data class ImportBatchRequest(
    /** 1 to 200 characters. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    /** The legacy system's code: 1 to 20 capital letters, digits or hyphens (part of every row key). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sourceCode: String,
    /** An IANA time zone, such as `Asia/Dhaka`: the legacy dates are days in it. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val timeZone: String,
    /** The level given to rows whose classification is blank; none: such rows are refused. */
    val defaultClassificationId: UUID? = null,
    /** Put before every legacy number (1 to 20 letters, digits, `/`, `.`, `_` or `-`), to keep them apart from ours. */
    val numberPrefix: String? = null,
)

/** One original to register: its relative path in the chosen folder (NFC, `/`) and its size on disk. */
data class IntakePathRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val path: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val size: Long,
)

data class IntakeRegistrationRequest(
    /** 1 to 1,000 paths. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val files: List<IntakePathRequest>,
)

/**
 * Legacy import batches (ADR-0055, design E06 §10): holders of `memo.import` only (no built-in role has it). Sources and
 * originals are uploaded through the files API with the purposes `imports.source` (owner `{batchId}:{table}`) and
 * `imports.intake` (owner the intake id registered here).
 */
@RestController
@RequestMapping("/api/v1/admin/imports")
@Tag(name = "imports")
class ImportsController(
    private val batches: ImportBatches,
    private val intake: ImportIntake,
    private val views: ImportViews,
    private val template: ImportTemplate,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    @GetMapping
    @RequiresPermission(Permission.MEMO_IMPORT)
    @Operation(operationId = "listImportBatches", summary = "Import batches, newest first")
    fun list(
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): ResponseEntity<ImportBatchPage> {
        val size = (limit ?: DEFAULT_PAGE).coerceIn(1, MAX_PAGE)
        val page =
            transactions.readInTenant(TenantContext.current()) {
                val found = batches.page(cursor?.let(Cursors::decode), size + 1)
                ImportBatchPage(
                    found.take(size).map(views::summary),
                    if (found.size > size) found[size - 1].let { Cursors.encode(it.createdAt, it.id) } else null,
                )
            }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page)
    }

    @PostMapping
    @RequiresPermission(Permission.MEMO_IMPORT)
    @Operation(operationId = "createImportBatch", summary = "Create an import batch")
    fun create(
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: ImportBatchRequest,
    ): ResponseEntity<ImportBatchView> {
        val view =
            transactions.inTenant(TenantContext.current()) {
                val id =
                    idempotency.once(CREATE, key, json.writeValueAsString(body)) {
                        batches.create(body.input()).id.toString()
                    }
                views.of(batches.get(UUID.fromString(id)))
            }
        return ResponseEntity.created(URI.create("/api/v1/admin/imports/${view.id}")).eTag(etagOf(view.version)).body(view)
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permission.MEMO_IMPORT)
    @Operation(operationId = "getImportBatch", summary = "An import batch: settings, sources, originals and counts")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<ImportBatchView> {
        val view = transactions.readInTenant(TenantContext.current()) { views.of(batches.get(id)) }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .eTag(etagOf(view.version))
            .body(view)
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.MEMO_IMPORT)
    @Operation(operationId = "updateImportBatch", summary = "Change a batch's settings (it then needs a new dry run)")
    fun update(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: ImportBatchRequest,
    ): ResponseEntity<ImportBatchView> {
        val version = ifMatchVersion(ifMatch)
        val view = transactions.inTenant(TenantContext.current()) { views.of(batches.update(id, version, body.input())) }
        return ResponseEntity.ok().eTag(etagOf(view.version)).body(view)
    }

    @GetMapping("/template", produces = [XLSX])
    @RequiresPermission(Permission.MEMO_IMPORT)
    @Operation(operationId = "getImportTemplate", summary = "The tenant's import template (.xlsx)")
    fun template(): ResponseEntity<ByteArray> {
        val bytes = transactions.readInTenant(TenantContext.current()) { template.build() }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.parseMediaType(XLSX))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .attachment()
                    .filename(TEMPLATE_NAME)
                    .build()
                    .toString(),
            ).header("X-Content-Type-Options", "nosniff")
            .body(bytes)
    }

    @PostMapping("/{id}/files")
    @RequiresPermission(Permission.MEMO_IMPORT)
    @Operation(operationId = "registerImportOriginals", summary = "Register originals' paths (1 to 1,000 at a time)")
    fun register(
        @PathVariable id: UUID,
        @RequestBody body: IntakeRegistrationRequest,
    ): List<IntakeEntryView> =
        transactions.inTenant(TenantContext.current()) {
            intake.register(id, body.files.map { PathToRegister(it.path, it.size) }).map(views::entry)
        }

    @GetMapping("/{id}/files")
    @RequiresPermission(Permission.MEMO_IMPORT)
    @Operation(operationId = "listImportOriginals", summary = "A batch's originals by state, for the upload and its resume")
    fun originals(
        @PathVariable id: UUID,
        @Parameter(description = "Comma-separated states (EXPECTED, RECEIVED, REFUSED, PURGED); all when absent.")
        @RequestParam(required = false) state: List<IntakeState>?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): ResponseEntity<IntakePage> {
        val size = (limit ?: DEFAULT_INTAKE_PAGE).coerceIn(1, MAX_INTAKE_PAGE)
        val page =
            transactions.readInTenant(TenantContext.current()) {
                val found = intake.page(id, state.orEmpty().toSet(), cursor?.let(::decodeIntake), size + 1)
                val next = if (found.size > size) found[size - 1].let { encodeIntake(it.state, it.path) } else null
                IntakePage(found.take(size).map(views::entry), next)
            }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page)
    }

    private fun ImportBatchRequest.input() = BatchSettingsInput(name, sourceCode, timeZone, defaultClassificationId, numberPrefix)

    private companion object {
        const val CREATE = "imports.batch.create"
        const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val TEMPLATE_NAME = "memo-import-template.xlsx"
        const val DEFAULT_PAGE = 50
        const val MAX_PAGE = 100
        const val DEFAULT_INTAKE_PAGE = 1_000
        const val MAX_INTAKE_PAGE = 5_000

        fun encodeIntake(
            state: IntakeState,
            path: String,
        ): String = Base64.getUrlEncoder().withoutPadding().encodeToString("${state.name}|$path".toByteArray())

        fun decodeIntake(cursor: String): Pair<IntakeState, String> {
            val text = runCatching { String(Base64.getUrlDecoder().decode(cursor)) }.getOrNull()
            val state = text?.substringBefore('|')?.let { s -> IntakeState.entries.firstOrNull { it.name == s } }
            require(text != null && state != null && '|' in text) { "invalid cursor" }
            return state to text.substringAfter('|')
        }
    }
}
