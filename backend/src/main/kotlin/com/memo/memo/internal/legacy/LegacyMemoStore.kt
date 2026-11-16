package com.memo.memo.internal.legacy

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.MemoLegacyFileRecord
import com.memo.jooq.tables.records.MemoLegacyRecord
import com.memo.jooq.tables.references.MEMO_LEGACY
import com.memo.jooq.tables.references.MEMO_LEGACY_APPROVAL
import com.memo.jooq.tables.references.MEMO_LEGACY_CORRECTION
import com.memo.jooq.tables.references.MEMO_LEGACY_FILE
import com.memo.memo.ExistingImport
import com.memo.memo.LegacyApproval
import com.memo.memo.LegacyFileRole
import com.memo.memo.LegacyMemoRow
import com.memo.memo.LegacyNumbers
import com.memo.memo.LegacyPerson
import com.memo.memo.MemoOrigin
import com.memo.memo.internal.Memo
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID
import com.memo.jooq.tables.references.MEMO_ as MEMO

/** An imported memo's legacy facts as stored (design E06 §3.1). */
data class LegacyFacts(
    val memoId: UUID,
    val batchId: UUID,
    val rowNumber: Int,
    val sourceCode: String,
    val legacyNumber: String,
    val fingerprint: String,
    val timeZone: String,
    val raisedOn: LocalDate,
    val decidedOn: LocalDate?,
    val closedOn: LocalDate?,
    val status: String,
    val type: String,
    val classification: String?,
    val unit: String,
    val subject: String,
    val initiator: LegacyPerson,
    val importedAt: Instant,
    val importedBy: UUID,
)

/** An original of an imported memo, with the hash and size it was imported with. */
data class LegacyFileRow(
    val id: UUID,
    val memoId: UUID,
    val fileId: UUID,
    val role: LegacyFileRole,
    val position: Int,
    val title: String,
    val relativePath: String,
    val classificationId: UUID?,
    val classificationRank: Int?,
    val sha256: String,
    val sizeBytes: Long,
) {
    /** The level that decides who may open it: its own when stricter, else the memo's. */
    fun effectiveRank(memoRank: Int): Int = maxOf(classificationRank ?: memoRank, memoRank)
}

/** A correction appended after import ([LegacyCorrections]). */
data class LegacyCorrectionRow(
    val id: UUID,
    val field: String,
    val before: String?,
    val after: String?,
    val reason: String,
    val correctedBy: UUID,
    val correctedAt: Instant,
)

/** The memo row of an imported memo, as [LegacyMemoWriter.memo] writes it. */
data class ImportedMemo(
    val row: LegacyMemoRow,
    val typeVersionId: UUID,
    val classificationRank: Int,
    val metadata: Map<String, Any>,
    val subject: String,
    val closedAt: OffsetDateTime?,
)

/** An original with what the files service stored for it. */
data class StoredOriginal(
    val fileId: UUID,
    val versionId: UUID,
    val role: LegacyFileRole,
    val title: String,
    val relativePath: String,
    val classificationId: UUID?,
    val classificationRank: Int?,
    val sha256: String,
    val sizeBytes: Long,
)

/** Writes an imported memo and its legacy rows (append-only, design E06 §3.1). Inside the caller's tenant transaction. */
@Repository
class LegacyMemoWriter(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
    private val ids: IdGenerator,
) {
    /** The memo row: final, numbered outside any series, no workflow; [now] is its creation time. */
    fun memo(
        memo: ImportedMemo,
        now: OffsetDateTime,
    ) {
        val row = memo.row
        tenantDsl
            .current()
            .insertInto(MEMO)
            .set(MEMO.ID, row.memoId)
            .set(MEMO.TENANT_ID, tenantDsl.tenant().value)
            .set(MEMO.ORIGIN, MemoOrigin.IMPORTED.name)
            .set(MEMO.REFERENCE, row.mapped.reference)
            .set(MEMO.MEMO_TYPE_ID, row.mapped.typeId)
            .set(MEMO.MEMO_TYPE_VERSION_ID, memo.typeVersionId)
            .set(MEMO.SUBJECT, memo.subject)
            .set(MEMO.CLASSIFICATION_ID, row.mapped.classificationId)
            .set(MEMO.CLASSIFICATION_RANK, memo.classificationRank)
            .set(MEMO.INITIATOR_ID, row.initiator.userId)
            .set(MEMO.ORIGIN_UNIT_ID, row.mapped.originUnitId)
            .set(MEMO.METADATA, JSONB.valueOf(json.writeValueAsString(memo.metadata)))
            .set(MEMO.STATUS, row.mapped.status.name)
            .set(MEMO.CLOSED_AT, memo.closedAt)
            .set(MEMO.CREATED_AT, now)
            .set(MEMO.UPDATED_AT, now)
            .execute()
    }

    /** The legacy facts as given. */
    fun facts(
        row: LegacyMemoRow,
        now: OffsetDateTime,
    ) {
        val given = row.given
        val source = row.source
        val l = MEMO_LEGACY
        tenantDsl
            .current()
            .insertInto(l)
            .set(l.TENANT_ID, tenantDsl.tenant().value)
            .set(l.MEMO_ID, row.memoId)
            .set(l.BATCH_ID, source.batchId)
            .set(l.ROW_NUMBER, source.rowNumber)
            .set(l.SOURCE_CODE, source.sourceCode)
            .set(l.LEGACY_NUMBER, source.legacyNumber)
            .set(l.LEGACY_NUMBER_KEY, LegacyNumbers.key(source.legacyNumber))
            .set(l.ROW_FINGERPRINT, HEX.parseHex(source.fingerprint))
            .set(l.TIME_ZONE, source.timeZone.id)
            .set(l.RAISED_ON, row.dates.raisedOn)
            .set(l.DECIDED_ON, row.dates.decidedOn)
            .set(l.CLOSED_ON, row.dates.closedOn)
            .set(l.LEGACY_STATUS, given.status)
            .set(l.LEGACY_TYPE, given.type)
            .set(l.LEGACY_CLASSIFICATION, given.classification.present())
            .set(l.LEGACY_UNIT, given.unit)
            .set(l.SUBJECT_AS_GIVEN, given.subject)
            .set(l.INITIATOR_NAME, row.initiator.name)
            .set(l.INITIATOR_EMAIL, row.initiator.email.present())
            .set(l.INITIATOR_LEGACY_ID, row.initiator.legacyId.present())
            .set(l.INITIATOR_USER_ID, row.initiator.userId)
            .set(l.IMPORTED_AT, now)
            .set(l.IMPORTED_BY, source.startedBy)
            .execute()
    }

    fun approvals(
        memoId: UUID,
        approvals: List<LegacyApproval>,
    ) {
        if (approvals.isEmpty()) return
        val a = MEMO_LEGACY_APPROVAL
        val dsl = tenantDsl.current()
        dsl
            .batch(
                approvals.map { approval ->
                    dsl
                        .insertInto(a)
                        .set(a.TENANT_ID, tenantDsl.tenant().value)
                        .set(a.MEMO_ID, memoId)
                        .set(a.ORDINAL, approval.ordinal)
                        .set(a.NAME, approval.person.name)
                        .set(a.EMAIL, approval.person.email.present())
                        .set(a.LEGACY_ID, approval.person.legacyId.present())
                        .set(a.USER_ID, approval.person.userId)
                        .set(a.POSITION_TEXT, approval.position.present())
                        .set(a.DECISION, approval.decision)
                        .set(a.DECIDED_ON, approval.decidedOn)
                        .set(a.REMARK, approval.remark.present())
                },
            ).execute()
    }

    /** The originals in the order given: positions from 0, the main document first. */
    fun originals(
        memoId: UUID,
        originals: List<StoredOriginal>,
    ) {
        val f = MEMO_LEGACY_FILE
        val dsl = tenantDsl.current()
        dsl
            .batch(
                originals.mapIndexed { position, original ->
                    dsl
                        .insertInto(f)
                        .set(f.ID, ids.next())
                        .set(f.TENANT_ID, tenantDsl.tenant().value)
                        .set(f.MEMO_ID, memoId)
                        .set(f.FILE_ID, original.fileId)
                        .set(f.ROLE, original.role.name)
                        .set(f.POSITION, position)
                        .set(f.TITLE, original.title)
                        .set(f.RELATIVE_PATH, original.relativePath)
                        .set(f.CLASSIFICATION_ID, original.classificationId)
                        .set(f.CLASSIFICATION_RANK, original.classificationRank)
                        .set(f.SHA256, HEX.parseHex(original.sha256))
                        .set(f.SIZE_BYTES, original.sizeBytes)
                },
            ).execute()
    }

    /** Appends [correction] (the legacy facts stay as given). */
    fun correction(
        correction: LegacyCorrection,
        at: OffsetDateTime,
    ) {
        val c = MEMO_LEGACY_CORRECTION
        tenantDsl
            .current()
            .insertInto(c)
            .set(c.ID, ids.next())
            .set(c.TENANT_ID, tenantDsl.tenant().value)
            .set(c.MEMO_ID, correction.memoId)
            .set(c.FIELD, correction.field.name)
            .set(c.VALUE_BEFORE, correction.before)
            .set(c.VALUE_AFTER, correction.after)
            .set(c.REASON, correction.reason)
            .set(c.CORRECTED_BY, correction.by)
            .set(c.CORRECTED_AT, at)
            .execute()
    }

    /**
     * The only change an imported memo's row takes (`memo_imported_guard`): its subject and its classification at [rank],
     * with a new version and update time; guarded on the version read. False when the memo changed meanwhile.
     */
    fun revise(
        memo: Memo,
        rank: Int,
        at: OffsetDateTime,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEMO)
            .set(MEMO.SUBJECT, memo.subject)
            .set(MEMO.CLASSIFICATION_ID, memo.classificationId)
            .set(MEMO.CLASSIFICATION_RANK, rank)
            .set(MEMO.VERSION, MEMO.VERSION.plus(1))
            .set(MEMO.UPDATED_AT, at)
            .where(MEMO.ID.eq(memo.id), MEMO.VERSION.eq(memo.version))
            .execute() == 1
}

/** Reads imported memos' legacy rows. Inside a tenant transaction. */
@Repository
class LegacyMemoReader(
    private val tenantDsl: TenantDsl,
) {
    fun facts(memoId: UUID): LegacyFacts? =
        tenantDsl
            .current()
            .selectFrom(MEMO_LEGACY)
            .where(MEMO_LEGACY.MEMO_ID.eq(memoId))
            .fetchOne()
            ?.let(::facts)

    /** The memo imported under the row key, if any. */
    fun existing(
        sourceCode: String,
        numberKey: String,
    ): ExistingImport? =
        tenantDsl
            .current()
            .select(MEMO_LEGACY.MEMO_ID, MEMO_LEGACY.BATCH_ID, MEMO_LEGACY.ROW_NUMBER, MEMO_LEGACY.ROW_FINGERPRINT)
            .from(MEMO_LEGACY)
            .where(MEMO_LEGACY.SOURCE_CODE.eq(sourceCode), MEMO_LEGACY.LEGACY_NUMBER_KEY.eq(numberKey))
            .fetchOne { ExistingImport(it.value1()!!, it.value2()!!, it.value3()!!, HEX.formatHex(it.value4()!!)) }

    /** Whether a memo of the tenant has [reference] as its number. */
    fun referenceTaken(reference: String): Boolean = tenantDsl.current().fetchExists(MEMO, MEMO.REFERENCE.eq(reference))

    fun approvals(memoId: UUID): List<LegacyApproval> =
        tenantDsl
            .current()
            .selectFrom(MEMO_LEGACY_APPROVAL)
            .where(MEMO_LEGACY_APPROVAL.MEMO_ID.eq(memoId))
            .orderBy(MEMO_LEGACY_APPROVAL.ORDINAL)
            .fetch { r ->
                LegacyApproval(
                    r.ordinal,
                    LegacyPerson(r.name, r.email, r.legacyId, r.userId),
                    r.decision,
                    r.positionText,
                    r.decidedOn,
                    r.remark,
                )
            }

    fun originals(memoId: UUID): List<LegacyFileRow> =
        tenantDsl
            .current()
            .selectFrom(MEMO_LEGACY_FILE)
            .where(MEMO_LEGACY_FILE.MEMO_ID.eq(memoId))
            .orderBy(MEMO_LEGACY_FILE.POSITION)
            .fetch(::original)

    /** Originals of every imported memo after [after] (by id), at most [limit]: the fixity check's pages. */
    fun originalsAfter(
        after: UUID?,
        limit: Int,
    ): List<LegacyFileRow> =
        tenantDsl
            .current()
            .selectFrom(MEMO_LEGACY_FILE)
            .where(after?.let { MEMO_LEGACY_FILE.ID.gt(it) } ?: DSL.noCondition())
            .orderBy(MEMO_LEGACY_FILE.ID)
            .limit(limit)
            .fetch(::original)

    fun original(fileId: UUID): LegacyFileRow? =
        tenantDsl
            .current()
            .selectFrom(MEMO_LEGACY_FILE)
            .where(MEMO_LEGACY_FILE.FILE_ID.eq(fileId))
            .fetchOne()
            ?.let(::original)

    fun corrections(memoId: UUID): List<LegacyCorrectionRow> =
        tenantDsl
            .current()
            .selectFrom(MEMO_LEGACY_CORRECTION)
            .where(MEMO_LEGACY_CORRECTION.MEMO_ID.eq(memoId))
            .orderBy(MEMO_LEGACY_CORRECTION.CORRECTED_AT, MEMO_LEGACY_CORRECTION.ID)
            .fetch { r ->
                LegacyCorrectionRow(r.id, r.field, r.valueBefore, r.valueAfter, r.reason, r.correctedBy, r.correctedAt.toInstant())
            }

    private fun facts(r: MemoLegacyRecord): LegacyFacts =
        LegacyFacts(
            memoId = r.memoId,
            batchId = r.batchId,
            rowNumber = r.rowNumber,
            sourceCode = r.sourceCode,
            legacyNumber = r.legacyNumber,
            fingerprint = HEX.formatHex(r.rowFingerprint),
            timeZone = r.timeZone,
            raisedOn = r.raisedOn,
            decidedOn = r.decidedOn,
            closedOn = r.closedOn,
            status = r.legacyStatus,
            type = r.legacyType,
            classification = r.legacyClassification,
            unit = r.legacyUnit,
            subject = r.subjectAsGiven,
            initiator = LegacyPerson(r.initiatorName, r.initiatorEmail, r.initiatorLegacyId, r.initiatorUserId),
            importedAt = r.importedAt.withOffsetSameInstant(ZoneOffset.UTC).toInstant(),
            importedBy = r.importedBy,
        )

    private fun original(r: MemoLegacyFileRecord): LegacyFileRow =
        LegacyFileRow(
            r.id,
            r.memoId,
            r.fileId,
            LegacyFileRole.valueOf(r.role),
            r.position,
            r.title,
            r.relativePath,
            r.classificationId,
            r.classificationRank,
            HEX.formatHex(r.sha256),
            r.sizeBytes,
        )
}

private val HEX: HexFormat = HexFormat.of()

/** An optional legacy value: a blank one is no value. */
private fun String?.present(): String? = this?.takeIf { it.isNotBlank() }
