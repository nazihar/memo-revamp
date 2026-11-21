package com.memo.meeting.internal.attendance

import com.memo.common.tenant.TenantDsl
import com.memo.forum.UnmetCondition
import com.memo.jooq.tables.references.MEETING_ITEM_PROGRESS
import com.memo.jooq.tables.references.MEETING_QUORUM_CHANGE
import com.memo.meeting.QuorumChangeKind
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** The item in progress and the quorum's change log of meetings (design E05 §1.6.1, §1.7), append-only, in a tenant transaction. */
@Repository
class MeetingProgressStore(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
    private val json: JsonMapper,
) {
    /** The items in progress of [meetingId], in the order they started. */
    fun progress(meetingId: UUID): List<ItemProgressRow> =
        tenantDsl
            .current()
            .selectFrom(MEETING_ITEM_PROGRESS)
            .where(MEETING_ITEM_PROGRESS.MEETING_ID.eq(meetingId))
            .orderBy(MEETING_ITEM_PROGRESS.STARTED_AT, MEETING_ITEM_PROGRESS.ID)
            .fetch { ItemProgressRow(it.id, it.meetingId, it.itemId, it.startedAt.toInstant(), it.recordedBy) }

    fun insert(progress: ItemProgressRow) {
        tenantDsl
            .current()
            .insertInto(MEETING_ITEM_PROGRESS)
            .set(MEETING_ITEM_PROGRESS.ID, progress.id)
            .set(MEETING_ITEM_PROGRESS.TENANT_ID, tenantDsl.tenant().value)
            .set(MEETING_ITEM_PROGRESS.MEETING_ID, progress.meetingId)
            .set(MEETING_ITEM_PROGRESS.ITEM_ID, progress.itemId)
            .set(MEETING_ITEM_PROGRESS.STARTED_AT, utc(progress.startedAt))
            .set(MEETING_ITEM_PROGRESS.RECORDED_BY, progress.recordedBy)
            .set(MEETING_ITEM_PROGRESS.RECORDED_AT, now())
            .execute()
    }

    /** The quorum's changes of [meetingId] at [level] (null: both), in the order they were recorded. */
    fun changes(
        meetingId: UUID,
        level: QuorumLevel? = QuorumLevel.MEETING,
    ): List<QuorumChangeRow> =
        tenantDsl
            .current()
            .selectFrom(MEETING_QUORUM_CHANGE)
            .where(
                MEETING_QUORUM_CHANGE.MEETING_ID.eq(meetingId),
                if (level == null) DSL.noCondition() else MEETING_QUORUM_CHANGE.LEVEL.eq(level.name),
            ).orderBy(MEETING_QUORUM_CHANGE.RECORDED_AT, MEETING_QUORUM_CHANGE.ID)
            .fetch { r ->
                QuorumChangeRow(
                    id = r.id,
                    meetingId = r.meetingId,
                    level = QuorumLevel.valueOf(r.level),
                    itemId = r.itemId,
                    kind = QuorumChangeKind.valueOf(r.kind),
                    needed = r.needed,
                    present = r.present,
                    unmet = json.readValue(r.unmet.data(), UNMET),
                    at = r.at.toInstant(),
                    causeEventId = r.causeEventId,
                    recordedAt = r.recordedAt.toInstant(),
                )
            }

    fun insert(change: QuorumChangeRow) {
        tenantDsl
            .current()
            .insertInto(MEETING_QUORUM_CHANGE)
            .set(MEETING_QUORUM_CHANGE.ID, change.id)
            .set(MEETING_QUORUM_CHANGE.TENANT_ID, tenantDsl.tenant().value)
            .set(MEETING_QUORUM_CHANGE.MEETING_ID, change.meetingId)
            .set(MEETING_QUORUM_CHANGE.LEVEL, change.level.name)
            .set(MEETING_QUORUM_CHANGE.ITEM_ID, change.itemId)
            .set(MEETING_QUORUM_CHANGE.KIND, change.kind.name)
            .set(MEETING_QUORUM_CHANGE.NEEDED, change.needed)
            .set(MEETING_QUORUM_CHANGE.PRESENT, change.present)
            .set(MEETING_QUORUM_CHANGE.UNMET, JSONB.jsonb(json.writeValueAsString(change.unmet)))
            .set(MEETING_QUORUM_CHANGE.AT, utc(change.at))
            .set(MEETING_QUORUM_CHANGE.CAUSE_EVENT_ID, change.causeEventId)
            .set(MEETING_QUORUM_CHANGE.RECORDED_AT, utc(change.recordedAt))
            .execute()
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private companion object {
        val UNMET = object : TypeReference<List<UnmetCondition>>() {}
    }
}
