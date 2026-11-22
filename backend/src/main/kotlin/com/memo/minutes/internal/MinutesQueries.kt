package com.memo.minutes.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.MINUTES
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The minutes found across meetings (design E06 §9: the sweep, links to the next meeting, "My meetings"), inside a tenant transaction. */
@Repository
class MinutesQueries(
    private val tenantDsl: TenantDsl,
    private val store: MinutesStore,
) {
    /** The minutes of [meetingIds] that exist, by meeting ("My meetings"). */
    fun findAll(meetingIds: Collection<UUID>): Map<UUID, MinutesRow> =
        if (meetingIds.isEmpty()) {
            emptyMap()
        } else {
            tenantDsl
                .current()
                .selectFrom(MINUTES)
                .where(MINUTES.MEETING_ID.`in`(meetingIds))
                .fetch { store.rowOf(it) }
                .associateBy { it.meetingId }
        }

    /** [forumId]'s minutes in [states], through `minutes_forum_idx` (linking them to the committee's next meeting). */
    fun ofForum(
        forumId: UUID,
        states: Collection<MinutesStage>,
    ): List<MinutesRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES)
            .where(MINUTES.FORUM_ID.eq(forumId), MINUTES.STATE.`in`(states.map { it.name }))
            .orderBy(MINUTES.HELD_AT, MINUTES.MEETING_ID)
            .fetch { store.rowOf(it) }

    /** The minutes linked to a "Minutes of the previous meeting" item of [meetingId]. */
    fun linkedTo(meetingId: UUID): List<MinutesRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES)
            .where(MINUTES.LINKED_MEETING_ID.eq(meetingId))
            .orderBy(MINUTES.HELD_AT, MINUTES.MEETING_ID)
            .fetch { store.rowOf(it) }

    /** The minutes [decisionId] approved at the next meeting (AT_NEXT_MEETING). */
    fun approvedBy(decisionId: UUID): List<MinutesRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES)
            .where(MINUTES.APPROVING_DECISION_ID.eq(decisionId))
            .fetch { store.rowOf(it) }

    /** Minutes in [state] whose comment deadline is before [now], through `minutes_deadline_idx` (the sweep, at most [limit]). */
    fun deadlinePassed(
        state: MinutesStage,
        now: Instant,
        limit: Int,
    ): List<MinutesRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES)
            .where(MINUTES.STATE.eq(state.name), MINUTES.COMMENTS_UNTIL.lt(now.atOffset(ZoneOffset.UTC)))
            .orderBy(MINUTES.COMMENTS_UNTIL, MINUTES.MEETING_ID)
            .limit(limit)
            .fetch { store.rowOf(it) }

    /** Minutes in [states], oldest meeting first, at most [limit] (the sweep's reminders). */
    fun inStates(
        states: Collection<MinutesStage>,
        limit: Int,
    ): List<MinutesRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES)
            .where(MINUTES.STATE.`in`(states.map { it.name }))
            .orderBy(MINUTES.HELD_AT, MINUTES.MEETING_ID)
            .limit(limit)
            .fetch { store.rowOf(it) }
}
