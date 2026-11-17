package com.memo.forum.internal

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.forum.ForumRole
import com.memo.forum.ForumStatus
import com.memo.identity.UserSummaries
import com.memo.org.OrgDirectory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Responses of the committee endpoints (design E01 §3), one batch of name lookups per call. Position seats show the
 * holder on the date asked; rows carry their derived state and the days left in a term when a reminder is due.
 */
@Component
class ForumViews(
    private val store: ForumStore,
    private val memberStore: ForumMemberStore,
    private val types: ForumTypeStore,
    private val versions: ForumTypeVersionStore,
    private val access: ForumAccess,
    private val users: UserSummaries,
    private val org: OrgDirectory,
) {
    fun forums(forums: List<ForumRow>): List<ForumResponse> {
        val today = org.today()
        val rows = forums.associate { it.id to memberStore.members(it.id) }
        val names = names(rows.values.flatten().mapNotNull { it.userId } + holders(rows.values.flatten(), today).values)
        val typeNames = types.all().associate { it.id to (versions.published(it.id)?.settings?.name ?: it.code) }
        val forumNames = store.all().associate { it.id to it.name }
        val managesAll = access.managesAll()
        return forums.map { forum ->
            val all = rows.getValue(forum.id)
            val inOffice = all.filter { it.inOffice(today) }
            val seats = holders(inOffice, today)

            fun person(row: ForumMemberRow) = (row.userId ?: seats[row.positionId])?.let { PersonRef(it, names[it].orEmpty()) }
            val reminder = reminderDays(forum)
            val mine = access.ownRows(forum, today).firstOrNull { it.inOffice(today) }
            ForumResponse(
                id = forum.id,
                key = forum.key,
                code = forum.code,
                name = forum.name,
                shortName = forum.shortName,
                typeId = forum.typeId,
                typeName = typeNames[forum.typeId].orEmpty(),
                parentId = forum.parentId,
                parentName = forum.parentId?.let { forumNames[it] },
                classificationId = forum.classificationId,
                status = forum.status,
                dissolvedOn = forum.dissolvedOn,
                dissolvedReason = forum.dissolvedReason,
                chair = inOffice.firstOrNull { it.role == ForumRole.CHAIR }?.let(::person),
                secretaries = inOffice.filter { it.role == ForumRole.SECRETARY }.mapNotNull(::person),
                memberCount = inOffice.size,
                termWarnings = all.count { warn(it, today, reminder) },
                myRole = mine?.role,
                canManageMembers = forum.status == ForumStatus.ACTIVE && (managesAll || mine?.role == ForumRole.SECRETARY),
                canManage = managesAll,
                version = forum.version,
            )
        }
    }

    fun forum(forum: ForumRow): ForumResponse = forums(listOf(forum)).single()

    /** Members in office on [on] (default today), or every row when [history]. */
    fun members(
        forum: ForumRow,
        on: LocalDate?,
        history: Boolean,
    ): List<MemberResponse> {
        val date = on ?: org.today()
        val all = memberStore.members(forum.id)
        val newestFirst = compareByDescending<ForumMemberRow> { it.startsOn }.thenByDescending { it.createdAt }
        val rows = if (history) all.sortedWith(newestFirst) else all.filter { it.inOffice(date) }
        return responses(forum, rows, date)
    }

    fun row(
        forumId: UUID,
        memberId: UUID,
    ): ForumMemberRow = memberStore.member(memberId)?.takeIf { it.forumId == forumId } ?: throw ForumNotFoundException("member")

    fun change(
        forum: ForumRow,
        change: MemberChange,
    ): MemberChangeResponse = MemberChangeResponse(responses(forum, listOf(change.row), org.today()).single(), change.beyondMaxTerms)

    /** The user's committees: each forum with their current or upcoming row, else their latest row. */
    fun mine(): List<MyForumResponse> {
        val user = (ActorContext.current().actor as? Actor.User)?.userId ?: return emptyList()
        val today = org.today()
        val position = org.positionsOf(listOf(user), today)[user]?.id
        val rows = memberStore.rowsOf(user, setOfNotNull(position))
        return rows
            .groupBy { it.forumId }
            .mapNotNull { (forumId, forumRows) ->
                val forum = store.find(forumId) ?: return@mapNotNull null
                val current = forumRows.filter { it.state(today) == MemberState.IN_OFFICE || it.state(today) == MemberState.UPCOMING }
                val row = current.minByOrNull { it.startsOn } ?: forumRows.maxBy { it.startsOn }
                MyForumResponse(
                    forumId = forum.id,
                    name = forum.name,
                    code = forum.code,
                    status = forum.status,
                    role = row.role,
                    voting = row.voting,
                    attributes = row.attributes.sorted(),
                    state = row.state(today),
                    startsOn = row.startsOn,
                    termEndsOn = row.termEndsOn,
                    endedOn = row.endedOn,
                    termNumber = row.termNumber,
                    canOpen = access.visible(forum),
                )
            }.sortedWith(compareBy<MyForumResponse> { it.state != MemberState.IN_OFFICE }.thenBy { it.name })
    }

    private fun responses(
        forum: ForumRow,
        rows: List<ForumMemberRow>,
        date: LocalDate,
    ): List<MemberResponse> {
        val seats = holders(rows, date)
        val names = names(rows.mapNotNull { it.userId } + rows.mapNotNull { it.alternateUserId } + seats.values)
        val titles = org.positions(rows.mapNotNull { it.positionId }).mapValues { it.value.title }
        val today = org.today()
        val reminder = reminderDays(forum)
        return rows.map { row ->
            val person = row.userId ?: seats[row.positionId]
            MemberResponse(
                id = row.id,
                kind = row.kind,
                person = person?.let { PersonRef(it, names[it].orEmpty()) },
                positionId = row.positionId,
                positionTitle = row.positionId?.let { titles[it] },
                role = row.role,
                voting = row.voting,
                attributes = row.attributes.sorted(),
                alternate = row.alternateUserId?.let { PersonRef(it, names[it].orEmpty()) },
                startsOn = row.startsOn,
                termEndsOn = row.termEndsOn,
                endedOn = row.endedOn,
                endReason = row.endReason,
                endNote = row.endNote,
                termNumber = row.termNumber,
                authority = row.authority,
                state = row.state(today),
                termEndsInDays =
                    row.termEndsOn
                        ?.takeIf { !row.ended }
                        ?.let { ChronoUnit.DAYS.between(today, it).toInt() }
                        ?.takeIf { it in 0..reminder },
                version = row.version,
            )
        }
    }

    /** A row needing attention: in office with a term ending within [reminder] days, or past its term and not renewed. */
    private fun warn(
        row: ForumMemberRow,
        today: LocalDate,
        reminder: Int,
    ): Boolean {
        val end = row.termEndsOn
        val due = end != null && !end.isAfter(today.plusDays(reminder.toLong()))
        return due && !row.ended && !row.startsOn.isAfter(today)
    }

    private fun reminderDays(forum: ForumRow): Int =
        versions
            .published(forum.typeId)
            ?.settings
            ?.termPolicy
            ?.reminderDays
            ?.maxOrNull() ?: 0

    private fun holders(
        rows: List<ForumMemberRow>,
        date: LocalDate,
    ): Map<UUID, UUID> = org.holders(rows.mapNotNull { it.positionId }.toSet(), date)

    private fun names(ids: Collection<UUID>): Map<UUID, String> = users.of(ids.toSet()).mapValues { it.value.displayName }
}
