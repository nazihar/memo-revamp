package com.memo.imports.internal

import com.memo.common.authz.Clearances
import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.imports.internal.dryrun.ImportDryRuns
import com.memo.imports.internal.dryrun.ProblemLevel
import com.memo.imports.internal.dryrun.ProblemSheet
import com.memo.imports.internal.dryrun.ProblemStage
import com.memo.imports.internal.dryrun.RowResult
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
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
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** A problem of a batch (design E06 §9). Messages are built from the code and params in the web. */
data class ImportProblemView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val stage: ProblemStage,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val level: ProblemLevel,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val code: String,
    /** The sheet (or ORIGINALS, BATCH) the problem is on. */
    val sheet: ProblemSheet? = null,
    /** The row number as the admin sees it in the sheet (the header is row 1). */
    val rowNumber: Int? = null,
    /** The column key (`legacy_number`, `field:amount`, ...). */
    val column: String? = null,
    /** The Memos row the problem rejects or warns about. */
    val rowId: UUID? = null,
    /** The original received (intake id) the problem is about. */
    val intakeId: UUID? = null,
    /** Values for the message: column names, values the admin typed (a status, a path), counts and limits; never subjects. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val params: Map<String, Any?>,
)

data class ImportProblemPage(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val problems: List<ImportProblemView>,
    /** The batch's problems by level (all of them, whatever the filters). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val byLevel: Map<String, Int>,
    /** The batch's problems by code. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val byCode: Map<String, Int>,
    /** Pass as `cursor` for the next page; absent on the last. */
    val next: String? = null,
)

/** A Memos row and how the dry run (or the import) left it. */
data class ImportRowView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val rowId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val rowNumber: Int,
    val legacyNumber: String? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val result: RowResult,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val errors: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val warnings: Int,
    /** The distinct codes of its problems. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val codes: List<String>,
    /** The memo it was (or will be) imported as. */
    val memoId: UUID? = null,
    /** The subject as given, only within the viewer's clearance. */
    val subject: String? = null,
    /** True when the subject is withheld: the row is above the viewer's clearance, or its level is not mapped yet. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val restricted: Boolean,
)

data class ImportRowPage(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val rows: List<ImportRowView>,
    /** Pass as `cursor` for the next page; absent on the last. */
    val next: String? = null,
)

/** A legacy value and what it maps to (design E06 §6). */
data class ImportMappingView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: MappingKind,
    /** As given (PERSON: `email:<lower-case email>` or `id:<legacy id>`). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val legacyValue: String,
    /** Rows of the last dry run that use it. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val rowCount: Int,
    /** CLASSIFICATION only: the legacy marking's order, 1 = least strict. */
    val legacyOrder: Int? = null,
    /** A status name (STATUS), or the id of a memo type, level, unit or user. */
    val target: String? = null,
    /** The target is the dry run's proposal, unchanged. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val proposed: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val confirmed: Boolean,
    val confirmedBy: UUID? = null,
    val confirmedAt: Instant? = null,
)

data class ImportMappingsView(
    /** The batch's version after the call: send it back in `If-Match`. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val batchVersion: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mappings: List<ImportMappingView>,
    /** What the listed kinds can point at, named (as built T9): types, units, levels, and the users already linked. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val targets: List<ImportMappingTargetView> = emptyList(),
)

/** A possible mapping target and its name (as built T9). */
data class ImportMappingTargetView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: MappingKind,
    /** Send it as a mapping's `target`. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val label: String,
    /** A type's or unit's code, or a user's email. */
    val detail: String? = null,
    /** CLASSIFICATION: the level's rank (higher is stricter). */
    val rank: Int? = null,
)

/** One mapping to save. */
data class ImportMappingRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val legacyValue: String,
    /** None: not mapped (for PERSON, confirmed as not linked). */
    val target: String? = null,
    /** CLASSIFICATION only, required with a confirmed target: 1 = least strict. */
    val legacyOrder: Int? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val confirmed: Boolean,
)

/**
 * The dry run and what it found (ADR-0055, design E06 §5.2, §6, §10): request it, list the problems and rows with their
 * filters, and list or save the mappings. Holders of `memo.import` only.
 */
@RestController
@RequestMapping("/api/v1/admin/imports/{id}")
@Tag(name = "imports")
class ImportDryRunController(
    private val dryRuns: ImportDryRuns,
    private val batches: ImportBatches,
    private val mappings: ImportMappings,
    private val queries: ImportDryRunQueries,
    private val views: ImportViews,
    private val clearances: Clearances,
    private val transactions: TenantTransactions,
) {
    @PostMapping("/dry-run")
    @RequiresPermission(Permission.MEMO_IMPORT)
    @Operation(operationId = "startImportDryRun", summary = "Run the dry run (a job; the batch is DRY_RUN until it ends)")
    fun dryRun(
        @PathVariable id: UUID,
    ): ResponseEntity<ImportBatchView> {
        val view = transactions.inTenant(TenantContext.current()) { views.of(dryRuns.request(id)) }
        return ResponseEntity.status(HttpStatus.ACCEPTED).eTag(etagOf(view.version)).body(view)
    }

    @GetMapping("/problems")
    @RequiresPermission(Permission.MEMO_IMPORT)
    @Operation(operationId = "listImportProblems", summary = "A batch's problems, filtered, 100 a page")
    fun problems(
        @PathVariable id: UUID,
        @Parameter(description = "Comma-separated levels (BATCH, ERROR, WARNING, FILE, INFO).")
        @RequestParam(required = false) level: List<ProblemLevel>?,
        @Parameter(description = "Comma-separated codes.")
        @RequestParam(required = false) code: List<String>?,
        @RequestParam(required = false) sheet: ProblemSheet?,
        @Parameter(description = "The sheet's row number.")
        @RequestParam(required = false) row: Int?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): ResponseEntity<ImportProblemPage> {
        val size = (limit ?: PAGE).coerceIn(1, PAGE)
        val filter = ProblemFilter(level.orEmpty().toSet(), code.orEmpty().toSet(), sheet, row)
        val page =
            transactions.readInTenant(TenantContext.current()) {
                batches.get(id)
                val found = queries.problems(id, filter, cursor?.let(::decodeProblem), size + 1)
                val (byLevel, byCode) = queries.problemCounts(id)
                val next = if (found.size > size) found[size - 1].let { encode("${it.level}|${it.code}|${it.id}") } else null
                ImportProblemPage(found.take(size).map(::problem), byLevel, byCode, next)
            }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page)
    }

    @GetMapping("/rows")
    @RequiresPermission(Permission.MEMO_IMPORT)
    @Operation(operationId = "listImportRows", summary = "A batch's Memos rows with their results, in row order")
    fun rows(
        @PathVariable id: UUID,
        @Parameter(description = "Comma-separated results (READY, REJECTED, ...).")
        @RequestParam(required = false) result: List<RowResult>?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): ResponseEntity<ImportRowPage> {
        val size = (limit ?: PAGE).coerceIn(1, PAGE)
        val page =
            transactions.readInTenant(TenantContext.current()) {
                batches.get(id)
                val after = cursor?.let { decode(it).toIntOrNull() ?: throw IllegalArgumentException("invalid cursor") }
                val found = queries.rows(id, result.orEmpty().toSet(), after, size + 1)
                val shown = found.take(size)
                val codes = queries.codes(shown.map { it.id })
                val clearance = clearances.rankOf(listOf(ImportBatches.user())).values.firstOrNull() ?: 0
                val top = clearances.levels().maxOfOrNull { it.rank } ?: 0
                val rows =
                    shown.map { r ->
                        val visible = (r.rank ?: top) <= clearance
                        ImportRowView(
                            r.id,
                            r.rowNumber,
                            r.legacyNumber,
                            r.result,
                            r.errors,
                            r.warnings,
                            codes[r.id].orEmpty(),
                            r.memoId,
                            r.subject.takeIf { visible },
                            !visible,
                        )
                    }
                ImportRowPage(rows, if (found.size > size) encode(shown.last().rowNumber.toString()) else null)
            }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page)
    }

    @GetMapping("/mappings")
    @RequiresPermission(Permission.MEMO_IMPORT)
    @Operation(operationId = "listImportMappings", summary = "A batch's mappings with their row counts and proposals")
    fun mappings(
        @PathVariable id: UUID,
        @RequestParam(required = false) kind: MappingKind?,
    ): ResponseEntity<ImportMappingsView> {
        val view =
            transactions.readInTenant(TenantContext.current()) {
                val found = mappings.list(id, kind)
                ImportMappingsView(batches.get(id).version, found.map(::mapping), mappings.targets(kind, found).map(::target))
            }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .eTag(etagOf(view.batchVersion))
            .body(view)
    }

    @PutMapping("/mappings/{kind}")
    @RequiresPermission(Permission.MEMO_IMPORT)
    @Operation(operationId = "saveImportMappings", summary = "Confirm or change mappings of one kind (the batch then needs a new dry run)")
    fun save(
        @PathVariable id: UUID,
        @PathVariable kind: MappingKind,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: List<ImportMappingRequest>,
    ): ResponseEntity<ImportMappingsView> {
        val version = ifMatchVersion(ifMatch)
        val changes =
            body.map {
                MappingChange(
                    it.legacyValue,
                    it.target?.trim()?.takeIf(String::isNotEmpty),
                    it.legacyOrder,
                    it.confirmed,
                )
            }
        val view =
            transactions.inTenant(TenantContext.current()) {
                val saved = mappings.save(id, version, kind, changes)
                val found = mappings.list(id, kind)
                ImportMappingsView(saved, found.map(::mapping), mappings.targets(kind, found).map(::target))
            }
        return ResponseEntity.ok().eTag(etagOf(view.batchVersion)).body(view)
    }

    private fun problem(p: StoredProblem) =
        ImportProblemView(p.id, p.stage, p.level, p.code, p.sheet, p.rowNumber, p.column, p.rowId, p.intakeId, p.params)

    private fun mapping(m: ImportMapping) =
        ImportMappingView(m.kind, m.legacyValue, m.rowCount, m.legacyOrder, m.target, m.proposed, m.confirmed, m.confirmedBy, m.confirmedAt)

    private fun target(t: MappingTarget) = ImportMappingTargetView(t.kind, t.id, t.label, t.detail, t.rank)

    private companion object {
        const val PAGE = 100
        const val POSITION_PARTS = 3

        fun encode(text: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

        fun decode(cursor: String): String =
            runCatching { String(Base64.getUrlDecoder().decode(cursor)) }.getOrNull() ?: throw IllegalArgumentException("invalid cursor")

        fun decodeProblem(cursor: String): ProblemPosition {
            val parts = decode(cursor).split('|', limit = POSITION_PARTS)
            val id = parts.getOrNull(POSITION_PARTS - 1)?.let(ImportIntake::uuidOrNull)
            require(parts.size == POSITION_PARTS && id != null) { "invalid cursor" }
            return ProblemPosition(parts[0], parts[1], id)
        }
    }
}
