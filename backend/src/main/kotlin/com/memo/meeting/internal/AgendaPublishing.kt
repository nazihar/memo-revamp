package com.memo.meeting.internal

import com.memo.audit.AuditLog
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.files.FileRenditions
import com.memo.files.Files
import com.memo.files.RenditionKind
import com.memo.files.RenditionState
import com.memo.forum.ForumCodes
import com.memo.jooq.tables.references.MEETING_COUNTER
import com.memo.meeting.AgendaPublished
import com.memo.meeting.MeetingChange
import com.memo.meeting.MeetingChanged
import com.memo.meeting.MeetingStatus
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.util.UUID

/** The per-forum, per-year meeting number counter (design E03 §1.10), inside a tenant transaction. */
@Repository
class MeetingCounterStore(
    private val tenantDsl: TenantDsl,
) {
    /**
     * The next number of [forumId] in [year]: the counter row is created if needed, locked (`SELECT … FOR UPDATE`) and
     * moved on in the business transaction, so a rolled-back publication leaves no gap (AGENTS.md §4).
     */
    fun next(
        forumId: UUID,
        year: Int,
    ): Int {
        val dsl = tenantDsl.current()
        dsl
            .insertInto(MEETING_COUNTER)
            .set(MEETING_COUNTER.TENANT_ID, tenantDsl.tenant().value)
            .set(MEETING_COUNTER.FORUM_ID, forumId)
            .set(MEETING_COUNTER.YEAR, year)
            .set(MEETING_COUNTER.LAST, 0)
            .onConflictDoNothing()
            .execute()
        val last =
            dsl
                .select(MEETING_COUNTER.LAST)
                .from(MEETING_COUNTER)
                .where(MEETING_COUNTER.FORUM_ID.eq(forumId), MEETING_COUNTER.YEAR.eq(year))
                .forUpdate()
                .fetchOne(MEETING_COUNTER.LAST) ?: 0
        val next = last + 1
        dsl
            .update(MEETING_COUNTER)
            .set(MEETING_COUNTER.LAST, next)
            .where(MEETING_COUNTER.FORUM_ID.eq(forumId), MEETING_COUNTER.YEAR.eq(year))
            .execute()
        return next
    }
}

/**
 * Meeting numbers (ADR-0060, design E03 §1.1): taken when the agenda is first published, the next of the forum in the
 * calendar year of the meeting's date in its zone, labelled "`{code}` `{n}`/`{yyyy}`" ("BRC 3/2026"). The forum's code
 * locks then. Audited as `meeting.numbered`.
 */
@Component
class MeetingNumbering(
    private val meetings: MeetingStore,
    private val counters: MeetingCounterStore,
    private val codes: ForumCodes,
    private val audit: AuditLog,
    private val events: ApplicationEventPublisher,
) {
    /** [meeting] with its number: taken now if it has none. */
    fun number(meeting: MeetingRow): MeetingRow {
        if (meeting.number != null) return meeting
        val code = codes.lock(meeting.forumId)
        val year = meeting.date.year
        val number = counters.next(meeting.forumId, year)
        val label = "$code $number/$year"
        check(meetings.number(meeting.id, number, year, label)) { "meeting ${meeting.id} was numbered meanwhile" }
        audit.record(
            "meeting.numbered",
            MeetingLifecycle.OBJECT_TYPE,
            meeting.id.toString(),
            after = mapOf("number" to number, "year" to year, "label" to label),
        )
        events.publishEvent(MeetingChanged(TenantContext.current().value, meeting.id, MeetingChange.NUMBERED))
        return checkNotNull(meetings.find(meeting.id))
    }
}

/** What a publication would carry now (design E03 §1.3): the flagged copy, and what refuses it. */
data class Publication(
    val revision: Int,
    val previous: RevisionHead?,
    /** The copy, flagged against the revision before, removed rows included. */
    val snapshot: Snapshot,
    val nothingNew: Boolean,
    val unreadable: List<UUID>,
    val chairAgreementMissing: Boolean,
    /** Items and papers waiting for the chair: left out. */
    val awaiting: List<Pair<ItemRow, PaperRow?>>,
    /** The agenda's version it was computed at. */
    val version: Long,
    /** In a preview, the items the officer previewing is recused from (design E04 §1.7): withheld from what they read. */
    val recused: Set<UUID> = emptySet(),
)

/**
 * Publishing the agenda (ADR-0060, design E03 §1.3): revision n is an immutable copy of the draft with computed numbers
 * and times, papers pinned to their current file version, and change flags against revision n−1; items waiting for the
 * chair are left out. Revision 1 needs the chair's agreement when the type says so and numbers the meeting; later ones
 * need a note. The audience is told after the commit ([AgendaPublished]), unless the secretary turns it off.
 *
 * Lock order (design E03 §1.8): the meeting's row, then the agenda's (a cancellation takes them in the same order).
 */
@Service
class AgendaPublisher(
    private val guard: AgendaGuard,
    private val meetingGuard: MeetingGuard,
    private val meetings: MeetingStore,
    private val agendas: AgendaStore,
    private val revisions: RevisionStore,
    private val lateness: AgendaLateness,
    private val files: Files,
    private val renditions: FileRenditions,
    private val numbering: MeetingNumbering,
    private val writes: AgendaWrites,
    private val ids: IdGenerator,
    private val events: ApplicationEventPublisher,
    private val carryOver: DeclarationCarryOver,
) {
    /** Publishes the next revision of [meetingId]'s agenda at [version]; its number. */
    fun publish(
        meetingId: UUID,
        version: Long,
        note: String?,
        notify: Boolean,
    ): Int {
        meetingGuard.requireManaged(meetingId)
        meetings.lock(meetingId)
        val edit = guard.edit(meetingId, version, tabled = true)
        val meeting = edit.meeting
        val n = edit.agenda.currentRevision + 1
        val (text, problem) = AgendaRules.optional("note", note, MAX_NOTE)
        requireNoProblems(
            listOfNotNull(problem, FieldProblem("note", "NOTE_REQUIRED", "Say in one line what changed.").takeIf { n > 1 && text == null }),
        )
        val publication = prepare(meeting, edit.rows)
        val refusal =
            RevisionRules.refusal(
                meeting.status,
                publication.chairAgreementMissing,
                publication.unreadable.isNotEmpty(),
                publication.nothingNew,
            )
        if (refusal == PAPER_NOT_READABLE) throw PaperNotReadableException(publication.unreadable)
        refuseIf(refusal != null, refusal.orEmpty()) { REFUSALS[refusal].orEmpty() }
        val numbered = numbering.number(meeting)
        val head =
            RevisionHead(
                id = ids.next(),
                meetingId = meeting.id,
                number = n,
                publishedAt = agendas.now().toInstant(),
                publishedBy = actorOrNull(),
                note = text,
                notified = notify,
                chairAgreement = edit.agenda.chairAgreement.takeIf { n == 1 },
            )
        revisions.insert(head, publication.snapshot)
        agendas.published(meeting.id, n)
        carryOver.published(numbered, edit.rows.items)
        val body = publication.snapshot.body()
        writes.record(
            "meeting.agenda.published",
            meeting.id,
            after =
                mapOf(
                    "revision" to n,
                    "note" to text,
                    "notified" to notify,
                    "label" to numbered.label,
                    "items" to body.items.size,
                    "papers" to body.papers.size,
                    "changes" to publication.snapshot.items.count { it.changes.isNotEmpty() },
                    "awaitingChair" to publication.awaiting.map { (item, paper) -> (paper?.id ?: item.id).toString() },
                ),
        )
        events.publishEvent(AgendaPublished(TenantContext.current().value, meeting.id, n))
        return n
    }

    /** What publishing now would carry and change, for the publish dialog (officers only). */
    fun preview(meetingId: UUID): Pair<MeetingRow, Publication?> {
        val meeting = guard.requireReader(meetingId)
        val rows = guard.rows(meeting.id) ?: return meeting to null
        return meeting to prepare(meeting, rows).copy(recused = guard.recused(rows))
    }

    private fun prepare(
        meeting: MeetingRow,
        rows: AgendaRows,
    ): Publication {
        val previous = revisions.heads(meeting.id).lastOrNull()
        val before = previous?.let { revisions.snapshot(it.id).body() }
        val versions = files.currentVersions(rows.papers.map { it.fileId }.toSet())
        val input =
            SnapshotInput(
                rows = rows,
                startsAt = meeting.startsAt,
                endsAt = meeting.endsAt,
                versions = versions,
                afterDeadline = meeting.status != MeetingStatus.IN_PROGRESS && lateness.passed(meeting, lateness.deadline(meeting)),
            )
        val next = RevisionRules.snapshot(input, before)
        val states = renditions.states(next.papers.map { it.fileVersionId })
        val unreadable =
            next.papers
                .filter { states[it.fileVersionId]?.get(RenditionKind.PDF) in UNREADABLE }
                .map { it.paperId }
        val itemsById = rows.items.associateBy { it.id }
        val awaiting =
            rows.items.filter { it.late == Lateness.AWAITING_CHAIR }.map { it to null } +
                rows.papers.filter { it.late == Lateness.AWAITING_CHAIR }.mapNotNull { p -> itemsById[p.itemId]?.let { it to p } }
        return Publication(
            revision = (previous?.number ?: 0) + 1,
            previous = previous,
            snapshot = RevisionRules.flagged(next, before),
            nothingNew = before != null && next.sameAs(before),
            unreadable = unreadable,
            chairAgreementMissing =
                previous == null && rows.agenda.chairAgreement == null &&
                    lateness.policy(meeting).agenda.chairAgreement,
            awaiting = awaiting,
            version = rows.agenda.version,
        )
    }

    companion object {
        const val MAX_NOTE = 500
        private val UNREADABLE = setOf(RenditionState.FAILED, RenditionState.NOT_POSSIBLE)
        private const val PAPER_NOT_READABLE = "PAPER_NOT_READABLE"

        /** The English fallback of each refusal (design E03 §1.3). */
        private val REFUSALS =
            mapOf(
                "FROZEN" to "The meeting is held or cancelled: its agenda no longer changes.",
                "MEETING_NOT_SCHEDULED" to "Schedule the meeting before publishing its agenda.",
                "CHAIR_AGREEMENT_REQUIRED" to
                    "The chair agrees the agenda before its first publication: ask them, or record their agreement.",
                "NOTHING_TO_PUBLISH" to "Nothing has changed since the last revision.",
            )
    }
}
