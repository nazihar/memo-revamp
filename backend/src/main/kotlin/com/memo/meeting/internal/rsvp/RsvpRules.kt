package com.memo.meeting.internal.rsvp

import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.forum.ForumSeat
import com.memo.meeting.MeetingMode
import com.memo.meeting.MeetingStatus
import com.memo.meeting.internal.AudienceRow
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** "Will you attend?" (design E05 §1.4): in person, online, or apologies. */
enum class RsvpAnswer {
    IN_PERSON,
    REMOTE,
    APOLOGIES,
    ;

    /** A "yes". */
    val attending: Boolean get() = this != APOLOGIES
}

/** How an answer reached memo: IN_APP from the person, or how they told the secretary who recorded it. */
enum class RsvpHow {
    IN_APP,
    EMAIL,
    PHONE,
    IN_PERSON,
    OTHER,
}

/** Why a person answers a meeting's invitation (design E05 §1.4): a seat, a guest, or an alternate standing in. */
enum class ReplyCapacity { MEMBER, ALTERNATE, GUEST }

/**
 * The pure rules of replies (ADR-0063, design E05 §1.4): the answers each meeting mode offers, the window, staleness, and
 * the lengths of what people write. The table's CHECKs repeat the shapes.
 */
object RsvpRules {
    const val MAX_NOTE = 300
    const val MAX_HOW_NOTE = 200

    /** The answers [mode] offers: in person for in-person and hybrid meetings, online for online and hybrid ones. */
    fun offered(mode: MeetingMode): List<RsvpAnswer> =
        when (mode) {
            MeetingMode.IN_PERSON -> listOf(RsvpAnswer.IN_PERSON, RsvpAnswer.APOLOGIES)
            MeetingMode.ONLINE -> listOf(RsvpAnswer.REMOTE, RsvpAnswer.APOLOGIES)
            MeetingMode.HYBRID -> listOf(RsvpAnswer.IN_PERSON, RsvpAnswer.REMOTE, RsvpAnswer.APOLOGIES)
        }

    /** Answers are given while the meeting is scheduled; from its start attendance takes over (409 `WRONG_STATE`). */
    fun window(status: MeetingStatus) {
        refuseIf(status != MeetingStatus.SCHEDULED, "WRONG_STATE") {
            if (status == MeetingStatus.DRAFT) {
                "The meeting is not scheduled yet: answers open when it is."
            } else {
                "Answers are closed: the meeting is no longer scheduled."
            }
        }
    }

    /** An answer the meeting's [mode] offers (422 `MODE_NOT_OFFERED`). */
    fun answer(
        answer: RsvpAnswer,
        mode: MeetingMode,
    ): FieldProblem? =
        FieldProblem("answer", "MODE_NOT_OFFERED", "This meeting is ${mode.name.lowercase().replace('_', ' ')}: choose another answer.")
            .takeIf { answer !in offered(mode) }

    /** The alternate attends only with apologies (422 `ALTERNATE_WITH_APOLOGIES`). */
    fun alternate(
        answer: RsvpAnswer,
        alternateAttends: Boolean,
    ): FieldProblem? =
        FieldProblem("alternateAttends", "ALTERNATE_WITH_APOLOGIES", "An alternate attends only in place of a member who sends apologies.")
            .takeIf { alternateAttends && answer != RsvpAnswer.APOLOGIES }

    /** How an answer recorded for someone was given: required, and not IN_APP (422 `HOW_REQUIRED`). */
    fun how(how: RsvpHow?): FieldProblem? =
        FieldProblem("how", "HOW_REQUIRED", "Say how they told you: by email, by phone, in person or otherwise.")
            .takeIf { how == null || how == RsvpHow.IN_APP }

    /** An optional text of at most [max] characters at [field], trimmed (422 `LENGTH`). */
    fun optional(
        field: String,
        text: String?,
        max: Int,
    ): Pair<String?, FieldProblem?> {
        val clean = text?.trim()?.ifEmpty { null }
        return clean to FieldProblem(field, "LENGTH", "At most $max characters.").takeIf { (clean?.length ?: 0) > max }
    }

    /** An answer given for other times than the meeting's now ([startsAt]–[endsAt]): "You replied for the old time". */
    fun stale(
        forStartsAt: Instant,
        forEndsAt: Instant,
        startsAt: Instant,
        endsAt: Instant,
    ): Boolean = forStartsAt != startsAt || forEndsAt != endsAt

    /** Why the person of [rows] (their current audience rows) answers the meeting; null: they do not. */
    fun capacity(rows: Collection<AudienceRow>): ReplyCapacity? {
        val sources = rows.map { it.source.name }.toSet()
        return ReplyCapacity.entries.firstOrNull { it.name in sources }
    }

    /** The SHA-256 of a note, hex: the history and the audit carry it instead of the text (design E05 §5). */
    fun sha256(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * Who may stand in (ADR-0064, design E05 §1.5): the alternate of the member's seat on the meeting's roster. Refused with
 * 409 `ALTERNATE_NONE` when no seat of the member has an alternate who can attend ([available]: active and cleared for the
 * meeting), `ALTERNATE_IS_MEMBER` when the alternate holds a seat on the committee on the meeting's date, and
 * `ALTERNATE_BUSY` when they already stand in for someone else at this meeting.
 */
object AlternateRules {
    /** The seat of [member] on [roster] whose alternate stands in ([standing]: the meeting's current ALTERNATE rows). */
    fun seat(
        member: UUID,
        roster: Collection<ForumSeat>,
        standing: Collection<AudienceRow>,
        available: (UUID) -> Boolean,
    ): ForumSeat {
        val seat = roster.firstOrNull { it.userId == member && it.alternateUserId != null }
        val alternate = seat?.alternateUserId
        refuseIf(seat == null || alternate == null || !available(alternate), "ALTERNATE_NONE") {
            "Your seat has no alternate who can attend this meeting."
        }
        refuseIf(roster.any { it.userId == alternate }, "ALTERNATE_IS_MEMBER") {
            "Your alternate is also a member of this committee and cannot attend in your place."
        }
        refuseIf(standing.any { it.userId == alternate && it.forUserId != member }, "ALTERNATE_BUSY") {
            "Your alternate is already attending in place of another member."
        }
        return checkNotNull(seat)
    }

    /**
     * Whether an ALTERNATE [row] still holds on [roster]: its member still holds the seat on the meeting's date, the seat's
     * alternate is still the person, and the person holds no seat themselves.
     */
    fun holds(
        row: AudienceRow,
        roster: Collection<ForumSeat>,
    ): Boolean =
        roster.any { it.memberId == row.forMemberId && it.userId == row.forUserId && it.alternateUserId == row.userId } &&
            roster.none { it.userId == row.userId }
}
