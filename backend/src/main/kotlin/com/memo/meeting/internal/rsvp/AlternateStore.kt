package com.memo.meeting.internal.rsvp

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.MEETING_AUDIENCE
import com.memo.meeting.internal.AudienceSource
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The ALTERNATE rows of meeting audiences (design E05 §1.5), inside a tenant transaction: an alternate standing in, and
 * released. Read with the rest of the audience through `AudienceStore`; nothing is deleted.
 */
@Repository
class AlternateStore(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
) {
    /**
     * Makes [userId] the alternate standing in for [forUserId]'s seat [forMemberId] in [meetingId] (design E05 §1.5): a
     * removed row of theirs comes back for the new seat. False when they already stand in at the meeting.
     */
    @Suppress("LongParameterList")
    fun standIn(
        meetingId: UUID,
        userId: UUID,
        forMemberId: UUID,
        forUserId: UUID,
        membersOnly: Boolean,
        until: LocalDate?,
    ): Boolean =
        tenantDsl
            .current()
            .insertInto(MEETING_AUDIENCE)
            .set(MEETING_AUDIENCE.TENANT_ID, tenantDsl.tenant().value)
            .set(MEETING_AUDIENCE.MEETING_ID, meetingId)
            .set(MEETING_AUDIENCE.USER_ID, userId)
            .set(MEETING_AUDIENCE.SOURCE, AudienceSource.ALTERNATE.name)
            .set(MEETING_AUDIENCE.MEMBERS_ONLY, membersOnly)
            .set(MEETING_AUDIENCE.UNTIL, until)
            .set(MEETING_AUDIENCE.FOR_MEMBER_ID, forMemberId)
            .set(MEETING_AUDIENCE.FOR_USER_ID, forUserId)
            .onConflict(MEETING_AUDIENCE.TENANT_ID, MEETING_AUDIENCE.MEETING_ID, MEETING_AUDIENCE.USER_ID, MEETING_AUDIENCE.SOURCE)
            .doUpdate()
            .set(MEETING_AUDIENCE.MEMBERS_ONLY, membersOnly)
            .set(MEETING_AUDIENCE.UNTIL, until)
            .set(MEETING_AUDIENCE.FOR_MEMBER_ID, forMemberId)
            .set(MEETING_AUDIENCE.FOR_USER_ID, forUserId)
            .setNull(MEETING_AUDIENCE.REMOVED_AT)
            .where(MEETING_AUDIENCE.REMOVED_AT.isNotNull)
            .execute() == 1

    /** Records that [userId] no longer stands in at [meetingId]; false when they did not. */
    fun release(
        meetingId: UUID,
        userId: UUID,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEETING_AUDIENCE)
            .set(MEETING_AUDIENCE.REMOVED_AT, now())
            .where(
                MEETING_AUDIENCE.MEETING_ID.eq(meetingId),
                MEETING_AUDIENCE.USER_ID.eq(userId),
                MEETING_AUDIENCE.SOURCE.eq(AudienceSource.ALTERNATE.name),
                MEETING_AUDIENCE.REMOVED_AT.isNull,
            ).execute() == 1

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
}
