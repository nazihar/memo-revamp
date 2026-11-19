package com.memo.meeting.internal.rsvp

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A person named in a reply: the seat's alternate, or the member an alternate stands in for. */
data class ReplyPersonResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val userId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
)

/** The viewer's own answer for a meeting, as a meeting and "My meetings" carry it (design E05 §3). */
data class MeetingRsvpResponse(
    /** Why the viewer answers this meeting: their seat, as a guest, or standing in for a member. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val capacity: ReplyCapacity,
    /** Null: no answer yet ("Please reply"). */
    val answer: RsvpAnswer?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val alternateAttends: Boolean,
    /** The answer was given for the meeting's old times ("Reply again: the time changed"). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val stale: Boolean,
    /** The meeting is scheduled: the answer can be given or changed now. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val canAnswer: Boolean,
)

/** "Will you attend?" for the viewer (design E05 §3, flow V): what they may answer and what they answered. */
data class MyRsvpResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val capacity: ReplyCapacity,
    /** The meeting is scheduled: the answer can be given or changed now. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val canAnswer: Boolean,
    /** The answers the meeting's mode offers, in the order to show them. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val offered: List<RsvpAnswer>,
    /** "Please reply by": the committee type's reply deadline; null when it has none. */
    val replyBy: LocalDate?,
    /** Null: no answer yet. */
    val answer: RsvpAnswer?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val alternateAttends: Boolean,
    /** The viewer's note to the secretaries. */
    val note: String?,
    /** IN_APP, or how the viewer told the secretary who recorded it. */
    val how: RsvpHow?,
    /** The secretary who recorded the answer on the viewer's behalf. */
    val recordedBy: ReplyPersonResponse?,
    val answeredAt: Instant?,
    /** The times the answer was given for. */
    val forStartsAt: Instant?,
    val forEndsAt: Instant?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val stale: Boolean,
    /** Send as `If-Match` when changing the answer; null before the first. */
    val version: Long?,
    /** A member's seat's alternate, who may attend in their place; null for guests, alternates and seats without one. */
    val alternate: ReplyPersonResponse?,
    /** The seat's alternate stands in for the viewer now (in the audience, invited). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val alternateStandingIn: Boolean,
    /** For an alternate: the member they attend in place of. */
    val standingInFor: ReplyPersonResponse?,
)

/** One person's reply, as the meeting's officers see it (design E05 §3, flow W). */
data class ReplyResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val userId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val capacity: ReplyCapacity,
    /** For an alternate: the member they attend in place of. */
    val standingInFor: ReplyPersonResponse?,
    /** For a member: the alternate standing in for them now. */
    val alternate: ReplyPersonResponse?,
    /** Null: no answer yet. */
    val answer: RsvpAnswer?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val alternateAttends: Boolean,
    val note: String?,
    val how: RsvpHow?,
    val howNote: String?,
    /** The secretary who recorded it, for an answer given by email, phone or in person. */
    val recordedBy: ReplyPersonResponse?,
    val answeredAt: Instant?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val stale: Boolean,
    /** Send as `If-Match` when recording a new answer for the person; null before the first. */
    val version: Long?,
)

/** How many answered what; a stale answer counts as [stale] only, not as a yes or apologies. */
data class ReplyCountsResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val inPerson: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val remote: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val apologies: Int,
    /** Answers given for the meeting's old times. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val stale: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val noAnswer: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val total: Int,
)

/**
 * A meeting's replies (design E05 §3, §4): counts for everyone who answers it ("5 of 7 members will attend"); the people
 * with their answers, notes and how, for the officers only.
 */
data class RepliesResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    /** Everyone who answers: members, alternates standing in and guests. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val counts: ReplyCountsResponse,
    /** The members' seats only. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val members: ReplyCountsResponse,
    /** Whether the viewer may record an answer on someone's behalf (a secretary or `forums.manage`, the meeting scheduled). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val canRecord: Boolean,
    /** Every reply, for the officers; null for everyone else. */
    val people: List<ReplyResponse>?,
)
