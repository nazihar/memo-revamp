package com.memo.minutes.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.MinutesCorrectionRecord
import com.memo.jooq.tables.records.MinutesExtractRecord
import com.memo.jooq.tables.references.MINUTES_CORRECTION
import com.memo.jooq.tables.references.MINUTES_EXTRACT
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Where a correction to approved minutes is (design E06 §1.6.5). */
enum class CorrectionState { PROPOSED, APPROVED, DECLINED }

/** A correction to approved minutes (design E06 §1.5, T8), as stored. */
data class CorrectionRow(
    val id: UUID,
    val meetingId: UUID,
    val forumId: UUID,
    /** The item it corrects; null for the minutes in general. */
    val itemId: UUID?,
    val text: String,
    val reason: String,
    val proposedBy: UUID,
    val proposedAt: Instant,
    val state: CorrectionState,
    val decidedBy: UUID?,
    val decidedAt: Instant?,
    val decisionNote: String?,
    /** The chair's authenticated approval (APPROVED only). */
    val approvalId: UUID?,
    /** The record version that carries it (APPROVED only). */
    val recordVersion: Int?,
    val version: Long,
)

/** Corrections to approved minutes (design E06 §1.6.5), inside a tenant transaction; decided once by a guarded update. */
@Repository
class MinutesCorrectionStore(
    private val tenantDsl: TenantDsl,
) {
    fun insert(row: CorrectionRow) {
        val c = MINUTES_CORRECTION
        tenantDsl
            .current()
            .insertInto(c)
            .set(c.ID, row.id)
            .set(c.TENANT_ID, tenantDsl.tenant().value)
            .set(c.MEETING_ID, row.meetingId)
            .set(c.FORUM_ID, row.forumId)
            .set(c.ITEM_ID, row.itemId)
            .set(c.TEXT, row.text)
            .set(c.REASON, row.reason)
            .set(c.PROPOSED_BY, row.proposedBy)
            .set(c.PROPOSED_AT, row.proposedAt.atOffset(ZoneOffset.UTC))
            .set(c.STATE, CorrectionState.PROPOSED.name)
            .set(c.VERSION, 0L)
            .execute()
    }

    /** [meetingId]'s corrections, oldest first. */
    fun of(meetingId: UUID): List<CorrectionRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES_CORRECTION)
            .where(MINUTES_CORRECTION.MEETING_ID.eq(meetingId))
            .orderBy(MINUTES_CORRECTION.PROPOSED_AT, MINUTES_CORRECTION.ID)
            .fetch { it.row() }

    fun find(id: UUID): CorrectionRow? =
        tenantDsl.current().selectFrom(MINUTES_CORRECTION).where(MINUTES_CORRECTION.ID.eq(id)).fetchOne {
            it.row()
        }

    /** [id], locked to the end of the transaction. */
    fun lock(id: UUID): CorrectionRow? =
        tenantDsl
            .current()
            .selectFrom(MINUTES_CORRECTION)
            .where(MINUTES_CORRECTION.ID.eq(id))
            .forUpdate()
            .fetchOne { it.row() }

    /** [forumId]'s corrections approved at or after [since], oldest first. */
    fun approvedSince(
        forumId: UUID,
        since: Instant,
    ): List<CorrectionRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES_CORRECTION)
            .where(
                MINUTES_CORRECTION.FORUM_ID.eq(forumId),
                MINUTES_CORRECTION.STATE.eq(CorrectionState.APPROVED.name),
                MINUTES_CORRECTION.DECIDED_AT.ge(since.atOffset(ZoneOffset.UTC)),
            ).orderBy(MINUTES_CORRECTION.DECIDED_AT, MINUTES_CORRECTION.ID)
            .fetch { it.row() }

    /** When the last correction to [meetingId]'s minutes was approved ("Corrected on …"); null when none was. */
    fun correctedAt(meetingId: UUID): Instant? =
        tenantDsl
            .current()
            .select(DSL.max(MINUTES_CORRECTION.DECIDED_AT))
            .from(MINUTES_CORRECTION)
            .where(MINUTES_CORRECTION.MEETING_ID.eq(meetingId), MINUTES_CORRECTION.STATE.eq(CorrectionState.APPROVED.name))
            .fetchOne { it.value1()?.toInstant() }

    /** Writes [after]'s decision over [before] (PROPOSED); false when it was decided in between. */
    fun decide(
        before: CorrectionRow,
        after: CorrectionRow,
    ): Boolean {
        val c = MINUTES_CORRECTION
        return tenantDsl
            .current()
            .update(c)
            .set(c.STATE, after.state.name)
            .set(c.DECIDED_BY, after.decidedBy)
            .set(c.DECIDED_AT, after.decidedAt?.atOffset(ZoneOffset.UTC))
            .set(c.DECISION_NOTE, after.decisionNote)
            .set(c.APPROVAL_ID, after.approvalId)
            .set(c.RECORD_VERSION, after.recordVersion)
            .set(c.VERSION, before.version + 1)
            .where(c.ID.eq(before.id), c.VERSION.eq(before.version), c.STATE.eq(CorrectionState.PROPOSED.name))
            .execute() == 1
    }

    private fun MinutesCorrectionRecord.row() =
        CorrectionRow(
            id = id,
            meetingId = meetingId,
            forumId = forumId,
            itemId = itemId,
            text = text,
            reason = reason,
            proposedBy = proposedBy,
            proposedAt = proposedAt.toInstant(),
            state = CorrectionState.valueOf(state ?: CorrectionState.PROPOSED.name),
            decidedBy = decidedBy,
            decidedAt = decidedAt?.toInstant(),
            decisionNote = decisionNote,
            approvalId = approvalId,
            recordVersion = recordVersion,
            version = version ?: 0,
        )
}

/** Whether the minutes were approved when an extract was issued (design E06 §1.6.6). */
enum class ExtractMinutesState { NOT_APPROVED, APPROVED }

/** A certified extract of one resolution (design E06 §1.5, T8), as stored. */
data class ExtractRow(
    val id: UUID,
    val resolutionId: UUID,
    val meetingId: UUID,
    val forumId: UUID,
    val issuedBy: UUID,
    val issuedAt: Instant,
    val minutesState: ExtractMinutesState,
    val minutesApprovedAt: Instant?,
    /** ADR-0051's fields of the secretary's authentication (`authentication`) and client (`client`). */
    val certification: JsonNode,
    val certificationHash: String,
    val state: RecordState,
    val error: String?,
    val fileId: UUID?,
    val fileVersionId: UUID?,
    val sha256: String?,
    val sizeBytes: Long?,
    val pages: Int?,
    val compliant: Boolean?,
    val validation: Map<String, Any?>?,
    val builtAt: Instant?,
    val version: Long,
)

/** The resolutions' certified extracts (design E06 §1.6.6), inside a tenant transaction; built by a guarded update. */
@Repository
class MinutesExtractStore(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
) {
    fun insert(row: ExtractRow) {
        val e = MINUTES_EXTRACT
        tenantDsl
            .current()
            .insertInto(e)
            .set(e.ID, row.id)
            .set(e.TENANT_ID, tenantDsl.tenant().value)
            .set(e.RESOLUTION_ID, row.resolutionId)
            .set(e.MEETING_ID, row.meetingId)
            .set(e.FORUM_ID, row.forumId)
            .set(e.ISSUED_BY, row.issuedBy)
            .set(e.ISSUED_AT, row.issuedAt.atOffset(ZoneOffset.UTC))
            .set(e.MINUTES_STATE, row.minutesState.name)
            .set(e.MINUTES_APPROVED_AT, row.minutesApprovedAt?.atOffset(ZoneOffset.UTC))
            .set(e.CERTIFICATION, JSONB.jsonb(json.writeValueAsString(row.certification)))
            .set(e.CERTIFICATION_HASH, row.certificationHash)
            .set(e.STATE, RecordState.PENDING.name)
            .set(e.VERSION, 0L)
            .execute()
    }

    fun find(id: UUID): ExtractRow? =
        tenantDsl
            .current()
            .selectFrom(MINUTES_EXTRACT)
            .where(MINUTES_EXTRACT.ID.eq(id))
            .fetchOne { it.row() }

    /** [resolutionId]'s extracts, the latest first. */
    fun of(resolutionId: UUID): List<ExtractRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES_EXTRACT)
            .where(MINUTES_EXTRACT.RESOLUTION_ID.eq(resolutionId))
            .orderBy(MINUTES_EXTRACT.ISSUED_AT.desc(), MINUTES_EXTRACT.ID.desc())
            .fetch { it.row() }

    fun lock(id: UUID): ExtractRow? =
        tenantDsl
            .current()
            .selectFrom(MINUTES_EXTRACT)
            .where(MINUTES_EXTRACT.ID.eq(id))
            .forUpdate()
            .fetchOne { it.row() }

    /** The made extracts after [after] in id order, at most [limit] (the weekly fixity run). */
    fun readyAfter(
        after: UUID?,
        limit: Int,
    ): List<ExtractRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES_EXTRACT)
            .where(MINUTES_EXTRACT.STATE.eq(RecordState.READY.name), after?.let { MINUTES_EXTRACT.ID.gt(it) } ?: DSL.noCondition())
            .orderBy(MINUTES_EXTRACT.ID)
            .limit(limit)
            .fetch { it.row() }

    /** Writes [after]'s build over [before]; false when it moved on in between. */
    fun update(
        before: ExtractRow,
        after: ExtractRow,
    ): Boolean {
        val e = MINUTES_EXTRACT
        return tenantDsl
            .current()
            .update(e)
            .set(e.STATE, after.state.name)
            .set(e.ERROR, after.error)
            .set(e.FILE_ID, after.fileId)
            .set(e.FILE_VERSION_ID, after.fileVersionId)
            .set(e.SHA256, after.sha256)
            .set(e.SIZE_BYTES, after.sizeBytes)
            .set(e.PAGES, after.pages)
            .set(e.COMPLIANT, after.compliant)
            .set(e.VALIDATION, after.validation?.let { JSONB.jsonb(json.writeValueAsString(it)) })
            .set(e.BUILT_AT, after.builtAt?.atOffset(ZoneOffset.UTC))
            .set(e.VERSION, before.version + 1)
            .where(e.ID.eq(before.id), e.VERSION.eq(before.version))
            .execute() == 1
    }

    private fun MinutesExtractRecord.row() =
        ExtractRow(
            id = id,
            resolutionId = resolutionId,
            meetingId = meetingId,
            forumId = forumId,
            issuedBy = issuedBy,
            issuedAt = issuedAt.toInstant(),
            minutesState = ExtractMinutesState.valueOf(minutesState),
            minutesApprovedAt = minutesApprovedAt?.toInstant(),
            certification = json.readTree(certification.data()),
            certificationHash = certificationHash,
            state = RecordState.valueOf(state ?: RecordState.PENDING.name),
            error = error,
            fileId = fileId,
            fileVersionId = fileVersionId,
            sha256 = sha256,
            sizeBytes = sizeBytes,
            pages = pages,
            compliant = compliant,
            validation = validation?.let { json.readValue(it.data(), VALIDATION) },
            builtAt = builtAt?.toInstant(),
            version = version ?: 0,
        )

    private companion object {
        val VALIDATION = object : TypeReference<Map<String, Any?>>() {}
    }
}
