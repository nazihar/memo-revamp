package com.memo.minutes.internal

import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRole
import com.memo.forum.MinutesApproval
import com.memo.meeting.AttendanceCapacity
import com.memo.meeting.LineAccess
import com.memo.meeting.MeetingAttendance
import com.memo.meeting.MeetingView
import com.memo.meeting.MeetingViewers
import com.memo.meeting.Meetings
import com.memo.meeting.Recusals
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** The chair or vice-chair who reviews or confirms the minutes. */
data class MinutesOfficer(
    val userId: UUID,
    val role: MinutesRole,
)

/** Why someone reads the circulated minutes (design E06 §1.6.3). */
enum class ReaderKind { OFFICER, MEMBER, ALTERNATE, ASKED, LINKED }

/** A reader's redaction set (ADR-0066): the items they may not see in full, and how each is shown; [key] names the copy. */
data class RedactionSet(
    val redacted: List<Redaction>,
) {
    val key: String = keyOf(redacted)

    /** Both sets' items, the stricter way where both redact one (HIDDEN, then RESTRICTED, then RECUSED). */
    operator fun plus(other: RedactionSet): RedactionSet {
        val byItem = (redacted + other.redacted).groupBy { it.itemId }.mapValues { (_, r) -> r.maxBy { STRICTNESS.getValue(it.kind) } }
        return RedactionSet(byItem.values.sortedBy { it.itemId })
    }

    companion object {
        val FULL = RedactionSet(emptyList())
        private val STRICTNESS = mapOf(RedactionKind.RECUSED to 0, RedactionKind.RESTRICTED to 1, RedactionKind.HIDDEN to 2)

        /** SHA-256 of the sorted `itemId:KIND` lines: the same set always names the same copy. */
        fun keyOf(redacted: List<Redaction>): String {
            val canonical = redacted.sortedBy { it.itemId }.joinToString("\n") { "${it.itemId}:${it.kind.name}" }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
        }
    }
}

/**
 * Who is who to a meeting's minutes (design E06 §1.6.2–§1.6.4, §1.9), inside a tenant transaction:
 *  - **the reviewer:** the chair in office, or a vice-chair in office when there is no chair or the chair is recused from
 *    any minuted item (a vice-chair recused from one does not review either); none when neither is able;
 *  - **the confirmer:** the same rule on the approving meeting's date (AT_NEXT_MEETING) or today (BY_CIRCULATION);
 *  - **the readers** of circulated minutes: the meeting's roster members still in office, the chair, vice-chairs and
 *    secretaries in office, the alternates who attended, and the people asked for comments;
 *  - **a reader's redaction set** by ADR-0061's rule with E04's recusals, as [MeetingViewers.ofUser] sees them today.
 */
@Component
class MinutesPeople(
    private val directory: ForumDirectory,
    private val meetings: Meetings,
    private val viewers: MeetingViewers,
    private val recusals: Recusals,
    private val attendance: MeetingAttendance,
    private val comments: MinutesCommentStore,
) {
    /** Who reviews [row]'s draft now; null when no chair or vice-chair is able (409 `NO_REVIEWER`). */
    fun reviewer(row: MinutesRow): MinutesOfficer? = chairOrVice(row, directory.membersOn(row.forumId).map { it.userId to it.role })

    /** Who confirms [row] (design E06 §1.6.4); null when nobody is able. */
    fun confirmer(row: MinutesRow): MinutesOfficer? {
        val approving = row.approvingMeetingId?.takeIf { row.policy.approval == MinutesApproval.AT_NEXT_MEETING }?.let(meetings::find)
        val seats =
            approving?.let { directory.membersOn(row.forumId, it.date()) } ?: directory.membersOn(row.forumId)
        return chairOrVice(row, seats.map { it.userId to it.role })
    }

    /**
     * Who approves a correction to [row]'s approved minutes (design E06 §1.6.5, as built in T8): the confirmer's rule with
     * the committee's seats today (the chair in office, else a vice-chair when the chair is recused from any minuted item or
     * there is none), since a correction may come long after the approving meeting; null when nobody is able.
     */
    fun correctionApprover(row: MinutesRow): MinutesOfficer? =
        chairOrVice(row, directory.membersOn(row.forumId).map { it.userId to it.role })

    private fun chairOrVice(
        row: MinutesRow,
        seats: List<Pair<UUID, ForumRole>>,
    ): MinutesOfficer? {
        val recused = recusedFromAny(row.meetingId)
        val chair = seats.firstOrNull { it.second == ForumRole.CHAIR }?.first
        if (chair != null && chair !in recused) return MinutesOfficer(chair, MinutesRole.CHAIR)
        val vice =
            seats
                .filter { it.second == ForumRole.VICE_CHAIR }
                .map { it.first }
                .sorted()
                .firstOrNull { it !in recused }
        return vice?.let { MinutesOfficer(it, MinutesRole.VICE_CHAIR) }
    }

    /** Who is recused from each item of [meetingId] (an item with nobody recused is absent). */
    fun recusedOf(meetingId: UUID): Map<UUID, Set<UUID>> = recusals.ofMeeting(meetingId)

    /** Everyone recused from any item of [meetingId] (the whole minutes rule, ADR-0066). */
    fun recusedFromAny(meetingId: UUID): Set<UUID> =
        recusals
            .ofMeeting(meetingId)
            .values
            .flatten()
            .toSet()

    /** Who answered [meetingId]'s circulation (any comment). */
    fun answeredBy(meetingId: UUID): Set<UUID> = comments.answered(listOf(meetingId))[meetingId].orEmpty()

    /** The committee's secretaries in office today. */
    fun secretaries(forumId: UUID): Set<UUID> =
        directory
            .membersOn(forumId)
            .filter { it.role == ForumRole.SECRETARY }
            .map { it.userId }
            .toSet()

    /**
     * The readers of [row] once circulated (design E06 §1.6.3), with why each reads: officers in office (chair, vice-chairs,
     * secretaries), the roster's members of the meeting still in office (all members in office when nobody is on the
     * register), the alternates who attended, and the people asked for comments. Former members are not readers.
     */
    fun readers(row: MinutesRow): Map<UUID, ReaderKind> {
        val seats = directory.membersOn(row.forumId)
        val inOffice = seats.map { it.userId }.toSet()
        val register = attendance.of(row.meetingId)?.people.orEmpty()
        val roster = register.filter { it.capacity == AttendanceCapacity.MEMBER }.map { it.userId }.toSet()
        val members = if (register.isEmpty()) inOffice else roster.filter { it in inOffice }.toSet()
        val alternates =
            register
                .filter { it.capacity == AttendanceCapacity.ALTERNATE && it.firstArrivedAt != null }
                .map { it.userId }
                .toSet()
        val officers =
            seats.filter { it.role in OFFICER_ROLES }.map { it.userId }.toSet()
        val asked = comments.requests(row.meetingId).map { it.userId }.toSet()
        return buildMap {
            asked.forEach { put(it, ReaderKind.ASKED) }
            alternates.forEach { put(it, ReaderKind.ALTERNATE) }
            members.forEach { put(it, ReaderKind.MEMBER) }
            officers.forEach { put(it, ReaderKind.OFFICER) }
        }
    }

    /**
     * The redaction set of each of [readers] (with why they read) on [row]'s minutes (ADR-0066, design E06 §1.9): each
     * minuted item they do not see in full by the paper rule today is redacted, as their recused line when they are recused
     * from it and it is for them (heading, declaration, decision), as its heading when they see its title, else hidden.
     * Someone outside the meeting asked for comments sees the items named for them as a member would, every other item
     * hidden. Their views are read together (E06 T9: one by one, the minutes page and the copies job built a meeting view
     * for each of forty readers).
     */
    fun setsOf(
        row: MinutesRow,
        readers: Map<UUID, ReaderKind?>,
    ): Map<UUID, RedactionSet> {
        val asMember = readers.mapValues { (_, kind) -> kind == ReaderKind.ASKED || kind == ReaderKind.LINKED }
        val views = viewers.ofUsers(row.meetingId, asMember)
        val requests = if (readers.values.any { it == ReaderKind.ASKED }) comments.requests(row.meetingId) else emptyList()
        return readers.mapValues { (userId, kind) ->
            val named =
                requests
                    .filter { it.userId == userId }
                    .flatMap { it.itemIds }
                    .toSet()
                    .takeIf { kind == ReaderKind.ASKED }
            views[userId]?.let { setOf(it, named) } ?: RedactionSet.FULL
        }
    }

    /** The set [view]'s person gets; [named]: only these items may be shown in full (someone asked for comments). */
    fun setOf(
        view: MeetingView,
        named: Set<UUID>? = null,
    ): RedactionSet {
        val redacted =
            MinutesTemplateData.minuted(view.agenda.items).mapNotNull { item ->
                val full = view.seesItem(item.id) && (named == null || item.id in named)
                val forThem = item.restrictedTo?.contains(view.userId) != false
                val line = view.lines[item.id]
                when {
                    full -> {
                        null
                    }

                    item.id in view.recused && forThem -> {
                        Redaction(item.id, RedactionKind.RECUSED)
                    }

                    line == LineAccess.TITLE && !item.titleHidden && (named == null || item.id in named) -> {
                        Redaction(item.id, RedactionKind.RESTRICTED)
                    }

                    else -> {
                        Redaction(item.id, RedactionKind.HIDDEN)
                    }
                }
            }
        return RedactionSet(redacted.sortedBy { it.itemId })
    }

    private companion object {
        val OFFICER_ROLES = setOf(ForumRole.CHAIR, ForumRole.VICE_CHAIR, ForumRole.SECRETARY)
    }
}
