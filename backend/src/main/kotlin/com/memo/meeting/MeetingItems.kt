package com.memo.meeting

import com.memo.forum.AgendaSectionKind
import com.memo.forum.StandingKind
import com.memo.forum.SubmissionPurpose
import com.memo.meeting.internal.AgendaRules
import com.memo.meeting.internal.ConsentRequestStore
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.ItemStore
import com.memo.meeting.internal.Lateness
import com.memo.meeting.internal.MeetingAccess
import com.memo.meeting.internal.MeetingGuard
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.MeetingStore
import com.memo.meeting.internal.PaperViewers
import com.memo.meeting.internal.PublishedLate
import com.memo.meeting.internal.RevisionStore
import com.memo.meeting.internal.SectionStore
import com.memo.meeting.internal.Snapshot
import com.memo.meeting.internal.actorOrNull
import org.springframework.stereotype.Component
import java.util.UUID

/** What an agenda item is, as other modules read it (design E03 §1.2); breaks are never decided and never listed. */
enum class AgendaItemKind { SUBMISSION, STANDING, ITEM }

/**
 * An item of a meeting's agenda as E06 decides it (design E06 §1.1): its row in the latest published revision, or a live
 * item tabled at the meeting and not published yet. The [title] and [resolutionSought] are business content: the caller
 * decides who may read them ([MeetingViewers]).
 */
data class AgendaItemRef(
    val id: UUID,
    val parentId: UUID?,
    val sectionId: UUID,
    /** As published (or as the draft numbers it, for an unpublished tabled item); null for one waiting for the chair. */
    val number: String?,
    val title: String,
    val titleHidden: Boolean,
    val kind: AgendaItemKind,
    val standingKind: StandingKind?,
    val purpose: SubmissionPurpose?,
    val resolutionSought: String?,
    val submissionId: UUID?,
    val memoId: UUID?,
    /** "Only these people"; null: the item is not restricted. */
    val restrictedTo: List<UUID>?,
    /** Its section is a consent section (ADR-0060). */
    val consent: Boolean,
    /** Someone asked for it to be lifted for discussion (an open request, design E03 §1.5). */
    val lifted: Boolean,
    /** In the latest published revision; false for a tabled item not published yet. */
    val published: Boolean,
    /** Tabled at the meeting (E03). */
    val tabled: Boolean,
) {
    val restricted: Boolean get() = restrictedTo != null
}

/** A meeting's decidable items, in agenda order (the latest revision's, then tabled items not yet published). */
data class MeetingAgendaRef(
    val meetingId: UUID,
    /** The latest published revision; 0 before the first. */
    val revision: Int,
    val items: List<AgendaItemRef>,
) {
    fun item(id: UUID): AgendaItemRef? = items.firstOrNull { it.id == id }

    /** The items of section [sectionId] in the latest revision. */
    fun section(sectionId: UUID): List<AgendaItemRef> = items.filter { it.sectionId == sectionId && it.published }
}

/**
 * A meeting's agenda items for other modules (design E06 §2): what is on the agenda to be decided. Inside a tenant
 * transaction; no access decision is made here.
 */
@Component
class MeetingItems(
    private val revisions: RevisionStore,
    private val items: ItemStore,
    private val sections: SectionStore,
    private val requests: ConsentRequestStore,
) {
    /** [meetingId]'s items: the latest revision's body (no breaks), then the live tabled items it does not carry. */
    fun agenda(meetingId: UUID): MeetingAgendaRef {
        val head = revisions.heads(meetingId).lastOrNull()
        val lifted = requests.open(meetingId).map { it.itemId }.toSet()
        val published = head?.let { published(revisions.snapshot(it.id).body(), lifted) }.orEmpty()
        return MeetingAgendaRef(meetingId, head?.number ?: 0, published + tabled(meetingId, published.map { it.id }.toSet(), lifted))
    }

    private fun published(
        body: Snapshot,
        lifted: Set<UUID>,
    ): List<AgendaItemRef> {
        val consent =
            body.sections
                .filter { it.kind == AgendaSectionKind.CONSENT }
                .map { it.sectionId }
                .toSet()
        return body.items.filter { it.kind != ItemKind.BREAK }.map { row ->
            AgendaItemRef(
                id = row.itemId,
                parentId = row.parentId,
                sectionId = row.sectionId,
                number = row.number,
                title = row.title,
                titleHidden = row.titleHidden,
                kind = kind(row.kind),
                standingKind = row.standingKind,
                purpose = row.purpose,
                resolutionSought = row.resolutionSought,
                submissionId = row.submissionId,
                memoId = row.memoId,
                restrictedTo = row.restrictedTo,
                consent = row.sectionId in consent,
                lifted = row.itemId in lifted,
                published = true,
                tabled = row.late == PublishedLate.TABLED,
            )
        }
    }

    /** The live items tabled at the meeting that the latest revision does not carry (officers see them). */
    private fun tabled(
        meetingId: UUID,
        carried: Set<UUID>,
        lifted: Set<UUID>,
    ): List<AgendaItemRef> {
        val liveSections = sections.live(meetingId)
        val live = items.live(meetingId)
        val numbers = AgendaRules.numbers(liveSections, live)
        val consent = liveSections.filter { it.kind == AgendaSectionKind.CONSENT }.map { it.id }.toSet()
        return live.filter { it.late == Lateness.TABLED && it.kind != ItemKind.BREAK && it.id !in carried }.map { row ->
            AgendaItemRef(
                id = row.id,
                parentId = row.parentId,
                sectionId = row.sectionId,
                number = numbers[row.id],
                title = row.title,
                titleHidden = row.titleHidden,
                kind = kind(row.kind),
                standingKind = row.standingKind,
                purpose = row.purpose,
                resolutionSought = row.resolutionSought,
                submissionId = row.submissionId,
                memoId = row.memoId,
                restrictedTo = row.restrictedTo,
                consent = row.sectionId in consent,
                lifted = row.id in lifted,
                published = false,
                tabled = true,
            )
        }
    }

    private fun kind(kind: ItemKind): AgendaItemKind =
        when (kind) {
            ItemKind.SUBMISSION -> AgendaItemKind.SUBMISSION
            ItemKind.STANDING -> AgendaItemKind.STANDING
            else -> AgendaItemKind.ITEM
        }
}

/**
 * The signed-in actor on one meeting, as E06 decides what they may do and read (design E06 §1.9, §4): their relation to
 * the committee, the meeting's decidable items, and the line they see of each by the one paper rule (ADR-0061) with E04's
 * recusals (ADR-0062). An item absent from [lines] is invisible to them.
 */
data class MeetingView(
    val meeting: MeetingRef,
    val userId: UUID,
    /** A secretary of the committee in office, or `forums.manage`: may run the meeting and record its decisions. */
    val manages: Boolean,
    /** [manages], or the chair in office (the vice-chair when there is none). */
    val officer: Boolean,
    /** A `forum.read` holder cleared for the meeting: reads unrestricted, unhidden items' lines. */
    val reader: Boolean,
    val agenda: MeetingAgendaRef,
    val lines: Map<UUID, LineAccess>,
    /** The items whose line they see only as their recused line (an effective declaration on it or its parent). */
    val recused: Set<UUID>,
) {
    /** Whether they see [itemId] in full (ADR-0061's `seeItem`). */
    fun seesItem(itemId: UUID): Boolean = lines[itemId] == LineAccess.FULL
}

/**
 * Who the actor is to a meeting, for E06 (design E06 §1.9): [of] answers the [MeetingView] of a meeting they see, and
 * fails closed with the meeting module's 404 otherwise. Inside a tenant transaction.
 */
@Component
class MeetingViewers(
    private val guard: MeetingGuard,
    private val store: MeetingStore,
    private val access: MeetingAccess,
    private val viewers: PaperViewers,
    private val paperAccess: PaperAccess,
    private val revisions: RevisionStore,
    private val items: MeetingItems,
) {
    /** The actor on [meetingId]; the meeting module's 404 when they do not see it or nobody is signed in. */
    fun of(meetingId: UUID): MeetingView = view(guard.requireVisible(meetingId).first)

    /** The actor on [meetingId]; null when they do not see it (a list across meetings skips it). */
    fun find(meetingId: UUID): MeetingView? = store.find(meetingId)?.takeIf { access.relation(it) != null }?.let(::view)

    private fun view(meeting: MeetingRow): MeetingView {
        val user = checkNotNull(actorOrNull()) { "a meeting view needs a signed-in user" }
        val viewer = checkNotNull(viewers.actor(meeting))
        val agenda = items.agenda(meeting.id)
        val head = revisions.heads(meeting.id).lastOrNull()
        val published = head?.let { paperAccess.revision(viewer, it.id) } ?: Visibility(emptyMap(), emptySet())
        val tabled =
            agenda.items
                .filterNot { it.published }
                .map { it.id }
                .toSet()
        // Tabled items not published yet are the officers' only: the audience reads published revisions.
        val draft =
            if (viewer.officer && tabled.isNotEmpty()) {
                paperAccess.draft(viewer, meeting.id).let { v -> Visibility(v.lines.filterKeys { it in tabled }, emptySet(), v.recused) }
            } else {
                Visibility(emptyMap(), emptySet())
            }
        val manages = access.manages(meeting.forumId)
        return MeetingView(
            meeting = meeting.ref(),
            userId = user,
            manages = manages,
            officer = viewer.officer,
            reader = viewer.reader,
            agenda = agenda,
            lines = published.lines + draft.lines,
            recused = published.recused + draft.recused.filter { it in tabled },
        )
    }
}

/**
 * The E06 hook in declarations (design E06 §2, E04 §1.1): once an item is decided, its declarations change only through a
 * secretary's correction until the minutes are approved. The minutes module implements it; without it nothing is decided.
 */
interface DecidedItems {
    /** Whether [itemId] has a decision recorded. */
    fun isDecided(itemId: UUID): Boolean
}

/** Nothing is decided: the rule before E06. Not a bean; the meeting module falls back on it when no module provides one. */
object NoDecidedItems : DecidedItems {
    override fun isDecided(itemId: UUID): Boolean = false
}
