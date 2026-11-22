package com.memo.minutes.internal

import com.memo.forum.ForumRole
import com.memo.forum.MinutesTemplateTags
import com.memo.forum.SubmissionPurpose
import com.memo.meeting.AgendaItemDetails
import com.memo.meeting.AgendaItemRef
import com.memo.meeting.AttendanceCapacity
import com.memo.meeting.AttendanceEventKind
import com.memo.meeting.AttendanceMode
import com.memo.meeting.AttendanceState
import com.memo.meeting.AttendeeRecord
import com.memo.meeting.DeclarationRef
import com.memo.meeting.DeclarationState
import com.memo.meeting.ItemLateness
import com.memo.meeting.MeetingAttendanceRecord
import com.memo.meeting.MeetingKind
import com.memo.meeting.MeetingMode
import com.memo.meeting.MeetingRef
import com.memo.meeting.QuorumChangeKind
import com.memo.meeting.QuorumSnapshot
import com.memo.minutes.MinutesActionLine
import com.memo.templating.ControlMark
import com.memo.templating.TemplateData
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Everything a draft is filled from (design E06 §1.6.1), read in one transaction: the meeting and its committee, the
 * minuted items in agenda order with their details, the attendance register, the declarations, the decisions with their
 * resolution numbers and quorum snapshots, the secretary's notes, the actions (E07) and the names of everyone named.
 */
data class DraftSource(
    val meeting: MeetingRef,
    val committee: String,
    /** When the meeting was marked as held: "the meeting closed at …". */
    val heldAt: Instant,
    val items: List<AgendaItemRef>,
    val details: Map<UUID, AgendaItemDetails>,
    val attendance: MeetingAttendanceRecord?,
    /** The roster's roles on the meeting's date, by person. */
    val roles: Map<UUID, ForumRole>,
    val declarations: List<DeclarationRef>,
    /** The current decision on each decided item, by item. */
    val decisions: Map<UUID, CurrentDecision>,
    /** The resolution number in force of each numbered decision, by decision. */
    val resolutions: Map<UUID, String>,
    val snapshots: Map<UUID, QuorumSnapshot>,
    /** "item 4 of BRC 2/2026" for each decision an entry ratifies, by that decision. */
    val ratified: Map<UUID, String>,
    /** The latest notes on each item, by item. */
    val notes: Map<UUID, String>,
    val actions: List<MinutesActionLine>,
    val names: Map<UUID, String>,
)

/**
 * The draft's data (design E06 §1.7, ADR-0066): every tag of [MinutesTemplateTags] given a pre-formatted English value in
 * the meeting's zone, and one element of the items block per minuted item in agenda order, each wrapped in its locked
 * content control `memo-item:<id>` titled "Item n". Blank values make their template sections disappear. Text is made
 * safe for XML (characters XML 1.0 cannot carry are dropped); markup and tags in values stay text (the filler puts them in
 * as text runs). Pure.
 */
object MinutesTemplateData {
    /** The values of the meeting and the items block for [source]. */
    fun of(source: DraftSource): TemplateData {
        val words = DraftWords(source)
        val attendance = AttendanceRows(source, words)
        val items = ItemValues(source, words)
        val minuted = minuted(source.items)
        val meeting = source.meeting
        // Each resolution's row belongs to its item: a reader's copy that redacts the item drops it (E06 T7).
        val resolutions = items.resolutions(minuted)
        val values =
            mapOf(
                "meeting.committee" to source.committee,
                "meeting.label" to meeting.label.orEmpty(),
                "meeting.kind" to if (meeting.kind == MeetingKind.SPECIAL) "Special meeting" else "Ordinary meeting",
                "meeting.date" to words.day(meeting.date()),
                "meeting.start" to words.at(meeting.startsAt),
                "meeting.end" to words.at(meeting.endsAt),
                "meeting.zone" to meeting.timeZone.id,
                "meeting.place" to meeting.location.orEmpty(),
                "meeting.mode" to words.mode(meeting.mode),
                "meeting.chair" to attendance.chair(),
                "meeting.closedAt" to words.at(source.heldAt),
                // Deprecated (E06 T7): the copies are stamped DRAFT and the record approved; the document itself says neither.
                "minutes.status" to "",
                "minutes.approvedOn" to "",
                "minutes.approvedBy" to "",
                "quorum.opening" to attendance.opening(),
                "actions.count" to count(meetingActions(source).size),
            )
        val rows =
            mapOf(
                "present" to attendance.present(),
                "alternates" to attendance.alternates(),
                "inAttendance" to attendance.inAttendance(),
                "apologies" to attendance.apologies(),
                "absent" to attendance.absent(),
                "quorumChanges" to attendance.quorumChanges(),
                "resolutions" to resolutions.map { it.second },
                // The meeting's own actions only: an item's are in its block (its `item.actions`), never outside it.
                "actions" to meetingActions(source).map { action(it) + ("item" to "") },
            )
        return TemplateData(
            values.mapValues { clean(it.value) },
            rows.mapValues { (_, list) -> list.map { row -> row.mapValues { clean(it.value) } } },
            blocks = mapOf(MinutesTemplateTags.ITEMS to minuted.map(items::of)),
            rowTags = mapOf("resolutions" to resolutions.map { MinutesStructure.rowTag(it.first) }),
        )
    }

    /** The control around [item]'s block. */
    fun control(item: AgendaItemRef): ControlMark =
        ControlMark(MinutesStructure.tag(item.id), "Item ${item.number.orEmpty()}".take(ControlMark.MAX_TITLE))

    /** The minuted items of an agenda: those with a number (an item waiting for the chair was never taken). */
    fun minuted(items: List<AgendaItemRef>): List<AgendaItemRef> = items.filter { it.number != null }

    /** [text] without the characters XML 1.0 cannot carry (control characters other than tab and line breaks). */
    fun clean(text: String): String =
        buildString(text.length) {
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (xmlSafe(cp)) appendCodePoint(cp)
                i += Character.charCount(cp)
            }
        }.replace("\r\n", "\n").replace('\r', '\n')

    /**
     * The meeting's own actions, those agreed on no item (E06 T7): an item's actions are printed only inside its block, so a
     * reader's copy that redacts the item drops them with it (ADR-0066's leak check refuses a copy that names them anywhere
     * else). Pure.
     */
    fun meetingActions(source: DraftSource): List<MinutesActionLine> = source.actions.filter { it.itemId == null }

    /** A count as the template prints it: blank for none, so its section disappears. */
    fun count(n: Int): String = if (n > 0) n.toString() else ""

    /** XML 1.0's characters (§2.2): tab, line breaks, and the printable ranges without surrogates and U+FFFE/U+FFFF. */
    private fun xmlSafe(cp: Int): Boolean =
        cp in WHITESPACE || cp in PRINTABLE_LOW..PRINTABLE_HIGH || cp in PRIVATE_LOW..PRIVATE_HIGH || cp >= SUPPLEMENTARY

    private val WHITESPACE = setOf(0x9, 0xA, 0xD)
    private const val PRINTABLE_LOW = 0x20
    private const val PRINTABLE_HIGH = 0xD7FF
    private const val PRIVATE_LOW = 0xE000
    private const val PRIVATE_HIGH = 0xFFFD
    private const val SUPPLEMENTARY = 0x10000
}

/** How the draft writes times, dates, names and labels: English, in the meeting's zone. */
internal class DraftWords(
    private val s: DraftSource,
) {
    private val zone = s.meeting.timeZone
    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val date = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
    private val numbers = s.items.associate { it.id to it.number.orEmpty() }

    fun at(instant: Instant): String = instant.atZone(zone).format(time)

    fun day(date: java.time.LocalDate): String = date.format(this.date)

    fun number(itemId: UUID?): String = itemId?.let(numbers::get).orEmpty()

    fun name(id: UUID): String = s.names[id] ?: "A member"

    /** "A", "A and B", "A, B and C". */
    fun names(ids: List<UUID>): String {
        val list = ids.map(::name)
        return if (list.size <= 1) list.joinToString() else list.dropLast(1).joinToString(", ") + " and " + list.last()
    }

    fun mode(mode: MeetingMode): String =
        when (mode) {
            MeetingMode.IN_PERSON -> "In person"
            MeetingMode.ONLINE -> "Online"
            MeetingMode.HYBRID -> "In person and online"
        }

    fun mode(mode: AttendanceMode?): String =
        when (mode) {
            AttendanceMode.IN_PERSON -> "In person"
            AttendanceMode.REMOTE -> "Online"
            null -> ""
        }

    fun role(role: ForumRole?): String =
        role
            ?.name
            ?.lowercase(Locale.ENGLISH)
            ?.replace('_', '-')
            ?.replaceFirstChar { it.titlecase(Locale.ENGLISH) } ?: "Member"

    fun purpose(purpose: SubmissionPurpose?): String =
        when (purpose) {
            SubmissionPurpose.DECISION -> "For decision"
            SubmissionPurpose.DISCUSSION -> "For discussion"
            SubmissionPurpose.INFORMATION -> "For noting"
            null -> ""
        }

    fun circulated(late: ItemLateness): String =
        when (late) {
            ItemLateness.NONE -> "circulated in advance"
            ItemLateness.LATE -> "circulated late"
            ItemLateness.TABLED -> "tabled at the meeting"
        }
}

/** The attendance tables, who chaired and the quorum lines (design E06 §1.7), from the register. */
internal class AttendanceRows(
    private val s: DraftSource,
    private val words: DraftWords,
) {
    private val people = s.attendance?.people.orEmpty()

    /** Who chaired: the chair present, else a vice-chair present; blank when neither attended. */
    fun chair(): String {
        val present = members().map { it.userId }
        val chair = present.firstOrNull { s.roles[it] == ForumRole.CHAIR } ?: present.firstOrNull { s.roles[it] == ForumRole.VICE_CHAIR }
        return chair?.let(words::name).orEmpty()
    }

    fun opening(): String {
        val reached = s.attendance?.quorumChanges?.firstOrNull { it.kind == QuorumChangeKind.REACHED }
        return reached?.let { "The meeting was quorate (${it.present} present, ${it.needed} needed)." }.orEmpty()
    }

    fun present(): List<Map<String, String>> =
        members()
            .sortedWith(compareBy({ rank(s.roles[it.userId]) }, { words.name(it.userId) }))
            .map { p ->
                mapOf(
                    "name" to words.name(p.userId),
                    "role" to words.role(s.roles[p.userId]),
                    "mode" to words.mode(p.mode),
                    "from" to p.firstArrivedAt?.let(words::at).orEmpty(),
                    "until" to if (p.leftEarly) p.lastLeftAt?.let(words::at).orEmpty() else "",
                    "note" to note(p),
                )
            }

    fun alternates(): List<Map<String, String>> =
        people
            .filter { attended(it) && it.capacity == AttendanceCapacity.ALTERNATE }
            .sortedBy { words.name(it.userId) }
            .map {
                mapOf(
                    "name" to words.name(it.userId),
                    "forMember" to it.forUserId?.let(words::name).orEmpty(),
                    "mode" to words.mode(it.mode),
                )
            }

    fun inAttendance(): List<Map<String, String>> =
        people
            .filter { attended(it) && (it.capacity == AttendanceCapacity.GUEST || it.capacity == AttendanceCapacity.ATTENDEE) }
            .sortedBy { words.name(it.userId) }
            .map { p ->
                val guest = p.capacity == AttendanceCapacity.GUEST
                val items = s.items.filter { s.details[it.id]?.attendees?.contains(p.userId) == true }.mapNotNull { it.number }
                mapOf(
                    "name" to words.name(p.userId),
                    "capacity" to if (guest) "In attendance" else "In attendance for items",
                    "items" to if (guest) "All" else items.joinToString(", "),
                )
            }

    fun apologies(): List<Map<String, String>> =
        people
            .filter { it.state == AttendanceState.APOLOGIES && it.capacity == AttendanceCapacity.MEMBER }
            .sortedBy { words.name(it.userId) }
            .map { p ->
                val alternate =
                    people.firstOrNull {
                        it.capacity == AttendanceCapacity.ALTERNATE && it.forUserId == p.userId &&
                            attended(
                                it,
                            )
                    }
                mapOf("name" to words.name(p.userId), "alternate" to alternate?.let { words.name(it.userId) }.orEmpty())
            }

    fun absent(): List<Map<String, String>> =
        people
            .filter {
                it.capacity == AttendanceCapacity.MEMBER &&
                    (it.state == AttendanceState.ABSENT || it.state == AttendanceState.EXPECTED)
            }.sortedBy { words.name(it.userId) }
            .map { mapOf("name" to words.name(it.userId)) }

    fun quorumChanges(): List<Map<String, String>> =
        s.attendance
            ?.quorumChanges
            .orEmpty()
            .filter { it.kind != QuorumChangeKind.REACHED }
            .map { c ->
                val numbers = "(${c.present} present, ${c.needed} needed)"
                val text =
                    if (c.kind ==
                        QuorumChangeKind.LOST
                    ) {
                        "The meeting lost its quorum $numbers."
                    } else {
                        "The quorum was restored $numbers."
                    }
                mapOf("at" to words.at(c.at), "item" to words.number(c.itemId), "text" to text)
            }

    private fun members() = people.filter { attended(it) && it.capacity == AttendanceCapacity.MEMBER }

    private fun rank(role: ForumRole?): Int =
        when (role) {
            ForumRole.CHAIR -> 0
            ForumRole.VICE_CHAIR -> 1
            else -> 2
        }

    private fun note(p: AttendeeRecord): String =
        listOfNotNull(
            p.arrivedDuringItem?.takeIf { p.joinedLate }?.let { "Joined during item ${words.number(it)}" },
            p.leftDuringItem?.takeIf { p.leftEarly }?.let { "Left during item ${words.number(it)}" },
        ).joinToString("; ")

    companion object {
        fun attended(person: AttendeeRecord) = person.state == AttendanceState.PRESENT || person.state == AttendanceState.LEFT
    }
}

/** One item's block (design E06 §1.7): its heading, papers, declarations, notes and decision, in its content control. */
internal class ItemValues(
    private val s: DraftSource,
    private val words: DraftWords,
) {
    private val people = s.attendance?.people.orEmpty()

    fun of(item: AgendaItemRef): TemplateData {
        val details = s.details[item.id]
        val entry = s.decisions[item.id]?.entry
        val actions = s.actions.filter { it.itemId == item.id }
        val values =
            mapOf(
                "item.number" to item.number.orEmpty(),
                "item.title" to item.title,
                "item.section" to details?.sectionTitle.orEmpty(),
                "item.purpose" to words.purpose(item.purpose),
                "item.presenters" to
                    details?.presenters?.joinToString(", ") { p -> p.userId?.let(s.names::get) ?: p.name.orEmpty() }.orEmpty(),
                "item.papers" to details?.papers?.joinToString("\n") { "${it.title} — ${words.circulated(it.late)}" }.orEmpty(),
                "item.late" to late(details?.late),
                "item.declarations" to declarations(item.id),
                "item.notes" to s.notes[item.id].orEmpty(),
                "item.arrivals" to moved(item.id, AttendanceEventKind.ARRIVED, "joined"),
                "item.departures" to moved(item.id, AttendanceEventKind.LEFT, "left"),
                "item.actionCount" to MinutesTemplateData.count(actions.size),
            ) + decided(item.id)
        return TemplateData(
            values = values.mapValues { MinutesTemplateData.clean(it.value) },
            rows =
                mapOf(MinutesTemplateTags.ITEM_ACTIONS to actions.map { a -> action(a).mapValues { MinutesTemplateData.clean(it.value) } }),
            control = MinutesTemplateData.control(item),
        )
    }

    /** The resolutions table: each minuted item's resolution in force, in agenda order. */
    fun resolutions(items: List<AgendaItemRef>): List<Pair<UUID, Map<String, String>>> =
        items.mapNotNull { item ->
            val decision = s.decisions[item.id]
            val number = decision?.let { s.resolutions[it.decision.id] }
            if (decision == null || number == null) {
                null
            } else {
                item.id to
                    mapOf(
                        "number" to number,
                        "item" to item.number.orEmpty(),
                        "outcome" to decision.entry.outcomeLabel.uppercase(Locale.ENGLISH),
                        "text" to decision.entry.text.orEmpty(),
                    )
            }
        }

    /** The decision's tags; blank for an item not decided. */
    private fun decided(itemId: UUID): Map<String, String> {
        val decision = s.decisions[itemId] ?: return DECISION_TAGS.associateWith { "" }
        val entry = decision.entry
        return mapOf(
            "item.outcome" to entry.outcomeLabel.uppercase(Locale.ENGLISH),
            "item.resolutionNumber" to s.resolutions[decision.decision.id].orEmpty(),
            "item.resolutionText" to entry.text.orEmpty(),
            "item.conditions" to entry.conditions.mapIndexed { i, c -> "${i + 1}. $c" }.joinToString("\n"),
            "item.validUntil" to entry.validUntil?.let(words::day).orEmpty(),
            "item.vote" to vote(entry),
            "item.dissent" to entry.dissent.joinToString("\n") { dissent(it) },
            "item.abstentions" to if (entry.abstentions.isEmpty()) "" else "${words.names(entry.abstentions)} abstained.",
            "item.tookNoPart" to tookNoPart(itemId, entry.tookNoPart),
            "item.quorum" to s.snapshots[entry.snapshotId]?.let(::quorum).orEmpty(),
            "item.ratification" to ratification(entry),
        )
    }

    private fun late(late: ItemLateness?): String =
        when (late) {
            ItemLateness.TABLED -> "Tabled at the meeting with the chair's agreement."
            ItemLateness.LATE -> "Its papers were circulated after the deadline with the chair's agreement."
            else -> ""
        }

    /** The declarations on [itemId] still standing, oldest first; a withdrawn one is not minuted. */
    private fun declarations(itemId: UUID): String =
        s.declarations
            .filter { it.itemId == itemId && it.state != DeclarationState.WITHDRAWN }
            .joinToString("\n") { d ->
                val who = "${words.name(d.userId)} declared an interest (${d.nature.trim().trimEnd('.')})"
                when (d.state) {
                    DeclarationState.TAKING_PART -> "$who; the chair ruled that they could take part."
                    DeclarationState.AWAITING_RULING -> "$who and took no part while the chair's ruling was awaited."
                    else -> "$who and left the meeting for this item."
                }
            }

    private fun vote(entry: EntryRow): String =
        if (entry.voteMode != VoteMode.VOTE) {
            ""
        } else {
            listOfNotNull(
                entry.votesFor?.let { "For $it" },
                entry.votesAgainst?.let { "against $it" },
                entry.votesAbstain?.let { "abstaining $it" },
            ).joinToString(", ")
        }

    private fun dissent(d: Dissent): String {
        val reason =
            d.reason
                ?.trim()
                ?.trimEnd('.')
                ?.takeIf { it.isNotEmpty() }
        val who = "${words.name(d.userId)} asked that their dissent be recorded"
        return if (reason == null) "$who." else "$who: $reason."
    }

    /**
     * Who took no part: the decision's (recused and stepped out, from its snapshot), and the alternate who stood in for
     * one of them or for a member who declared, whom the snapshot does not name (log E06 T3).
     */
    private fun tookNoPart(
        itemId: UUID,
        recorded: List<UUID>,
    ): String {
        val declared = s.declarations.filter { it.itemId == itemId && it.state.effective }.map { it.userId }
        val standIns =
            people
                .filter {
                    it.capacity == AttendanceCapacity.ALTERNATE && AttendanceRows.attended(it) &&
                        it.forUserId in (recorded + declared)
                }.map { it.userId }
        val all = (recorded + standIns).distinct()
        return if (all.isEmpty()) "" else "${words.names(all)} took no part in this item."
    }

    private fun quorum(snapshot: QuorumSnapshot): String {
        val voting = snapshot.inputs.roster.count { it.voting }
        val r = snapshot.result
        val numbers = "(${r.present} of $voting voting members, ${r.needed} needed)"
        return if (r.quorate) "The meeting remained quorate $numbers." else "The meeting was not quorate $numbers."
    }

    private fun ratification(entry: EntryRow): String {
        val earlier = entry.ratifiesDecisionId?.let { s.ratified[it] ?: "an earlier item" }
        return when {
            entry.ratificationRequired -> "Decided without a quorum, to be ratified at a quorate meeting."
            earlier != null -> "This decision ratifies the decision on $earlier."
            else -> ""
        }
    }

    private fun moved(
        itemId: UUID,
        kind: AttendanceEventKind,
        verb: String,
    ): String =
        s.attendance
            ?.movements
            .orEmpty()
            .filter { it.itemId == itemId && it.kind == kind }
            .map { it.userId }
            .distinct()
            .joinToString("\n") { "${words.name(it)} $verb the meeting during this item." }

    private companion object {
        val DECISION_TAGS =
            listOf(
                "item.outcome",
                "item.resolutionNumber",
                "item.resolutionText",
                "item.conditions",
                "item.validUntil",
                "item.vote",
                "item.dissent",
                "item.abstentions",
                "item.tookNoPart",
                "item.quorum",
                "item.ratification",
            )
    }
}

/** An action's row fields, in the meeting's `actions` loop and the item's `item.actions` loop (E07). */
private fun action(a: MinutesActionLine) = mapOf("reference" to a.reference, "action" to a.action, "owner" to a.owner, "due" to a.due)
