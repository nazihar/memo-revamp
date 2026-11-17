package com.memo.memo.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.MemoRecord
import com.memo.jooq.tables.references.MEMO_PARTICIPANT
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import com.memo.jooq.tables.references.MEMO_ as MEMO

/** Where a memo is (design E01 §3). Approved, rejected, withdrawn and discarded memos never change again. */
enum class MemoStatus {
    DRAFT,
    IN_REVIEW,
    RETURNED,
    APPROVED,
    REJECTED,
    WITHDRAWN,
    DISCARDED,

    /** An administrator cancelled the workflow (design E03 §4.4). */
    CANCELLED,
    ;

    /** Fields, document and route may change only while the initiator holds the memo. */
    val editable: Boolean get() = this == DRAFT || this == RETURNED
}

enum class ParticipantKind { INITIATOR, APPROVER, NOTIFIED }

data class Memo(
    val id: UUID,
    /** The memo's number (ADR-0044); null until it is numbered, at submit or final approval as its type says. */
    val reference: String?,
    val typeId: UUID,
    val subject: String,
    val classificationId: UUID,
    val classificationRank: Int,
    val initiatorId: UUID,
    val originUnitId: UUID?,
    val status: MemoStatus,
    val documentFileId: UUID?,
    val workflowInstanceId: UUID?,
    val currentRound: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
    /** The type version the memo was submitted under (ADR-0043); null while it is a draft. */
    val memoTypeVersionId: UUID? = null,
    /** The values of its type's fields, in stored form ([MemoMetadata]). */
    val metadata: Map<String, Any> = emptyMap(),
)

/** The fields of a new memo. */
data class NewMemo(
    val id: UUID,
    val typeId: UUID,
    val subject: String,
    val classificationId: UUID,
    val classificationRank: Int,
    val initiatorId: UUID,
    val originUnitId: UUID?,
    val metadata: Map<String, Any> = emptyMap(),
)

/** Memos and their participants (design E01 §2). Every method runs inside a tenant transaction. */
@Repository
class MemoStore(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
) {
    fun insert(
        memo: NewMemo,
        now: OffsetDateTime,
    ) {
        tenantDsl
            .current()
            .insertInto(MEMO)
            .set(MEMO.ID, memo.id)
            .set(MEMO.TENANT_ID, tenantDsl.tenant().value)
            .set(MEMO.MEMO_TYPE_ID, memo.typeId)
            .set(MEMO.SUBJECT, memo.subject)
            .set(MEMO.CLASSIFICATION_ID, memo.classificationId)
            .set(MEMO.CLASSIFICATION_RANK, memo.classificationRank)
            .set(MEMO.INITIATOR_ID, memo.initiatorId)
            .set(MEMO.ORIGIN_UNIT_ID, memo.originUnitId)
            .set(MEMO.METADATA, json.jsonb(memo.metadata))
            .set(MEMO.STATUS, MemoStatus.DRAFT.name)
            .set(MEMO.CREATED_AT, now)
            .set(MEMO.UPDATED_AT, now)
            .execute()
        addParticipant(memo.id, memo.initiatorId, ParticipantKind.INITIATOR, now)
    }

    fun addParticipant(
        memo: UUID,
        user: UUID,
        kind: ParticipantKind,
        now: OffsetDateTime,
    ) {
        tenantDsl
            .current()
            .insertInto(MEMO_PARTICIPANT)
            .set(MEMO_PARTICIPANT.TENANT_ID, tenantDsl.tenant().value)
            .set(MEMO_PARTICIPANT.MEMO_ID, memo)
            .set(MEMO_PARTICIPANT.USER_ID, user)
            .set(MEMO_PARTICIPANT.KIND, kind.name)
            .set(MEMO_PARTICIPANT.CREATED_AT, now)
            .onConflictDoNothing()
            .execute()
    }

    /** The memo if [user] takes part in it and is cleared for its classification ([clearanceRank]); else null. */
    fun visible(
        id: UUID,
        user: UUID,
        clearanceRank: Int,
    ): Memo? =
        tenantDsl
            .current()
            .selectFrom(MEMO)
            .where(MEMO.ID.eq(id))
            .and(MEMO.CLASSIFICATION_RANK.le(clearanceRank))
            .andExists(
                DSL
                    .selectOne()
                    .from(MEMO_PARTICIPANT)
                    .where(MEMO_PARTICIPANT.MEMO_ID.eq(MEMO.ID), MEMO_PARTICIPANT.USER_ID.eq(user)),
            ).fetchOne()
            ?.let(json::memo)

    /** The memo, without any visibility check (callers decide). */
    fun find(id: UUID): Memo? =
        tenantDsl
            .current()
            .selectFrom(MEMO)
            .where(MEMO.ID.eq(id))
            .fetchOne()
            ?.let(json::memo)

    /** The memo with its row locked until commit, so uploads, submits and edits of one memo are serialised. */
    fun lock(id: UUID): Memo? =
        tenantDsl
            .current()
            .selectFrom(MEMO)
            .where(MEMO.ID.eq(id))
            .forUpdate()
            .fetchOne()
            ?.let(json::memo)

    /**
     * Saves subject, type, classification and metadata, guarded on [expectedVersion]; false when the memo changed meanwhile.
     * The database refuses a change of type or metadata once the memo is pinned (`memo_pinned_guard`).
     */
    fun updateFields(
        changed: Memo,
        expectedVersion: Long,
        now: OffsetDateTime,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEMO)
            .set(MEMO.SUBJECT, changed.subject)
            .set(MEMO.MEMO_TYPE_ID, changed.typeId)
            .set(MEMO.CLASSIFICATION_ID, changed.classificationId)
            .set(MEMO.CLASSIFICATION_RANK, changed.classificationRank)
            .set(MEMO.METADATA, json.jsonb(changed.metadata))
            .set(MEMO.VERSION, MEMO.VERSION.plus(1))
            .set(MEMO.UPDATED_AT, now)
            .where(MEMO.ID.eq(changed.id), MEMO.VERSION.eq(expectedVersion))
            .execute() == 1

    /**
     * Pins a draft to its type version [versionId] with its final [metadata] (ADR-0043), guarded on the draft state and
     * [expectedVersion]; false when the memo changed meanwhile. The version is not bumped: the submit that pins it
     * continues in the same transaction.
     */
    fun pin(
        id: UUID,
        expectedVersion: Long,
        versionId: UUID,
        metadata: Map<String, Any>,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEMO)
            .set(MEMO.MEMO_TYPE_VERSION_ID, versionId)
            .set(MEMO.METADATA, json.jsonb(metadata))
            .where(
                MEMO.ID.eq(id),
                MEMO.VERSION.eq(expectedVersion),
                MEMO.STATUS.eq(MemoStatus.DRAFT.name),
                MEMO.MEMO_TYPE_VERSION_ID.isNull,
            ).execute() == 1

    /** Moves the memo from [from] to [to], guarded on both; false when it is no longer in [from] at [expectedVersion]. */
    fun changeStatus(
        id: UUID,
        expectedVersion: Long,
        from: MemoStatus,
        to: MemoStatus,
        now: OffsetDateTime,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEMO)
            .set(MEMO.STATUS, to.name)
            .set(MEMO.VERSION, MEMO.VERSION.plus(1))
            .set(MEMO.UPDATED_AT, now)
            .where(MEMO.ID.eq(id), MEMO.VERSION.eq(expectedVersion), MEMO.STATUS.eq(from.name))
            .execute() == 1

    /** Records the memo's document file (its first upload); the version is not bumped, so the client's `If-Match` stays valid. */
    fun setDocument(
        id: UUID,
        fileId: UUID,
        now: OffsetDateTime,
    ) {
        tenantDsl
            .current()
            .update(MEMO)
            .set(MEMO.DOCUMENT_FILE_ID, fileId)
            .set(MEMO.UPDATED_AT, now)
            .where(MEMO.ID.eq(id), MEMO.DOCUMENT_FILE_ID.isNull)
            .execute()
    }

    /**
     * Follows the memo's workflow (T4, T5): its instance, round and status, from [WorkflowActionTaken] in the same
     * transaction. Not guarded by version: the workflow serialises actions on its instance row.
     */
    fun applyWorkflow(
        id: UUID,
        instanceId: UUID,
        round: Int,
        status: MemoStatus,
        now: OffsetDateTime,
    ) {
        tenantDsl
            .current()
            .update(MEMO)
            .set(MEMO.WORKFLOW_INSTANCE_ID, instanceId)
            .set(MEMO.CURRENT_ROUND, round)
            .set(MEMO.STATUS, status.name)
            .set(MEMO.VERSION, MEMO.VERSION.plus(1))
            .set(MEMO.UPDATED_AT, now)
            .where(MEMO.ID.eq(id))
            .execute()
    }
}

private fun JsonMapper.jsonb(metadata: Map<String, Any>): JSONB = JSONB.valueOf(writeValueAsString(metadata))

private fun JsonMapper.memo(r: MemoRecord): Memo =
    with(r) {
        Memo(
            id = id,
            reference = reference,
            typeId = memoTypeId,
            subject = subject,
            classificationId = classificationId,
            classificationRank = classificationRank,
            initiatorId = initiatorId,
            originUnitId = originUnitId,
            status = MemoStatus.valueOf(status),
            documentFileId = documentFileId,
            workflowInstanceId = workflowInstanceId,
            currentRound = currentRound!!,
            createdAt = createdAt.toInstant(),
            updatedAt = updatedAt.toInstant(),
            version = version!!,
            memoTypeVersionId = memoTypeVersionId,
            metadata = metadata?.let { readValue(it.data(), METADATA) } ?: emptyMap(),
        )
    }

private val METADATA = object : TypeReference<Map<String, Any>>() {}
