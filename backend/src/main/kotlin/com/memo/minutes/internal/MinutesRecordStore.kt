package com.memo.minutes.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.MinutesRecordRecord
import com.memo.jooq.tables.references.MINUTES_RECORD
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Where a version of the approved minutes' record is (ADR-0066). */
enum class RecordState { PENDING, READY, FAILED }

/** One version of the approved minutes' record (design E06 §1.5, T7), as stored. */
data class RecordRow(
    val id: UUID,
    val meetingId: UUID,
    val forumId: UUID,
    val recordVersion: Int,
    /** The approval it records: the chair's confirmation for version 1. */
    val approvalId: UUID,
    val docxVersionId: UUID,
    val docxSha256: String,
    val state: RecordState,
    val error: String?,
    val fileId: UUID?,
    val fileVersionId: UUID?,
    val sha256: String?,
    val sizeBytes: Long?,
    val pages: Int?,
    val compliant: Boolean?,
    val validation: Map<String, Any?>?,
    val createdAt: Instant,
    val builtAt: Instant?,
    val version: Long,
    /** When it last failed (null: never, or before the sweep kept it). */
    val failedAt: Instant? = null,
    /** How many times it was asked for again after failing (the sweep, a secretary). */
    val retries: Int = 0,
)

/** The record's versions (ADR-0066), inside a tenant transaction; a version changes only by a guarded update. */
@Repository
class MinutesRecordStore(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
) {
    /** Writes [row] PENDING; false when [row]'s version of the meeting's record exists. */
    fun insert(row: RecordRow): Boolean {
        val r = MINUTES_RECORD
        return tenantDsl
            .current()
            .insertInto(r)
            .set(r.ID, row.id)
            .set(r.TENANT_ID, tenantDsl.tenant().value)
            .set(r.MEETING_ID, row.meetingId)
            .set(r.FORUM_ID, row.forumId)
            .set(r.RECORD_VERSION, row.recordVersion)
            .set(r.APPROVAL_ID, row.approvalId)
            .set(r.DOCX_VERSION_ID, row.docxVersionId)
            .set(r.DOCX_SHA256, row.docxSha256)
            .set(r.STATE, RecordState.PENDING.name)
            .set(r.CREATED_AT, row.createdAt.atOffset(ZoneOffset.UTC))
            .set(r.VERSION, 0L)
            .onConflictDoNothing()
            .execute() == 1
    }

    /** [meetingId]'s record versions, the newest first. */
    fun of(meetingId: UUID): List<RecordRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES_RECORD)
            .where(MINUTES_RECORD.MEETING_ID.eq(meetingId))
            .orderBy(MINUTES_RECORD.RECORD_VERSION.desc())
            .fetch { it.row() }

    /** Version [recordVersion] of [meetingId]'s record. */
    fun find(
        meetingId: UUID,
        recordVersion: Int,
    ): RecordRow? =
        tenantDsl
            .current()
            .selectFrom(MINUTES_RECORD)
            .where(MINUTES_RECORD.MEETING_ID.eq(meetingId), MINUTES_RECORD.RECORD_VERSION.eq(recordVersion))
            .fetchOne { it.row() }

    /** [id], locked to the end of the transaction. */
    fun lock(id: UUID): RecordRow? =
        tenantDsl
            .current()
            .selectFrom(MINUTES_RECORD)
            .where(MINUTES_RECORD.ID.eq(id))
            .forUpdate()
            .fetchOne { it.row() }

    /** The made versions after [after] in id order, at most [limit] (the weekly fixity run). */
    fun readyAfter(
        after: UUID?,
        limit: Int,
    ): List<RecordRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES_RECORD)
            .where(
                MINUTES_RECORD.STATE.eq(RecordState.READY.name),
                after?.let { MINUTES_RECORD.ID.gt(it) } ?: DSL.noCondition(),
            ).orderBy(MINUTES_RECORD.ID)
            .limit(limit)
            .fetch { it.row() }

    /** The failed versions asked for again fewer than [below] times, in id order, at most [limit] (the sweep). */
    fun failed(
        below: Int,
        limit: Int,
    ): List<RecordRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES_RECORD)
            .where(MINUTES_RECORD.STATE.eq(RecordState.FAILED.name), MINUTES_RECORD.RETRIES.lt(below))
            .orderBy(MINUTES_RECORD.ID)
            .limit(limit)
            .fetch { it.row() }

    /** Writes [after]'s build over [before]; false when the version moved on in between. */
    fun update(
        before: RecordRow,
        after: RecordRow,
    ): Boolean {
        val r = MINUTES_RECORD
        return tenantDsl
            .current()
            .update(r)
            .set(r.STATE, after.state.name)
            .set(r.ERROR, after.error)
            .set(r.FILE_ID, after.fileId)
            .set(r.FILE_VERSION_ID, after.fileVersionId)
            .set(r.SHA256, after.sha256)
            .set(r.SIZE_BYTES, after.sizeBytes)
            .set(r.PAGES, after.pages)
            .set(r.COMPLIANT, after.compliant)
            .set(r.VALIDATION, after.validation?.let { JSONB.jsonb(json.writeValueAsString(it)) })
            .set(r.BUILT_AT, after.builtAt?.atOffset(ZoneOffset.UTC))
            .set(r.FAILED_AT, after.failedAt?.atOffset(ZoneOffset.UTC))
            .set(r.RETRIES, after.retries)
            .set(r.VERSION, before.version + 1)
            .where(r.ID.eq(before.id), r.VERSION.eq(before.version))
            .execute() == 1
    }

    private fun MinutesRecordRecord.row() =
        RecordRow(
            id = id,
            meetingId = meetingId,
            forumId = forumId,
            recordVersion = recordVersion,
            approvalId = approvalId,
            docxVersionId = docxVersionId,
            docxSha256 = docxSha256,
            state = RecordState.valueOf(state ?: RecordState.PENDING.name),
            error = error,
            fileId = fileId,
            fileVersionId = fileVersionId,
            sha256 = sha256,
            sizeBytes = sizeBytes,
            pages = pages,
            compliant = compliant,
            validation = validation?.let { json.readValue(it.data(), VALIDATION) },
            createdAt = createdAt.toInstant(),
            builtAt = builtAt?.toInstant(),
            version = version ?: 0,
            failedAt = failedAt?.toInstant(),
            retries = retries ?: 0,
        )

    private companion object {
        val VALIDATION = object : TypeReference<Map<String, Any?>>() {}
    }
}
