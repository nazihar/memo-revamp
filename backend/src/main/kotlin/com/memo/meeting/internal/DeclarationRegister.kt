package com.memo.meeting.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.Clearances
import com.memo.common.tenant.TenantDsl
import com.memo.common.web.refuseIf
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRole
import com.memo.forum.Quorum
import com.memo.forum.QuorumCounts
import com.memo.forum.QuorumPresence
import com.memo.forum.QuorumSeat
import com.memo.forum.UnmetCondition
import com.memo.identity.UserSummaries
import com.memo.jooq.tables.references.MEETING_DECLARATION_REMINDER
import com.memo.jooq.tables.references.MEETING_DECLARATION_REVIEW
import com.memo.meeting.LineAccess
import com.memo.meeting.MeetingStatus
import com.memo.meeting.PaperAccess
import com.memo.meeting.Recusals
import com.memo.notification.NotificationKind
import com.memo.org.OrgDirectory
import com.memo.workflow.WorkingDays
import io.swagger.v3.oas.annotations.media.Schema
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/**
 * The reviews and the reminders sent per person (design E04 §1.5, §1.9), inside a tenant transaction: the latest revision
 * each person confirmed or was reminded of, and the reminder's mark, written once per person and revision.
 */
@Repository
class DeclarationReminderStore(
    private val tenantDsl: TenantDsl,
) {
    /** The latest revision of [meetingId] each person confirmed, for those who confirmed one. */
    fun reviewedBy(meetingId: UUID): Map<UUID, Int> =
        tenantDsl
            .current()
            .select(MEETING_DECLARATION_REVIEW.USER_ID, DSL.max(MEETING_DECLARATION_REVIEW.REVISION))
            .from(MEETING_DECLARATION_REVIEW)
            .where(MEETING_DECLARATION_REVIEW.MEETING_ID.eq(meetingId))
            .groupBy(MEETING_DECLARATION_REVIEW.USER_ID)
            .fetch()
            .associate { checkNotNull(it.value1()) to checkNotNull(it.value2()) }

    /** The latest revision of [meetingId] each person was reminded of, for those reminded. */
    fun remindedBy(meetingId: UUID): Map<UUID, Int> =
        tenantDsl
            .current()
            .select(MEETING_DECLARATION_REMINDER.USER_ID, DSL.max(MEETING_DECLARATION_REMINDER.REVISION))
            .from(MEETING_DECLARATION_REMINDER)
            .where(MEETING_DECLARATION_REMINDER.MEETING_ID.eq(meetingId))
            .groupBy(MEETING_DECLARATION_REMINDER.USER_ID)
            .fetch()
            .associate { checkNotNull(it.value1()) to checkNotNull(it.value2()) }

    /** Marks [userId] reminded of [meetingId]'s revision [revision] at [at], before the reminder is sent; false when they were. */
    fun remind(
        meetingId: UUID,
        userId: UUID,
        revision: Int,
        at: Instant,
    ): Boolean =
        tenantDsl
            .current()
            .insertInto(MEETING_DECLARATION_REMINDER)
            .set(MEETING_DECLARATION_REMINDER.TENANT_ID, tenantDsl.tenant().value)
            .set(MEETING_DECLARATION_REMINDER.MEETING_ID, meetingId)
            .set(MEETING_DECLARATION_REMINDER.USER_ID, userId)
            .set(MEETING_DECLARATION_REMINDER.REVISION, revision)
            .set(MEETING_DECLARATION_REMINDER.SENT_AT, MeetingStore.utc(at))
            .onConflictDoNothing()
            .execute() == 1
}

/** One person's review of a meeting's agenda (design E04 §1.5). */
data class ReviewState(
    val userId: UUID,
    /** An officer of the forum (secretary, chair or vice-chair seat), not only a member. */
    val officer: Boolean,
    /** The latest revision they confirmed; null before a first confirmation. */
    val reviewed: Int?,
    /** The items of the latest revision they see and have not confirmed, in agenda order. */
    val toReview: List<UUID>,
    /** The latest revision they were reminded of; null when never. */
    val reminded: Int?,
)

/**
 * Who has reviewed a meeting's agenda for interests (design E04 §1.5), for the meeting register and the reminders: the
 * members in office of the meeting's audience and the forum's officers (secretaries, chair, vice-chair). Guests and item
 * attendees may confirm but are not counted or reminded. Items to review are those of the latest revision first published
 * after the person's last confirmation whose title they see (E03 `seeTitle` through [PaperAccess], their own recusals
 * included); the paper rule runs only for people with such items.
 */
@Component
class DeclarationReviewStates(
    private val store: DeclarationReminderStore,
    private val revisions: RevisionStore,
    private val audience: AudienceStore,
    private val viewers: PaperViewers,
    private val paperAccess: PaperAccess,
    private val clearances: Clearances,
    private val directory: ForumDirectory,
    private val org: OrgDirectory,
) {
    /** The review of each person counted on [agenda]'s meeting, the officers first. */
    fun of(agenda: DeclarationAgenda): List<ReviewState> {
        val meeting = agenda.meeting
        val today = org.today()
        val rows = audience.audience(meeting.id)
        val members =
            rows
                .filter { row -> row.source == AudienceSource.MEMBER && row.until?.isBefore(today) != true }
                .map { it.userId }
                .toSet()
        val officers =
            directory
                .membersOn(meeting.forumId)
                .filter { it.role in OFFICER_SEATS }
                .map { it.userId }
                .toSet()
        val people = officers + members
        if (people.isEmpty()) return emptyList()
        val reviewed = store.reviewedBy(meeting.id)
        val reminded = store.remindedBy(meeting.id)
        val first = if (agenda.latest > 0) revisions.firstPublished(meeting.id) else emptyMap()
        val askable =
            agenda.published.values
                .filter { it.kind == ItemKind.ITEM || it.kind == ItemKind.SUBMISSION }
                .sortedBy { it.position }
        val waiting = people.associateWith { p -> askable.filter { (first[it.itemId] ?: 0) > (reviewed[p] ?: 0) }.map { it.itemId } }
        val judged = waiting.filterValues { it.isNotEmpty() }.keys
        val titles = titles(agenda, judged, rows.groupBy { it.userId })
        return people.map { p ->
            val seen = titles[p].orEmpty()
            ReviewState(p, p in officers, reviewed[p], waiting.getValue(p).filter { it in seen }, reminded[p])
        }
    }

    /** The items of the latest revision whose title each of [people] sees. */
    private fun titles(
        agenda: DeclarationAgenda,
        people: Set<UUID>,
        rows: Map<UUID, List<AudienceRow>>,
    ): Map<UUID, Set<UUID>> {
        val revision = agenda.latestId
        if (revision == null || people.isEmpty()) return emptyMap()
        val ranks = clearances.rankOf(people)
        val paperOfficers = viewers.officers(agenda.meeting.forumId)
        val all = people.map { p -> viewers.of(agenda.meeting, p, rows[p].orEmpty(), ranks[p] ?: 0, paperOfficers) }
        return all.chunked(VIEWERS_PER_QUERY).fold(emptyMap()) { seen, chunk ->
            seen + paperAccess.linesOf(chunk, revision, titledOnly = true).mapValues { it.value.lines.keys }
        }
    }

    private companion object {
        val OFFICER_SEATS = setOf(ForumRole.SECRETARY, ForumRole.CHAIR, ForumRole.VICE_CHAIR)

        /** People judged in one statement (a `UNION ALL` of their conditions). */
        const val VIEWERS_PER_QUERY = 50
    }
}

/**
 * The declarations-due reminder (design E04 §1.5, §9): `MEETING_DECLARATIONS_DUE` to each person counted in the review
 * who still has items to review, once per person and revision (the `meeting_declaration_reminder` row, written before the
 * reminder is sent). The hourly sweep sends it from 09:00 in the meeting's zone on the working day before a scheduled
 * meeting with a published agenda, until the meeting starts; a secretary sends it at once ("Remind them now").
 */
@Component
class DeclarationReminders(
    private val sweepStore: MeetingSweepStore,
    private val declarations: DeclarationAccess,
    private val states: DeclarationReviewStates,
    private val store: DeclarationReminderStore,
    private val notifier: MeetingNotifier,
    private val directory: ForumDirectory,
    private val workingDays: WorkingDays,
    private val audit: AuditLog,
) {
    /** The sweep's part (d), at [now]: how many meetings had people reminded. */
    fun due(now: Instant): Int {
        var sent = 0
        sweepStore.declarationsDue(now, now.plus(HORIZON), MeetingSweep.BATCH).forEach { meeting ->
            val day = workingDays.before(meeting.date, 1) ?: return@forEach
            if (now.isBefore(day.atTime(FROM).atZone(meeting.zone).toInstant())) return@forEach
            if (remind(meeting, declarations.agenda(meeting), now, manual = false).isNotEmpty()) sent++
        }
        return sent
    }

    /**
     * Reminds the people of [agenda]'s meeting who have items to review and were not reminded of its latest revision,
     * except the actor; marks each before telling them, and audits the meeting's reminder once. The people reminded.
     */
    fun remind(
        meeting: MeetingRow,
        agenda: DeclarationAgenda,
        at: Instant,
        manual: Boolean,
    ): List<UUID> {
        val forum = directory.forum(meeting.forumId)
        if (agenda.latest == 0 || forum == null) return emptyList()
        val actor = actorOrNull()
        val sent =
            states
                .of(agenda)
                .filter { it.userId != actor && it.toReview.isNotEmpty() && (it.reminded ?: 0) < agenda.latest }
                .map { it.userId }
                .sorted()
                .filter { store.remind(meeting.id, it, agenda.latest, at) }
        sent.forEach { notifier.person(it, NotificationKind.MEETING_DECLARATIONS_DUE, meeting, forum, emptyMap()) }
        if (sent.isNotEmpty()) {
            audit.record(
                "meeting.reminded.declarations_due",
                MeetingLifecycle.OBJECT_TYPE,
                meeting.id.toString(),
                after = mapOf("revision" to agenda.latest, "count" to sent.size, "manual" to manual),
            )
        }
        return sent
    }

    private companion object {
        /** The reminder goes from 09:00 in the meeting's zone (design E04 §1.5). */
        val FROM: LocalTime = LocalTime.of(9, 0)

        /** The working day before is at most a few weeks before the meeting, holidays included. */
        val HORIZON: Duration = Duration.ofDays(30)
    }
}

/** Why an item cannot be decided even if every member attends (design E04 §1.8). */
data class QuorumWarningResponse(
    /** Counting members in office who are recused from the item. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val declared: Int,
    /** Counting members in office, or the committee's fixed seats. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val basis: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val needed: Int,
    /** Who would count with every member present. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val available: Int,
    /** Whether the committee counts members with an interest towards an item's quorum (ADR-0057). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val recusedCount: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val unmet: List<UnmetCondition>,
)

/** An item of the meeting register: its declarations as the viewer may see them. */
data class RegisterItemResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val itemId: UUID,
    val number: String?,
    /** Null when the viewer may not read the title ("Restricted item"). */
    val title: String?,
    /** The viewer is recused from the item: only their own declaration is listed, and no warning. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val recused: Boolean,
    /** Withdrawn ones included, the oldest first. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val declarations: List<DeclarationResponse>,
    /** People recused from the item who still read its memo through the memo's own access (design E04 §1.4). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val alsoReadsMemo: List<PersonResponse>,
    val quorumWarning: QuorumWarningResponse?,
)

/** One person's review of the agenda, as the secretary sees it (design E04 flow T1). */
data class ReviewStatusResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val user: PersonResponse,
    /** An officer of the committee (secretary, chair, vice-chair), not only a member. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val officer: Boolean,
    /** The latest revision they confirmed; null before a first confirmation. */
    val reviewedRevision: Int?,
    /** Items of the latest revision they see and have not confirmed; 0: up to date. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val toReview: Int,
    /** The latest revision they were reminded of; null when never. */
    val remindedRevision: Int?,
)

/** The meeting register (design E04 §3): declarations per item, the review of the agenda and quorum warnings. */
data class MeetingDeclarationRegisterResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    /** The latest published revision; 0 before the first. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val latestRevision: Int,
    /** The viewer may send the reminder now (a secretary or `forums.manage`, the agenda published, the meeting to come). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val canRemind: Boolean,
    /** Items with declarations the viewer may see, or a quorum warning, in agenda order. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val items: List<RegisterItemResponse>,
    /** The members in office and the officers, by name. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val reviews: List<ReviewStatusResponse>,
)

/** "Remind them now": who was reminded of which revision. */
data class DeclarationReminderResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val revision: Int,
    /** Empty when everyone has reviewed or was already reminded of this revision. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val reminded: List<PersonResponse>,
)

/**
 * The meeting register of declarations (design E04 §1.8, §3, flow T1), for the forum's secretaries, its chair and
 * vice-chair and `forums.manage`: per item the declarations with their natures, the review of the agenda per member,
 * quorum warnings (`Quorum.evaluate` with every voting member in office present and the declarants recused), and "also
 * reads the memo". An officer recused from an item sees only their own declaration on it; items without a line for the
 * viewer (above their clearance) are left out but for their own declaration. Everyone else is 403.
 */
@Service
class DeclarationRegister(
    private val service: Declarations,
    private val access: MeetingAccess,
    private val viewers: PaperViewers,
    private val paperAccess: PaperAccess,
    private val store: DeclarationStore,
    private val recusalStore: RecusalStore,
    private val recusals: Recusals,
    private val memoReaders: RecusedMemoReaders,
    private val states: DeclarationReviewStates,
    private val reminders: DeclarationReminders,
    private val lateness: AgendaLateness,
    private val directory: ForumDirectory,
    private val views: DeclarationViews,
    private val users: UserSummaries,
    private val clock: Clock,
) {
    fun meeting(meetingId: UUID): MeetingDeclarationRegisterResponse {
        val scene = service.scene(meetingId)
        forbidIf(!scene.officer && !scene.chairSeat)
        val agenda = scene.agenda
        val lines = lines(scene)
        val recused = recusedItems(scene)
        val visible =
            store.ofMeeting(scene.meeting.id).filter { row ->
                row.userId == scene.actor ||
                    (row.itemId !in recused && lines[row.itemId].let { it == LineAccess.FULL || it == LineAccess.TITLE })
            }
        val warnings = warnings(scene, lines, recused)
        val readers = memoReaders.of(scene.meeting.id)
        val reviews = states.of(agenda)
        val names =
            views.names(visible) + users.names(readers.values.flatten().toSet() + reviews.map { it.userId })
        val person = { id: UUID -> PersonResponse(id, names[id].orEmpty()) }
        val byItem = visible.groupBy { it.itemId }
        val order = order(agenda)
        val items =
            (byItem.keys + warnings.keys)
                .sortedWith(compareBy({ order[it] ?: Int.MAX_VALUE }, { it }))
                .map { itemId ->
                    val item = agenda.item(itemId, officer = true)
                    val shown = byItem[itemId].orEmpty()
                    val title = item?.title?.takeIf { lines[itemId] == LineAccess.FULL || lines[itemId] == LineAccess.TITLE }
                    RegisterItemResponse(
                        itemId = itemId,
                        number = item?.number,
                        title = title,
                        recused = itemId in recused,
                        declarations = shown.map { DeclarationViews.of(it, item?.number, title, names) },
                        alsoReadsMemo =
                            readers[itemId]
                                .orEmpty()
                                .filter { reader -> shown.any { it.userId == reader } }
                                .sorted()
                                .map(person),
                        quorumWarning = warnings[itemId],
                    )
                }
        return MeetingDeclarationRegisterResponse(
            meetingId = scene.meeting.id,
            latestRevision = agenda.latest,
            canRemind = access.manages(scene.meeting.forumId) && agenda.latest > 0 && scene.meeting.status in REMINDABLE,
            items = items,
            reviews =
                reviews
                    .sortedWith(compareBy({ !it.officer }, { names[it.userId].orEmpty() }, { it.userId }))
                    .map { ReviewStatusResponse(person(it.userId), it.officer, it.reviewed, it.toReview.size, it.reminded) },
        )
    }

    /**
     * "Remind them now" (design E04 §3): a secretary or `forums.manage` sends the declarations-due reminder at once to those
     * with items to review, once per person and revision (409 `WRONG_STATE` unless the meeting is scheduled or in progress,
     * `NOT_PUBLISHED` before the first revision).
     */
    fun remind(meetingId: UUID): DeclarationReminderResponse {
        val scene = service.scene(meetingId)
        forbidIf(!access.manages(scene.meeting.forumId))
        refuseIf(scene.meeting.status !in REMINDABLE, "WRONG_STATE") { "Only a scheduled or running meeting's members are reminded." }
        refuseIf(scene.agenda.latest == 0, "NOT_PUBLISHED") { "Publish the agenda first: members review a published revision." }
        val sent = reminders.remind(scene.meeting, scene.agenda, clock.instant(), manual = true)
        val names = users.names(sent)
        return DeclarationReminderResponse(scene.agenda.latest, sent.map { PersonResponse(it, names[it].orEmpty()) })
    }

    /** How much of each item the actor sees: the latest revision's lines, and an officer's lines of the live draft. */
    private fun lines(scene: DeclarationScene): Map<UUID, LineAccess> {
        val viewer = viewers.actor(scene.meeting) ?: return emptyMap()
        val published =
            scene.agenda.latestId
                ?.let { paperAccess.revision(viewer, it).lines }
                .orEmpty()
        val draft = if (viewer.officer) paperAccess.draft(viewer, scene.meeting.id).lines else emptyMap()
        return (published.keys + draft.keys).associateWith { item ->
            listOfNotNull(published[item], draft[item]).minBy { it.ordinal }
        }
    }

    /** The items the actor is recused from: their effective declarations' items and those items' sub-items. */
    private fun recusedItems(scene: DeclarationScene): Set<UUID> {
        val declared = recusalStore.effective(scene.meeting.id, scene.actor).map { it.first }.toSet()
        if (declared.isEmpty()) return emptySet()
        val children =
            scene.agenda.published.values
                .filter { it.parentId in declared }
                .map { it.itemId } +
                scene.agenda.draft.values
                    .filter { it.parentId in declared }
                    .map { it.id }
        return declared + children
    }

    /**
     * The items that cannot be decided even with every voting member in office present (design E04 §1.8), among those
     * with effective declarations that the actor sees and is not recused from.
     */
    private fun warnings(
        scene: DeclarationScene,
        lines: Map<UUID, LineAccess>,
        recused: Set<UUID>,
    ): Map<UUID, QuorumWarningResponse> {
        val declared = recusals.ofMeeting(scene.meeting.id).filterKeys { it in lines && it !in recused }
        if (declared.isEmpty()) return emptyMap()
        val rule = lateness.policy(scene.meeting).quorum
        val seats = directory.membersOn(scene.meeting.forumId, scene.meeting.date)
        val roster = seats.map { QuorumSeat(it.memberId, it.role, it.voting, it.attributes) }
        return declared
            .mapValues { (_, people) ->
                val attendance = seats.associate { it.memberId to QuorumPresence(recused = it.userId in people) }
                val result = Quorum.evaluate(rule, roster, attendance)
                if (result.quorate) {
                    null
                } else {
                    val counting = seats.filter { it.role.countable && (rule.counts == QuorumCounts.ALL || it.voting) }
                    QuorumWarningResponse(
                        declared = counting.count { it.userId in people },
                        basis = result.basis,
                        needed = result.needed,
                        available = result.present,
                        recusedCount = rule.recusedCount,
                        unmet = result.unmet,
                    )
                }
            }.filterValues { it != null }
            .mapValues { checkNotNull(it.value) }
    }

    /** Agenda order: the latest revision's positions, then the draft's items after them. */
    private fun order(agenda: DeclarationAgenda): Map<UUID, Int> {
        val published =
            agenda.published.values
                .sortedBy { it.position }
                .map { it.itemId }
        val draft =
            agenda.draft.keys
                .filter { it !in agenda.published }
                .sorted()
        return (published + draft).withIndex().associate { it.value to it.index }
    }

    private companion object {
        val REMINDABLE = setOf(MeetingStatus.SCHEDULED, MeetingStatus.IN_PROGRESS)
    }
}
