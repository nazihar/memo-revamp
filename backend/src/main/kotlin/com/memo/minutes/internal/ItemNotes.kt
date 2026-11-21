package com.memo.minutes.internal

import com.memo.common.authz.ForbiddenException
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.meeting.AgendaItemRef
import com.memo.meeting.MeetingStatus
import com.memo.meeting.MeetingView
import com.memo.meeting.MeetingViewers
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The secretary's notes per item (design E06 §1.2): saved from the decision screen while the meeting is in progress or
 * held and the minutes are in the draft, as a new version only when the text changed (the screen autosaves). Read and
 * written by the committee's secretaries and `forums.manage` holders not recused from the item; to everyone else the
 * notes do not exist (404), and a recused secretary's write is 403 `RECUSED`.
 */
@Service
class ItemNotes(
    private val viewers: MeetingViewers,
    private val store: NoteStore,
    private val stages: MinutesStages,
    private val effects: DecisionEffects,
    private val clock: Clock,
) {
    /** The latest version of the notes on [itemId]; null when none was saved. */
    fun read(
        meetingId: UUID,
        itemId: UUID,
    ): NoteRow? {
        val (view, item) = scene(meetingId, itemId)
        if (item.id in view.recused || !view.seesItem(item.id)) throw MinutesNotFoundException("notes")
        return store.latest(item.id)
    }

    /** Saves [text] as the version after [seq] (If-Match: the latest version the client read, 0 for none). */
    fun save(
        meetingId: UUID,
        itemId: UUID,
        seq: Int,
        text: String?,
    ): NoteRow {
        val (view, item) = scene(meetingId, itemId)
        requireKeeper(view, item)
        window(view)
        val clean = text.orEmpty()
        requireNoProblems(
            listOfNotNull(FieldProblem("text", "LENGTH", "At most $MAX_TEXT characters.").takeIf { clean.length > MAX_TEXT }),
        )
        val latest = store.latest(item.id)
        val stale = (latest?.seq ?: 0) != seq
        if (!stale && latest != null && latest.text == clean) return latest
        val row = NoteRow(meetingId, item.id, seq + 1, clean, view.userId, clock.instant().truncatedTo(ChronoUnit.MICROS))
        if (stale || !store.insert(row)) throw ConcurrentModificationException("notes on item ${item.id}", seq.toLong())
        effects.notesSaved(row)
        return row
    }

    /** Writing the notes: 403 `RECUSED` for a secretary recused from the item, 404 for one not cleared for it. */
    private fun requireKeeper(
        view: MeetingView,
        item: AgendaItemRef,
    ) {
        if (item.id in view.recused) {
            throw ForbiddenException(null, "RECUSED", "You declared an interest in this item: its notes are not yours to keep.")
        }
        if (!view.seesItem(item.id)) throw MinutesNotFoundException("notes")
    }

    /** The meeting and the item, if the actor keeps the notes: a secretary or `forums.manage`; 404 for anyone else. */
    private fun scene(
        meetingId: UUID,
        itemId: UUID,
    ): Pair<MeetingView, AgendaItemRef> {
        val view = viewers.of(meetingId)
        val item = view.agenda.item(itemId)?.takeIf { view.manages && view.lines.containsKey(it.id) }
        return view to (item ?: throw MinutesNotFoundException("notes"))
    }

    /** Notes are kept while the meeting runs and once it is held, while the minutes are in the draft. */
    private fun window(view: MeetingView) {
        val status = view.meeting.status
        val stage = stages.stage(view.meeting.id)
        refuseIf(status == MeetingStatus.MINUTES_APPROVED || status == MeetingStatus.CLOSED || stage == MinutesStage.APPROVED, "FROZEN") {
            "The minutes have been approved: the notes are kept as they are."
        }
        refuseIf(status != MeetingStatus.IN_PROGRESS && status != MeetingStatus.HELD, "WRONG_STATE") {
            "Notes are kept while the meeting is in progress, and once it is held."
        }
        refuseIf(stage?.inReview == true, "MINUTES_IN_REVIEW") { "The minutes are out of the draft: the notes are kept as they are." }
    }

    private companion object {
        const val MAX_TEXT = 10_000
    }
}
