package com.memo.meeting.internal.quorum

import com.memo.common.tenant.TenantDsl
import com.memo.forum.QuorumResult
import com.memo.jooq.tables.records.MeetingQuorumSnapshotRecord
import com.memo.jooq.tables.references.MEETING_QUORUM_SNAPSHOT
import com.memo.meeting.QuorumSnapshotInputs
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** A stored quorum snapshot (design E05 §1.7). */
data class SnapshotRow(
    val id: UUID,
    val meetingId: UUID,
    val itemId: UUID?,
    val at: Instant,
    val inputs: QuorumSnapshotInputs,
    val result: QuorumResult,
    /** Set when the decision was recorded without a quorum (by [takenBy]), to be ratified. */
    val overrideReason: String?,
    val inputsSha256: String,
    val takenBy: UUID?,
    val takenAt: Instant,
)

/** The quorum snapshots (append-only; the app role has no UPDATE or DELETE), inside a tenant transaction. */
@Repository
class SnapshotStore(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
) {
    fun insert(row: SnapshotRow) {
        val s = MEETING_QUORUM_SNAPSHOT
        tenantDsl
            .current()
            .insertInto(s)
            .set(s.ID, row.id)
            .set(s.TENANT_ID, tenantDsl.tenant().value)
            .set(s.MEETING_ID, row.meetingId)
            .set(s.ITEM_ID, row.itemId)
            .set(s.AT, row.at.atOffset(ZoneOffset.UTC))
            .set(s.TYPE_ID, row.inputs.typeId)
            .set(s.TYPE_VERSION, row.inputs.typeVersion)
            .set(s.INPUTS, JSONB.jsonb(json.writeValueAsString(row.inputs)))
            .set(s.RESULT, JSONB.jsonb(json.writeValueAsString(row.result)))
            .set(s.QUORATE, row.result.quorate)
            .set(s.OVERRIDE_REASON, row.overrideReason)
            .set(s.RATIFICATION_REQUIRED, row.overrideReason != null)
            .set(s.INPUTS_SHA256, row.inputsSha256)
            .set(s.TAKEN_BY, row.takenBy)
            .set(s.TAKEN_AT, row.takenAt.atOffset(ZoneOffset.UTC))
            .execute()
    }

    fun find(id: UUID): SnapshotRow? =
        tenantDsl
            .current()
            .selectFrom(MEETING_QUORUM_SNAPSHOT)
            .where(MEETING_QUORUM_SNAPSHOT.ID.eq(id))
            .fetchOne { it.row() }

    /** [meetingId]'s snapshots, oldest first. */
    fun ofMeeting(meetingId: UUID): List<SnapshotRow> =
        tenantDsl
            .current()
            .selectFrom(MEETING_QUORUM_SNAPSHOT)
            .where(MEETING_QUORUM_SNAPSHOT.MEETING_ID.eq(meetingId))
            .orderBy(MEETING_QUORUM_SNAPSHOT.AT, MEETING_QUORUM_SNAPSHOT.ID)
            .fetch { it.row() }

    private fun MeetingQuorumSnapshotRecord.row() =
        SnapshotRow(
            id = id,
            meetingId = meetingId,
            itemId = itemId,
            at = at.toInstant(),
            inputs = json.readValue(inputs.data(), QuorumSnapshotInputs::class.java),
            result = json.readValue(result.data(), QuorumResult::class.java),
            overrideReason = overrideReason,
            inputsSha256 = inputsSha256,
            takenBy = takenBy,
            takenAt = takenAt.toInstant(),
        )
}
