package com.memo.imports.internal.dryrun

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.IMPORTS_PROBLEM
import com.memo.jooq.tables.references.IMPORTS_ROW
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID

/** How much a problem weighs (design E06 §9): BATCH stops the batch, ERROR rejects its row, the rest are listed. */
enum class ProblemLevel { BATCH, ERROR, WARNING, FILE, INFO }

/** Where a problem is: a sheet of the metadata, the originals received, or the batch as a whole. */
enum class ProblemSheet { MEMOS, FILES, APPROVALS, ORIGINALS, BATCH }

/** Which stage found a problem. */
enum class ProblemStage { DRY_RUN, IMPORT, VERIFY }

/** A Memos row's result (design E06 §4.2). */
enum class RowResult { PENDING, READY, REJECTED, IMPORTING, IMPORTED, SKIPPED, FAILED, NOT_STARTED }

/**
 * A problem found: its [code] and [level], where (the sheet, its row number and column), and [params] for the message
 * (column names, values the admin typed, counts and limits; never subjects or remarks). [intakeId] names an original.
 */
data class Finding(
    val level: ProblemLevel,
    val code: String,
    val sheet: ProblemSheet?,
    val rowNumber: Int? = null,
    val column: String? = null,
    val params: Map<String, Any> = emptyMap(),
    val intakeId: UUID? = null,
)

/** What the validation decided for one Memos row. */
data class RowOutcome(
    val rowId: UUID,
    val result: RowResult,
    /** Lower-case hex SHA-256 of the canonical row (design E06 §5.3: same key and fingerprint is skipped). */
    val fingerprint: String,
    /** The highest rank the row writes (its memo's level or a stricter original's); null when unmapped. */
    val rank: Int?,
    val findings: List<Finding>,
    /** All of the row's errors and warnings, also those past the per-row cap of recorded problems. */
    val errors: Int = findings.count { it.level == ProblemLevel.ERROR },
    val warnings: Int = findings.count { it.level == ProblemLevel.WARNING },
)

/** Writes the dry run's problems and row results; staging tables only, inside a tenant transaction. */
@Repository
class FindingStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    /** Problems of [batchId], attached to the Memos row [rowId] when given. */
    fun add(
        batchId: UUID,
        rowId: UUID?,
        findings: List<Finding>,
        stage: ProblemStage = ProblemStage.DRY_RUN,
    ) {
        if (findings.isEmpty()) return
        val p = IMPORTS_PROBLEM
        val tenant = tenantDsl.tenant().value
        findings
            .fold(
                tenantDsl.current().insertInto(
                    p,
                    p.ID,
                    p.TENANT_ID,
                    p.BATCH_ID,
                    p.STAGE,
                    p.LEVEL,
                    p.CODE,
                    p.SHEET,
                    p.ROW_NUMBER,
                    p.COLUMN_NAME,
                    p.ROW_ID,
                    p.FILE_ID,
                    p.PARAMS,
                ),
            ) { insert, f ->
                insert.values(
                    ids.next(),
                    tenant,
                    batchId,
                    stage.name,
                    f.level.name,
                    f.code,
                    f.sheet?.name,
                    f.rowNumber,
                    f.column?.take(MAX_COLUMN),
                    rowId,
                    f.intakeId,
                    f.params.takeIf { it.isNotEmpty() }?.let { JSONB.valueOf(json.writeValueAsString(it)) },
                )
            }.execute()
    }

    /** Each row's result, counts, fingerprint and rank, and its problems. */
    fun results(
        batchId: UUID,
        outcomes: List<RowOutcome>,
    ) {
        if (outcomes.isEmpty()) return
        val dsl = tenantDsl.current()
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        val r = IMPORTS_ROW
        dsl
            .batch(
                outcomes.map { o ->
                    dsl
                        .update(r)
                        .set(r.RESULT, o.result.name)
                        .set(r.ERRORS, o.errors)
                        .set(r.WARNINGS, o.warnings)
                        .set(r.FINGERPRINT, HexFormat.of().parseHex(o.fingerprint))
                        .set(
                            r.DATA,
                            DSL.field(
                                "jsonb_set({0}, {1}::text[], coalesce(to_jsonb({2}::int), 'null'::jsonb))",
                                JSONB::class.java,
                                r.DATA,
                                DSL.inline("{${StagingStore.RANK}}"),
                                DSL.`val`(o.rank),
                            ),
                        ).set(r.UPDATED_AT, now)
                        .where(r.ID.eq(o.rowId))
                },
            ).execute()
        outcomes.filter { it.findings.isNotEmpty() }.forEach { add(batchId, it.rowId, it.findings) }
    }

    private companion object {
        const val MAX_COLUMN = 200
    }
}
