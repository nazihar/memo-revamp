package com.memo.minutes.internal

import com.memo.common.tenant.TenantDsl
import com.memo.forum.MinutesPolicy
import com.memo.jooq.tables.records.MinutesRecord
import com.memo.jooq.tables.references.MINUTES
import com.memo.jooq.tables.references.MINUTES_EVENT
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** What the draft job was asked to do last (design E06 §1.6.1): the whole draft, or one item filled again or restored. */
enum class DraftWork { ALL, ITEM }

/** Where that request stands: running, or given up with a code. Done requests leave no state. */
enum class WorkState { PENDING, FAILED }

/** What happened to the minutes (design E06 §1.5), as `minutes_event` keeps it. */
enum class MinutesEventKind {
    GENERATED,
    ITEM_REGENERATED,
    ITEM_RESTORED,
    RETURNED_TO_DRAFT,
}

/** A meeting's minutes (design E06 §1.5), as the module reads and writes them. */
data class MinutesRow(
    val meetingId: UUID,
    val forumId: UUID,
    /** The committee type's minutes policy as pinned on the meeting when it was held. */
    val policy: MinutesPolicy,
    val state: MinutesStage,
    /** The draft (purpose `minutes.draft`), once its first version is stored. */
    val fileId: UUID?,
    val workSeq: Int,
    val workKind: DraftWork?,
    val workItemId: UUID?,
    val workState: WorkState?,
    val workError: String?,
    val workRequestedBy: UUID?,
    val workRequestedAt: Instant?,
    /** The draft's version the structure check last read, with what it found. */
    val checkedVersionId: UUID?,
    val structureOk: Boolean?,
    val structureProblems: List<StructureProblem>,
    val trackedChanges: Int,
    val comments: Int,
    val checkedAt: Instant?,
    val heldAt: Instant,
    val createdAt: Instant,
    val version: Long,
)

/** One line of the minutes' history; [note] is free text, never audited. */
data class MinutesEventRow(
    val id: UUID,
    val meetingId: UUID,
    val kind: MinutesEventKind,
    val itemId: UUID?,
    val actor: UUID?,
    val fileVersionId: UUID?,
    val sha256: String?,
    val at: Instant,
    val note: String? = null,
)

/**
 * The minutes and their history (design E06 §1.5, §6), inside a tenant transaction. The row changes only by a guarded
 * update on its version (the trigger repeats the rules: the meeting, policy and file never change once set); the history
 * is append-only. Lock order (design E06 §1.4 as built): after the decision, submission and meeting rows.
 */
@Repository
class MinutesStore(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
) {
    fun find(meetingId: UUID): MinutesRow? =
        tenantDsl
            .current()
            .selectFrom(MINUTES)
            .where(MINUTES.MEETING_ID.eq(meetingId))
            .fetchOne { it.row() }

    /** [meetingId]'s minutes, locked to the end of the transaction; null when there are none. */
    fun lock(meetingId: UUID): MinutesRow? =
        tenantDsl
            .current()
            .selectFrom(MINUTES)
            .where(MINUTES.MEETING_ID.eq(meetingId))
            .forUpdate()
            .fetchOne { it.row() }

    /** Creates [row]; false when the meeting has minutes already. */
    fun insert(row: MinutesRow): Boolean {
        val m = MINUTES
        return tenantDsl
            .current()
            .insertInto(m)
            .set(m.TENANT_ID, tenantDsl.tenant().value)
            .set(m.MEETING_ID, row.meetingId)
            .set(m.FORUM_ID, row.forumId)
            .set(m.POLICY, JSONB.jsonb(json.writeValueAsString(row.policy)))
            .set(m.STATE, row.state.name)
            .set(m.HELD_AT, row.heldAt.atOffset(ZoneOffset.UTC))
            .set(m.CREATED_AT, row.createdAt.atOffset(ZoneOffset.UTC))
            .set(m.VERSION, 0L)
            .onConflictDoNothing()
            .execute() == 1
    }

    /** Writes [after] over the minutes as seen at [before]'s version; false when they moved on in between. */
    fun update(
        before: MinutesRow,
        after: MinutesRow,
    ): Boolean {
        val m = MINUTES
        return tenantDsl
            .current()
            .update(m)
            .set(m.STATE, after.state.name)
            .set(m.FILE_ID, after.fileId)
            .set(m.WORK_SEQ, after.workSeq)
            .set(m.WORK_KIND, after.workKind?.name)
            .set(m.WORK_ITEM_ID, after.workItemId)
            .set(m.WORK_STATE, after.workState?.name)
            .set(m.WORK_ERROR, after.workError)
            .set(m.WORK_REQUESTED_BY, after.workRequestedBy)
            .set(m.WORK_REQUESTED_AT, after.workRequestedAt?.atOffset(ZoneOffset.UTC))
            .set(m.CHECKED_VERSION_ID, after.checkedVersionId)
            .set(m.STRUCTURE_OK, after.structureOk)
            .set(m.STRUCTURE_PROBLEMS, JSONB.jsonb(json.writeValueAsString(after.structureProblems)))
            .set(m.TRACKED_CHANGES, after.trackedChanges)
            .set(m.COMMENTS, after.comments)
            .set(m.CHECKED_AT, after.checkedAt?.atOffset(ZoneOffset.UTC))
            .set(m.VERSION, before.version + 1)
            .where(m.MEETING_ID.eq(before.meetingId), m.VERSION.eq(before.version))
            .execute() == 1
    }

    fun insertEvent(event: MinutesEventRow) {
        val e = MINUTES_EVENT
        tenantDsl
            .current()
            .insertInto(e)
            .set(e.ID, event.id)
            .set(e.TENANT_ID, tenantDsl.tenant().value)
            .set(e.MEETING_ID, event.meetingId)
            .set(e.KIND, event.kind.name)
            .set(e.ITEM_ID, event.itemId)
            .set(e.ACTOR, event.actor)
            .set(e.NOTE, event.note)
            .set(e.FILE_VERSION_ID, event.fileVersionId)
            .set(e.SHA256, event.sha256)
            .set(e.AT, event.at.atOffset(ZoneOffset.UTC))
            .execute()
    }

    /** [meetingId]'s history, oldest first. */
    fun events(meetingId: UUID): List<MinutesEventRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES_EVENT)
            .where(MINUTES_EVENT.MEETING_ID.eq(meetingId))
            .orderBy(MINUTES_EVENT.AT, MINUTES_EVENT.ID)
            .fetch {
                MinutesEventRow(
                    it.id,
                    it.meetingId,
                    MinutesEventKind.valueOf(it.kind),
                    it.itemId,
                    it.actor,
                    it.fileVersionId,
                    it.sha256,
                    it.at.toInstant(),
                    it.note,
                )
            }

    private fun MinutesRecord.row() =
        MinutesRow(
            meetingId = meetingId,
            forumId = forumId,
            policy = json.readValue(policy.data(), MinutesPolicy::class.java),
            state = MinutesStage.valueOf(state ?: MinutesStage.DRAFT.name),
            fileId = fileId,
            workSeq = workSeq ?: 0,
            workKind = workKind?.let(DraftWork::valueOf),
            workItemId = workItemId,
            workState = workState?.let(WorkState::valueOf),
            workError = workError,
            workRequestedBy = workRequestedBy,
            workRequestedAt = workRequestedAt?.toInstant(),
            checkedVersionId = checkedVersionId,
            structureOk = structureOk,
            structureProblems = structureProblems?.let { json.readValue(it.data(), PROBLEMS) }.orEmpty(),
            trackedChanges = trackedChanges ?: 0,
            comments = comments ?: 0,
            checkedAt = checkedAt?.toInstant(),
            heldAt = heldAt.toInstant(),
            createdAt = createdAt.toInstant(),
            version = version ?: 0,
        )

    private companion object {
        val PROBLEMS = object : TypeReference<List<StructureProblem>>() {}
    }
}
