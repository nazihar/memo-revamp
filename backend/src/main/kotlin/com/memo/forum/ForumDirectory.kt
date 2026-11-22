package com.memo.forum

import com.memo.forum.internal.ForumMemberRow
import com.memo.forum.internal.ForumMemberStore
import com.memo.forum.internal.ForumRow
import com.memo.forum.internal.ForumStore
import com.memo.forum.internal.MemberKind
import com.memo.org.OrgDirectory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

enum class ForumStatus { ACTIVE, DISSOLVED }

/** A forum as other modules see it (ADR-0056). */
data class ForumRef(
    val id: UUID,
    val key: String,
    val code: String,
    val name: String,
    val shortName: String,
    val typeId: UUID,
    val parentId: UUID?,
    /** Null: the type's default, else the tenant's lowest level. */
    val classificationId: UUID?,
    val status: ForumStatus,
)

/** A member in office on a date, with a position seat resolved to its holder (absent when the seat is vacant). */
data class ForumSeat(
    val memberId: UUID,
    val forumId: UUID,
    /** The person: the member, or the holder of the position seat on the date. */
    val userId: UUID,
    /** Set for a position seat (ex officio). */
    val positionId: UUID?,
    val role: ForumRole,
    val voting: Boolean,
    val attributes: Set<String>,
    val alternateUserId: UUID?,
    val startsOn: LocalDate,
    val termEndsOn: LocalDate?,
    val termNumber: Int,
    /** The last day of the seat: the earlier of its end and its planned term end; null when open-ended. */
    val until: LocalDate?,
)

/**
 * Who sits on which forum, on which date (ADR-0056): the published API later epics build their access on. Inside a
 * tenant transaction. Rows are cached per forum and evicted on every change; position seats are resolved through the org
 * directory, which keeps its own cache, so a reorganisation needs no eviction here.
 */
@Component
class ForumDirectory(
    private val store: ForumStore,
    private val memberStore: ForumMemberStore,
    private val org: OrgDirectory,
) {
    fun forum(id: UUID): ForumRef? = store.find(id)?.ref()

    /** The forums of [ids] that exist, in one query (a list of meetings across committees; a tenant has tens of forums). */
    fun forums(ids: Collection<UUID>): Map<UUID, ForumRef> =
        if (ids.isEmpty()) emptyMap() else store.all().filter { it.id in ids }.associate { it.id to it.ref() }

    /** Every forum of the tenant, dissolved ones included (the decision register's scope for oversight, E08 T2). */
    fun all(): List<ForumRef> = store.all().map { it.ref() }

    fun forumByKey(key: String): ForumRef? = store.findByKey(key)?.ref()

    /** The members in office on [date] (today in the tenant's zone by default). */
    fun membersOn(
        forumId: UUID,
        date: LocalDate = org.today(),
    ): List<ForumSeat> = seats(memberStore.members(forumId).filter { it.inOffice(date) }, date)

    /** The roles [userId] holds on [date], by forum, as a person or as the holder of a position seat. */
    fun rolesOf(
        userId: UUID,
        date: LocalDate = org.today(),
    ): Map<UUID, ForumRole> {
        val positions = org.positionsOf(listOf(userId), date)[userId]?.let { setOf(it.id) }.orEmpty()
        return memberStore
            .rowsOf(userId, positions)
            .filter { it.inOffice(date) }
            .associate { it.forumId to it.role }
    }

    fun isSecretary(
        userId: UUID,
        forumId: UUID,
        date: LocalDate = org.today(),
    ): Boolean = rolesOf(userId, date)[forumId] == ForumRole.SECRETARY

    private fun seats(
        rows: List<ForumMemberRow>,
        date: LocalDate,
    ): List<ForumSeat> {
        val holders = org.holders(rows.mapNotNull { it.positionId }, date)
        return rows.mapNotNull { row ->
            val person = if (row.kind == MemberKind.POSITION) holders[row.positionId] else row.userId
            person?.let {
                ForumSeat(
                    memberId = row.id,
                    forumId = row.forumId,
                    userId = it,
                    positionId = row.positionId,
                    role = row.role,
                    voting = row.voting,
                    attributes = row.attributes,
                    alternateUserId = row.alternateUserId,
                    startsOn = row.startsOn,
                    termEndsOn = row.termEndsOn,
                    termNumber = row.termNumber,
                    until = row.lastDay,
                )
            }
        }
    }
}

internal fun ForumRow.ref() = ForumRef(id, key, code, name, shortName, typeId, parentId, classificationId, status)
