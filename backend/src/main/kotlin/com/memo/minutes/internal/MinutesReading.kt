package com.memo.minutes.internal

import com.memo.common.web.FieldProblem
import com.memo.common.web.RefusedException
import com.memo.meeting.MeetingMinutesSummaries
import com.memo.meeting.MeetingMinutesSummary
import com.memo.meeting.MeetingView
import com.memo.meeting.MeetingViewers
import org.springframework.stereotype.Component
import java.util.UUID

/** How the actor reads a meeting's minutes now (design E06 §1.9); null: not at all. */
enum class MinutesAccessKind {
    /** A secretary of the committee or `forums.manage`: the page in full, their own copy when recused. */
    MANAGER,

    /** The chair or vice-chair reviewing the draft. */
    REVIEWER,

    /** A reader of the circulated or approved minutes (their [ReaderKind] says why). */
    READER,

    /** The next meeting's audience, through its "Minutes of the previous meeting" item (AT_NEXT_MEETING). */
    LINKED,
}

/** The actor and the minutes as they read them. */
data class MinutesReader(
    val view: MeetingView,
    val row: MinutesRow,
    val access: MinutesAccessKind,
    /** Why they read (for a reader), as the copies count them. */
    val kind: ReaderKind?,
) {
    val userId: UUID get() = view.userId
}

/**
 * Who reads a meeting's minutes and through which copy (ADR-0066, design E06 §1.9), inside a tenant transaction: the
 * committee's secretaries and `forums.manage` always; the reviewer while the draft is with them; from the circulation on,
 * the readers of §1.6.3, the confirmer, and (AT_NEXT_MEETING) whoever sees the next meeting's "Minutes of the previous
 * meeting" item in full; nobody else (404). Each reads the copy of their redaction set; an officer previews another
 * reader's copy with their own recusals still applying.
 */
@Component
class MinutesReading(
    private val store: MinutesStore,
    private val queries: MinutesQueries,
    private val people: MinutesPeople,
    private val viewers: MeetingViewers,
    private val copies: MinutesCopies,
) : MeetingMinutesSummaries {
    /** The actor on [meetingId]'s minutes; 404 when they do not read them now (or there are none). */
    fun reader(meetingId: UUID): MinutesReader {
        val view = actorView(meetingId)
        val row = store.find(meetingId) ?: throw MinutesNotFoundException("minutes")
        return readerOf(view, row) ?: throw MinutesNotFoundException("minutes")
    }

    /**
     * The actor on [meetingId]: as the meeting module sees them when they see the meeting; else, for a reader of its minutes
     * from outside its audience (someone asked for comments, the next meeting's audience), as the paper rule sees them
     * today. 404 for anyone else.
     */
    fun actorView(meetingId: UUID): MeetingView {
        viewers.find(meetingId)?.let { return it }
        val user = actorOrNull()
        val row = store.find(meetingId)
        val view = if (user != null && row != null) viewers.ofUser(meetingId, user) else null
        return view?.takeIf { readerOf(it, checkNotNull(row)) != null } ?: throw MinutesNotFoundException("minutes")
    }

    /** [view]'s person on [row], or null when they do not read the minutes now. */
    fun readerOf(
        view: MeetingView,
        row: MinutesRow,
        readers: Map<UUID, ReaderKind> = people.readers(row),
    ): MinutesReader? {
        val reads = row.state in READ_STAGES
        val access: Pair<MinutesAccessKind, ReaderKind?>? =
            when {
                view.manages -> MinutesAccessKind.MANAGER to readers[view.userId]
                row.state == MinutesStage.CHAIR_REVIEW && row.reviewerId == view.userId -> MinutesAccessKind.REVIEWER to ReaderKind.OFFICER
                !reads -> null
                readers.containsKey(view.userId) -> MinutesAccessKind.READER to readers[view.userId]
                people.confirmer(row)?.userId == view.userId -> MinutesAccessKind.READER to ReaderKind.OFFICER
                linkedReader(row) -> MinutesAccessKind.LINKED to ReaderKind.LINKED
                else -> null
            }
        return access?.let { (kind, why) -> MinutesReader(view, row, kind, why) }
    }

    /** The actor sees, in full, the next meeting's "Minutes of the previous meeting" item [row] is linked to. */
    private fun linkedReader(row: MinutesRow): Boolean {
        val linked = row.linkedMeetingId?.let(viewers::find)
        return linked != null && row.linkedItemId?.let(linked::seesItem) == true
    }

    /** The redaction set of the actor's own copy. */
    fun ownSet(reader: MinutesReader): RedactionSet =
        when (reader.kind) {
            ReaderKind.ASKED, ReaderKind.LINKED -> people.setOf(reader.row, reader.userId, reader.kind)
            else -> people.setOf(reader.view)
        }

    /**
     * The actor's copy of the draft (design E06 §3 `GET …/minutes/document`): while the minutes are with the chair,
     * circulated or waiting for approval; the approved minutes' copies are the record's (T7: 409 `RECORD_PENDING` until then).
     */
    fun document(meetingId: UUID): Pair<MinutesReader, CopyLookup> {
        val reader = reader(meetingId)
        requireCopying(reader.row)
        return reader to copies.lookup(reader.row, ownSet(reader))
    }

    /**
     * The copy [userId] would get (design E06 §3 `GET …/minutes/preview?as=`): for the committee's secretaries and
     * `forums.manage` (403 otherwise), the officer's own recusals still applying (their items stay redacted as their set
     * has them). 422 `as` for someone who does not read these minutes.
     */
    fun preview(
        meetingId: UUID,
        userId: UUID,
    ): Pair<MinutesReader, CopyLookup> {
        val reader = reader(meetingId)
        if (reader.access != MinutesAccessKind.MANAGER) {
            throw com.memo.common.authz
                .ForbiddenException(null)
        }
        requireCopying(reader.row)
        val kinds = people.readers(reader.row) + listOfNotNull(reader.row.reviewerId).associateWith { ReaderKind.OFFICER }
        val kind = kinds[userId]
        requireNoProblems(
            listOfNotNull(FieldProblem("as", "NOT_A_READER", "Choose someone who reads these minutes.").takeIf { kind == null }),
        )
        val set = people.setOf(reader.row, userId, kind) + people.setOf(reader.view)
        return reader to copies.lookup(reader.row, set)
    }

    /** "My meetings" (design E06 §3): each meeting's minutes and what the actor has to do, for the meetings they read. */
    override fun of(meetingIds: Collection<UUID>): Map<UUID, MeetingMinutesSummary> {
        val rows = queries.findAll(meetingIds).values.filter { it.state != MinutesStage.DRAFT }
        if (rows.isEmpty()) return emptyMap()
        return rows
            .mapNotNull { row ->
                val view = viewers.find(row.meetingId) ?: return@mapNotNull null
                val readers = people.readers(row)
                val reader = readerOf(view, row, readers) ?: return@mapNotNull null
                row.meetingId to MeetingMinutesSummary(row.state.name, action(reader, readers))
            }.toMap()
    }

    /** What [reader] has to do: REVIEW, CONFIRM, COMMENT (not answered yet), READ; null for a secretary while drafting. */
    fun action(
        reader: MinutesReader,
        readers: Map<UUID, ReaderKind> = people.readers(reader.row),
    ): String? {
        val row = reader.row
        val secretary = reader.userId in people.secretaries(row.forumId)
        return when {
            row.state == MinutesStage.CHAIR_REVIEW && row.reviewerId == reader.userId -> {
                "REVIEW"
            }

            row.state == MinutesStage.AWAITING_CHAIR && people.confirmer(row)?.userId == reader.userId -> {
                "CONFIRM"
            }

            row.state == MinutesStage.CIRCULATED && !secretary && readers.containsKey(reader.userId) && !answered(row, reader.userId) -> {
                "COMMENT"
            }

            row.state in READ_STAGES -> {
                "READ"
            }

            else -> {
                null
            }
        }
    }

    private fun answered(
        row: MinutesRow,
        userId: UUID,
    ): Boolean = people.answeredBy(row.meetingId).contains(userId)

    private fun requireCopying(row: MinutesRow) {
        if (row.state == MinutesStage.APPROVED) {
            throw RefusedException("RECORD_PENDING", "The approved minutes are being kept as the record; their copies come with it.")
        }
        if (row.state !in MinutesCopies.COPYING) throw MinutesNotFoundException("minutes")
    }

    companion object {
        /** The stages whose minutes their readers read. */
        val READ_STAGES = setOf(MinutesStage.CIRCULATED, MinutesStage.AWAITING_CHAIR, MinutesStage.APPROVED)
    }
}
