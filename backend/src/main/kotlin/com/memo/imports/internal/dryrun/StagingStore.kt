package com.memo.imports.internal.dryrun

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.IMPORTS_PROBLEM
import com.memo.jooq.tables.references.IMPORTS_ROW
import com.memo.jooq.tables.references.IMPORTS_ROW_APPROVAL
import com.memo.jooq.tables.references.IMPORTS_ROW_FILE
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A Memos row as staged: its values by column key (blank ones left out). */
data class StagedMemo(
    val rowNumber: Int,
    val cells: Map<String, String>,
) {
    val legacyNumber: String? get() = cells[MemoColumns.LEGACY_NUMBER]
    val key: String? get() = legacyNumber?.let(com.memo.memo.LegacyNumbers::key)?.takeIf { it.isNotEmpty() }
}

/** A Files row as staged; [path] is normalised (NFC, `/`) when it is a valid path, else as given. */
data class StagedFile(
    val rowNumber: Int,
    val key: String?,
    val path: String?,
    val role: String?,
    val title: String?,
    val sha256: String?,
    val classification: String?,
)

/** An Approvals row as staged, its values as given (the order and date are parsed when the row is validated). */
data class StagedApproval(
    val rowNumber: Int,
    val key: String?,
    val ordinal: String?,
    val name: String?,
    val email: String?,
    val legacyId: String?,
    val position: String?,
    val decision: String?,
    val decidedOn: String?,
    val remark: String?,
)

/** The column keys of the Memos sheet the dry run reads (design E06 §6.1). */
object MemoColumns {
    const val LEGACY_NUMBER = "legacy_number"
    const val MEMO_TYPE = "memo_type"
    const val SUBJECT = "subject"
    const val STATUS = "status"
    const val CLASSIFICATION = "classification"
    const val ORIGIN_UNIT = "origin_unit"
    const val RAISED_ON = "raised_on"
    const val DECIDED_ON = "decided_on"
    const val CLOSED_ON = "closed_on"
    const val INITIATOR_NAME = "initiator_name"
    const val INITIATOR_EMAIL = "initiator_email"
    const val INITIATOR_ID = "initiator_id"
}

/**
 * Writes a batch's staging rows (design E06 §3.2, §5.2 step 1): the Memos, Files and Approvals rows of a dry run, which
 * replace the previous dry run's. Staging tables only; inside a tenant transaction.
 */
@Repository
class StagingStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    /** Removes the batch's staging rows and problems (the mappings stay: confirmed ones are kept across dry runs). */
    fun clear(batchId: UUID) {
        val dsl = tenantDsl.current()
        dsl.deleteFrom(IMPORTS_PROBLEM).where(IMPORTS_PROBLEM.BATCH_ID.eq(batchId)).execute()
        dsl.deleteFrom(IMPORTS_ROW_FILE).where(IMPORTS_ROW_FILE.BATCH_ID.eq(batchId)).execute()
        dsl.deleteFrom(IMPORTS_ROW_APPROVAL).where(IMPORTS_ROW_APPROVAL.BATCH_ID.eq(batchId)).execute()
        dsl.deleteFrom(IMPORTS_ROW).where(IMPORTS_ROW.BATCH_ID.eq(batchId)).execute()
    }

    fun memos(
        batchId: UUID,
        rows: List<StagedMemo>,
    ) {
        if (rows.isEmpty()) return
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        val tenant = tenantDsl.tenant().value
        val r = IMPORTS_ROW
        rows
            .fold(
                tenantDsl.current().insertInto(
                    r,
                    r.ID,
                    r.TENANT_ID,
                    r.BATCH_ID,
                    r.ROW_NUMBER,
                    r.LEGACY_NUMBER,
                    r.LEGACY_NUMBER_KEY,
                    r.DATA,
                    r.RESULT,
                    r.UPDATED_AT,
                ),
            ) { insert, row ->
                insert.values(
                    ids.next(),
                    tenant,
                    batchId,
                    row.rowNumber,
                    row.legacyNumber,
                    row.key,
                    JSONB.valueOf(json.writeValueAsString(mapOf(CELLS to row.cells))),
                    PENDING,
                    now,
                )
            }.execute()
    }

    fun files(
        batchId: UUID,
        rows: List<StagedFile>,
    ) {
        if (rows.isEmpty()) return
        val tenant = tenantDsl.tenant().value
        val f = IMPORTS_ROW_FILE
        rows
            .fold(
                tenantDsl.current().insertInto(
                    f,
                    f.ID,
                    f.TENANT_ID,
                    f.BATCH_ID,
                    f.ROW_NUMBER,
                    f.LEGACY_NUMBER_KEY,
                    f.PATH,
                    f.ROLE,
                    f.TITLE,
                    f.EXPECTED_SHA256,
                    f.CLASSIFICATION,
                ),
            ) { insert, row ->
                insert.values(
                    ids.next(),
                    tenant,
                    batchId,
                    row.rowNumber,
                    row.key,
                    row.path,
                    row.role,
                    row.title,
                    row.sha256,
                    row.classification,
                )
            }.execute()
    }

    fun approvals(
        batchId: UUID,
        rows: List<StagedApproval>,
    ) {
        if (rows.isEmpty()) return
        val tenant = tenantDsl.tenant().value
        val a = IMPORTS_ROW_APPROVAL
        val dsl = tenantDsl.current()
        dsl
            .batch(
                rows.map { row ->
                    dsl
                        .insertInto(a)
                        .set(a.TENANT_ID, tenant)
                        .set(a.BATCH_ID, batchId)
                        .set(a.ROW_NUMBER, row.rowNumber)
                        .set(a.LEGACY_NUMBER_KEY, row.key)
                        .set(a.ORDINAL, row.ordinal)
                        .set(a.NAME, row.name)
                        .set(a.EMAIL, row.email)
                        .set(a.LEGACY_ID, row.legacyId)
                        .set(a.POSITION, row.position)
                        .set(a.DECISION, row.decision)
                        .set(a.DECIDED_ON, row.decidedOn)
                        .set(a.REMARK, row.remark)
                },
            ).execute()
    }

    /** Attaches Files and Approvals rows to their Memos row by legacy number key (the first Memos row with that key). */
    fun group(batchId: UUID) {
        val dsl = tenantDsl.current()
        val r = IMPORTS_ROW
        val f = IMPORTS_ROW_FILE
        val a = IMPORTS_ROW_APPROVAL

        fun memoOf(key: org.jooq.Field<String?>) =
            dsl
                .select(r.ID)
                .from(r)
                .where(r.BATCH_ID.eq(batchId), r.LEGACY_NUMBER_KEY.eq(key))
                .orderBy(r.ROW_NUMBER)
                .limit(1)
                .asField<UUID>()
        dsl
            .update(f)
            .set(f.ROW_ID, memoOf(f.LEGACY_NUMBER_KEY))
            .where(f.BATCH_ID.eq(batchId))
            .execute()
        dsl
            .update(a)
            .set(a.ROW_ID, memoOf(a.LEGACY_NUMBER_KEY))
            .where(a.BATCH_ID.eq(batchId))
            .execute()
    }

    companion object {
        const val CELLS = "cells"
        const val RANK = "rank"
        private const val PENDING = "PENDING"
    }
}
