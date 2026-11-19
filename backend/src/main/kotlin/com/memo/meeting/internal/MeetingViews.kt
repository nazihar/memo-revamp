package com.memo.meeting.internal

import com.memo.common.web.Cursors
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRef
import com.memo.identity.UserSummaries
import com.memo.meeting.MeetingKind
import com.memo.meeting.MeetingMode
import com.memo.meeting.MeetingStatus
import com.memo.org.OrgDirectory
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

data class MeetingGuestResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val userId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    val note: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val addedAt: Instant,
)

/** A meeting as the viewer may see it (design E03 §3); the place and link only for the audience and officers. */
data class MeetingResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumName: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumCode: String,
    /** The committee type version pinned at creation. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val typeVersion: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: MeetingKind,
    val title: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val startsAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val endsAt: Instant,
    /** The IANA zone the meeting is held in. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val timeZone: String,
    /** The start and end on the clock of [timeZone]. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val localStartsAt: LocalDateTime,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val localEndsAt: LocalDateTime,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mode: MeetingMode,
    /** Null for `forum.read` holders outside the audience. */
    val location: String?,
    val joinUrl: String?,
    val joinDetails: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val classificationId: UUID,
    val number: Int?,
    /** "BRC 3/2026", once the agenda is first published. */
    val label: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val status: MeetingStatus,
    /** Why it was cancelled. */
    val statusReason: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val relation: MeetingRelation,
    /** Whether the viewer may edit, schedule, run and cancel it, and change its guests. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val canManage: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val guests: List<MeetingGuestResponse>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Long,
)

/** A meeting in a list ("My meetings", a committee's Meetings tab). */
data class MeetingSummaryResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumName: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumCode: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: MeetingKind,
    val title: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val startsAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val endsAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val timeZone: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val localStartsAt: LocalDateTime,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val localEndsAt: LocalDateTime,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mode: MeetingMode,
    val location: String?,
    val joinUrl: String?,
    val label: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val status: MeetingStatus,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val relation: MeetingRelation,
    /** "My meetings" only: the latest published revision of the agenda; null before the first. */
    val agendaRevision: Int? = null,
    /** "My meetings" only: the last revision the viewer looked at; null when never. */
    val lastSeenRevision: Int? = null,
    /** "My meetings" only: the lines changed since the viewer last looked, as far as they see them; null when not counted. */
    val changesSinceSeen: Int? = null,
)

data class MeetingPageResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val items: List<MeetingSummaryResponse>,
    /** Pass as `cursor` for the next page; null on the last one. */
    val nextCursor: String?,
)

/**
 * Reading meetings (design E03 §3): one, a forum's by date with a cursor, and "My meetings". Every row passes the
 * actor's relation ([MeetingAccess.relation]); the place and link are left out for `forum.read` holders.
 */
@Component
class MeetingReads(
    private val lists: MeetingListStore,
    private val access: MeetingAccess,
    private val guard: MeetingGuard,
    private val audience: AudienceStore,
    private val directory: ForumDirectory,
    private val users: UserSummaries,
    private val org: OrgDirectory,
    private val agendas: MemberAgendas,
) {
    fun get(id: UUID): MeetingResponse {
        val (meeting, relation) = guard.requireVisible(id)
        return detail(meeting, relation)
    }

    fun detail(
        meeting: MeetingRow,
        relation: MeetingRelation,
    ): MeetingResponse {
        val forum = forums(listOf(meeting)).getValue(meeting.forumId)
        val guests = audience.guests(meeting.id)
        val names = users.names(guests.map { it.userId })
        val place = relation != MeetingRelation.READER
        return MeetingResponse(
            id = meeting.id,
            forumId = meeting.forumId,
            forumName = forum.name,
            forumCode = forum.code,
            typeVersion = meeting.typeVersion,
            kind = meeting.kind,
            title = meeting.title,
            startsAt = meeting.startsAt,
            endsAt = meeting.endsAt,
            timeZone = meeting.zone.id,
            localStartsAt = LocalDateTime.ofInstant(meeting.startsAt, meeting.zone),
            localEndsAt = LocalDateTime.ofInstant(meeting.endsAt, meeting.zone),
            mode = meeting.mode,
            location = meeting.location.takeIf { place },
            joinUrl = meeting.joinUrl.takeIf { place },
            joinDetails = meeting.joinDetails.takeIf { place },
            classificationId = meeting.classificationId,
            number = meeting.number,
            label = meeting.label,
            status = meeting.status,
            statusReason = meeting.statusReason,
            relation = relation,
            canManage = access.manages(meeting.forumId),
            guests = guests.map { MeetingGuestResponse(it.userId, names[it.userId].orEmpty(), it.note, it.addedAt) },
            version = meeting.version,
        )
    }

    /**
     * The forum's meetings starting in [from]–[to] (days in the tenant's zone, either open), in [statuses] (none: all),
     * by start time after [cursor]. Officers see drafts; the audience its meetings past DRAFT; `forum.read` all past DRAFT.
     */
    fun ofForum(
        forumId: UUID,
        from: LocalDate?,
        to: LocalDate?,
        statuses: Set<MeetingStatus>,
        cursor: String?,
    ): MeetingPageResponse {
        val forum = guard.requireForum(forumId)
        val officer = access.officer(forum.id)
        val allowed = statuses.ifEmpty { MeetingStatus.entries.toSet() } - setOfNotNull(MeetingStatus.DRAFT.takeUnless { officer })
        val window =
            MeetingWindow(
                from = from?.let { org.startOf(it) },
                to = to?.let { org.startOf(it.plusDays(1)) },
                statuses = allowed,
                after = cursor?.let { Cursors.decode(it) },
                limit = PAGE + 1,
            )
        val audienceOf = actorOrNull().takeUnless { officer || access.readsAll() }
        val rows = lists.page(forum.id, window, audienceOf)
        val page = rows.take(PAGE)
        val next = page.lastOrNull()?.takeIf { rows.size > PAGE }?.let { Cursors.encode(it.startsAt, it.id) }
        return MeetingPageResponse(summaries(page), next)
    }

    /**
     * "My meetings": those the actor is in the audience of (past DRAFT), and every meeting of the forums they are a
     * secretary or the chair of, ending on or after [from] (default 30 days ago), by start time; at most [MINE].
     */
    fun mine(from: LocalDate?): List<MeetingSummaryResponse> {
        val user = actorOrNull() ?: return emptyList()
        val since = org.startOf(from ?: org.today().minusDays(RECENT_DAYS))
        val officerOf = directory.rolesOf(user).keys.filter { access.officer(it) }
        val rows = (lists.ofAudienceFrom(user, since, MINE) + lists.ofForumsFrom(officerOf, since, MINE)).distinctBy { it.id }
        val mine = rows.sortedWith(compareBy({ it.startsAt }, { it.id })).take(MINE)
        val counts = agendas.counts(mine)
        return summaries(mine).map { s ->
            counts[s.id]?.let { (latest, seen, changed) ->
                s.copy(agendaRevision = latest, lastSeenRevision = seen, changesSinceSeen = changed)
            } ?: s
        }
    }

    private fun summaries(rows: List<MeetingRow>): List<MeetingSummaryResponse> {
        val forums = forums(rows)
        val relations = access.relations(rows)
        return rows.mapNotNull { meeting ->
            relations[meeting.id]?.let { relation ->
                val forum = forums.getValue(meeting.forumId)
                val place = relation != MeetingRelation.READER
                MeetingSummaryResponse(
                    id = meeting.id,
                    forumId = meeting.forumId,
                    forumName = forum.name,
                    forumCode = forum.code,
                    kind = meeting.kind,
                    title = meeting.title,
                    startsAt = meeting.startsAt,
                    endsAt = meeting.endsAt,
                    timeZone = meeting.zone.id,
                    localStartsAt = LocalDateTime.ofInstant(meeting.startsAt, meeting.zone),
                    localEndsAt = LocalDateTime.ofInstant(meeting.endsAt, meeting.zone),
                    mode = meeting.mode,
                    location = meeting.location.takeIf { place },
                    joinUrl = meeting.joinUrl.takeIf { place },
                    label = meeting.label,
                    status = meeting.status,
                    relation = relation,
                )
            }
        }
    }

    private fun forums(rows: List<MeetingRow>): Map<UUID, ForumRef> {
        val ids = rows.map { it.forumId }.toSet()
        val found = directory.forums(ids)
        return ids.associateWith { checkNotNull(found[it]) { "forum $it" } }
    }

    private fun OrgDirectory.startOf(day: LocalDate): Instant = day.atStartOfDay(zone()).toInstant()

    companion object {
        const val PAGE = 50
        const val MINE = 100
        const val RECENT_DAYS = 30L
    }
}
