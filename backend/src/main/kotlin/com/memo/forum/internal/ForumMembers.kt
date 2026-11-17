package com.memo.forum.internal

import com.memo.audit.AuditLog
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.forum.ForumRole
import com.memo.org.OrgDirectory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/** A new membership: a person or a position, its role and its term (design E01 §3). */
data class MemberInput(
    val userId: UUID? = null,
    val positionId: UUID? = null,
    val role: ForumRole = ForumRole.MEMBER,
    /** Null: the role's default. */
    val voting: Boolean? = null,
    val attributes: Set<String> = emptySet(),
    val alternateUserId: UUID? = null,
    /** Null: today. */
    val startsOn: LocalDate? = null,
    /** Null: from the type's usual term, or open-ended. */
    val termEndsOn: LocalDate? = null,
    val authority: String? = null,
)

/** What can change on a membership in place. */
data class MemberEdit(
    val voting: Boolean,
    val attributes: Set<String>,
    val alternateUserId: UUID?,
    val termEndsOn: LocalDate?,
    val authority: String?,
)

/** A membership change's outcome; [beyondMaxTerms] warns that a renewal passed the type's most-consecutive-terms limit. */
data class MemberChange(
    val row: ForumMemberRow,
    val beyondMaxTerms: Boolean = false,
)

/**
 * Terms of office (ADR-0056, design E01 §1): one row per term; role changes and renewals end a row and start the next.
 * Every change runs under a lock on the forum row ([MemberRules]), so `ONE_CHAIR` and `ALREADY_MEMBER` hold under
 * concurrency; is audited with before and after; and tells the chair, the secretaries and the member concerned.
 */
@Service
class ForumMembers(
    private val store: ForumMemberStore,
    private val rules: MemberRules,
    private val types: ForumTypeVersionStore,
    private val access: ForumAccess,
    private val org: OrgDirectory,
    private val notifier: ForumNotifier,
    private val audit: AuditLog,
) {
    fun add(
        forumId: UUID,
        input: MemberInput,
    ): MemberChange {
        val forum = rules.lockedActive(forumId)
        access.requireMemberChange(forum, listOf(input.role), person = input.userId)
        val startsOn = input.startsOn ?: org.today()
        val values =
            MemberValues(
                kind = if (input.positionId != null) MemberKind.POSITION else MemberKind.PERSON,
                userId = input.userId,
                positionId = input.positionId,
                role = input.role,
                voting = input.voting ?: input.role.votesByDefault,
                attributes = input.attributes,
                alternateUserId = input.alternateUserId,
                startsOn = startsOn,
                termEndsOn = input.termEndsOn ?: usualEnd(forum, startsOn),
                termNumber = 1,
                authority = input.authority?.trim()?.ifEmpty { null },
                previousId = null,
            )
        rules.require(forum, values, except = null)
        val row = checkNotNull(store.member(store.insertMember(forum.id, values, actorOrNull())))
        return done("forum.member.added", forum, row, before = null)
    }

    fun edit(
        forumId: UUID,
        memberId: UUID,
        version: Long,
        edit: MemberEdit,
    ): MemberChange {
        val forum = rules.lockedActive(forumId)
        val before = open(forum, memberId)
        val after =
            before.copy(
                voting = edit.voting,
                attributes = edit.attributes,
                alternateUserId = edit.alternateUserId,
                termEndsOn = edit.termEndsOn,
                authority = edit.authority?.trim()?.ifEmpty { null },
            )
        rules.require(forum, after.values(), except = before.id)
        if (!store.editMember(after, version)) throw ConcurrentModificationException("forum member $memberId", version)
        return done("forum.member.updated", forum, checkNotNull(store.member(memberId)), before)
    }

    fun end(
        forumId: UUID,
        memberId: UUID,
        version: Long,
        on: LocalDate?,
        reason: EndReason,
        note: String?,
    ): MemberChange {
        val forum = rules.locked(forumId)
        val before = rules.row(forum, memberId)
        access.requireMemberChange(forum, listOf(before.role), before)
        refuseIf(reason in SYSTEM_REASONS, "WRONG_REASON") { "Use renew, change role or withdraw for that." }
        val endedOn = on ?: org.today()
        requireNoProblems(
            listOfNotNull(
                FieldProblem("endedOn", "BEFORE_START", "A membership cannot end before it starts.")
                    .takeIf { endedOn.isBefore(before.startsOn) },
                FieldProblem("note", "LENGTH", "At most ${MemberRules.MAX_TEXT} characters.")
                    .takeIf { (note?.length ?: 0) > MemberRules.MAX_TEXT },
            ),
        )
        if (!store.endMember(before, endedOn, reason, note?.trim()?.ifEmpty { null }, version)) {
            throw ConcurrentModificationException("forum member $memberId", version)
        }
        return done("forum.member.ended", forum, checkNotNull(store.member(memberId)), before)
    }

    /** Ends the row at its planned term end and starts the next term the day after (design E01 §1). */
    fun renew(
        forumId: UUID,
        memberId: UUID,
        termEndsOn: LocalDate?,
        authority: String?,
    ): MemberChange {
        val forum = rules.lockedActive(forumId)
        val before = open(forum, memberId)
        val currentEnd = before.termEndsOn
        refuseIf(currentEnd == null, "NO_TERM") { "This membership has no term end to renew from." }
        val startsOn = checkNotNull(currentEnd).plusDays(1)
        val next =
            before.values().copy(
                startsOn = startsOn,
                termEndsOn = termEndsOn ?: usualEnd(forum, startsOn),
                termNumber = before.termNumber + 1,
                authority = authority?.trim()?.ifEmpty { null } ?: before.authority,
                previousId = before.id,
            )
        store.endMember(before, currentEnd, EndReason.RENEWED, null, null)
        rules.require(forum, next, except = before.id)
        val row = checkNotNull(store.member(store.insertMember(forum.id, next, actorOrNull())))
        val limit =
            types
                .published(forum.typeId)
                ?.settings
                ?.termPolicy
                ?.maxTerms
        return done("forum.member.renewed", forum, row, before).copy(beyondMaxTerms = limit != null && row.termNumber > limit)
    }

    /** Ends the row the day before [from] and starts the same term in [role] from [from]. */
    fun changeRole(
        forumId: UUID,
        memberId: UUID,
        role: ForumRole,
        from: LocalDate?,
        voting: Boolean?,
    ): MemberChange {
        val forum = rules.lockedActive(forumId)
        val before = open(forum, memberId, role)
        refuseIf(role == before.role, "SAME_ROLE") { "The member already has this role." }
        val start = from ?: org.today()
        refuseIf(!start.isAfter(before.startsOn), "TOO_EARLY") { "Withdraw a membership that has not started and add it again instead." }
        val next = before.values().copy(role = role, voting = voting ?: role.votesByDefault, startsOn = start, previousId = before.id)
        store.endMember(before, start.minusDays(1), EndReason.ROLE_CHANGED, null, null)
        rules.require(forum, next, except = before.id)
        val row = checkNotNull(store.member(store.insertMember(forum.id, next, actorOrNull())))
        return done("forum.member.role_changed", forum, row, before)
    }

    /** A row that has not started yet is withdrawn: kept, ending the day before its start. */
    fun withdraw(
        forumId: UUID,
        memberId: UUID,
    ): MemberChange {
        val forum = rules.locked(forumId)
        val before = rules.row(forum, memberId)
        access.requireMemberChange(forum, listOf(before.role), before)
        val upcoming = before.state(org.today()) == MemberState.UPCOMING
        refuseIf(!upcoming, "NOT_UPCOMING") { "Only a membership that has not started can be withdrawn." }
        store.endMember(before, before.startsOn.minusDays(1), EndReason.WITHDRAWN, null, null)
        return done("forum.member.withdrawn", forum, checkNotNull(store.member(memberId)), before)
    }

    /** Copies [source] into [target] from [from] as a first term (clone). */
    fun copyInto(
        target: ForumRow,
        source: ForumMemberRow,
        from: LocalDate,
    ) {
        val values = source.values().copy(startsOn = from, termEndsOn = usualEnd(target, from), termNumber = 1, previousId = null)
        val row = checkNotNull(store.member(store.insertMember(target.id, values, actorOrNull())))
        audit.record("forum.member.added", OBJECT_TYPE, row.id.toString(), after = audited(row))
    }

    /** A row the actor may change ([role]: the role it moves to, if any) and that has not ended. */
    private fun open(
        forum: ForumRow,
        memberId: UUID,
        role: ForumRole? = null,
    ): ForumMemberRow {
        val row = rules.row(forum, memberId)
        access.requireMemberChange(forum, listOfNotNull(row.role, role), row)
        refuseIf(row.ended, "MEMBER_ENDED") { "This membership has ended; add a new one instead." }
        return row
    }

    private fun usualEnd(
        forum: ForumRow,
        startsOn: LocalDate,
    ): LocalDate? =
        types
            .published(forum.typeId)
            ?.settings
            ?.termPolicy
            ?.termMonths
            ?.let { startsOn.plusMonths(it.toLong()).minusDays(1) }

    private fun done(
        action: String,
        forum: ForumRow,
        row: ForumMemberRow,
        before: ForumMemberRow?,
    ): MemberChange {
        audit.record(action, OBJECT_TYPE, row.id.toString(), before = before?.let { audited(it) }, after = audited(row))
        notifier.membershipChanged(forum, row)
        return MemberChange(row)
    }

    companion object {
        const val OBJECT_TYPE = "forum_member"
        private val SYSTEM_REASONS = setOf(EndReason.RENEWED, EndReason.ROLE_CHANGED, EndReason.WITHDRAWN)

        fun audited(row: ForumMemberRow): Map<String, Any?> =
            mapOf(
                "forumId" to row.forumId.toString(),
                "userId" to row.userId?.toString(),
                "positionId" to row.positionId?.toString(),
                "role" to row.role.name,
                "voting" to row.voting,
                "attributes" to row.attributes.sorted(),
                "alternateUserId" to row.alternateUserId?.toString(),
                "startsOn" to row.startsOn.toString(),
                "termEndsOn" to row.termEndsOn?.toString(),
                "endedOn" to row.endedOn?.toString(),
                "endReason" to row.endReason?.name,
                "termNumber" to row.termNumber,
                "authority" to row.authority,
                "previousId" to row.previousId?.toString(),
            )
    }
}

internal fun ForumMemberRow.values() =
    MemberValues(
        kind,
        userId,
        positionId,
        role,
        voting,
        attributes,
        alternateUserId,
        startsOn,
        termEndsOn,
        termNumber,
        authority,
        previousId,
    )
