package com.memo.memo.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.Clearances
import com.memo.common.authz.RoleHolders
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.identity.UserStatus
import com.memo.identity.UserSummaries
import com.memo.org.OrgDirectory
import com.memo.workflow.RouteEntry
import com.memo.workflow.WorkflowAuthority
import com.memo.workflow.Workflows
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * A memo's route (design E01 §2, E04 §4.7, invariant 11): the initiator's ordered entries (people, positions, groups),
 * replaced as a whole while they hold the memo. Every person must be another active user cleared for the memo's
 * classification, at most once; positions must exist; the memo type may restrict who is on it; and the route may not be
 * longer than the tenant's limit. The same rules are checked again at submit and resubmit, with the DoA when the memo
 * type asks for it. Inside a tenant transaction.
 */
@Service
class MemoRoutes(
    private val memos: Memos,
    private val routes: MemoRouteStore,
    private val types: MemoTypeStore,
    private val users: UserSummaries,
    private val clearances: Clearances,
    private val interests: MemoInterestStore,
    private val directory: OrgDirectory,
    private val roles: RoleHolders,
    private val workflows: Workflows,
    private val authority: WorkflowAuthority,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    fun route(memo: UUID): List<RouteEntry> = routes.route(memo)

    fun replace(
        id: UUID,
        expectedVersion: Long,
        entries: List<RouteEntry>,
    ): Memo {
        val memo = memos.own(id)
        refuseIf(!memo.status.editable, "WRONG_STATE") { "The route can be changed only while the memo is a draft or returned to you." }
        val problems = problems(memo, entries, allowEmpty = true)
        if (problems.isNotEmpty()) throw MemoRejectedException(problems)
        val before = routes.route(id)
        if (before == entries) return memo
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        if (!routes.replace(id, expectedVersion, entries, now)) throw ConcurrentModificationException("memo $id", expectedVersion)
        audit.record(
            "memo.route.updated",
            Memos.OBJECT_TYPE,
            id.toString(),
            before = mapOf("approvers" to before.map(::token)),
            after = mapOf("approvers" to entries.map(::token)),
        )
        return memos.get(id)
    }

    /**
     * What is wrong with [route] for [memo]: `route` for the list as a whole (`empty`, `too-long`), `route[i]` for an
     * entry (`invalid`, `duplicate`, `self`, `interested`, `approver-inactive`, `approver-not-cleared`,
     * `position-unknown`, `not-allowed`). A draft may save an empty route.
     */
    fun problems(
        memo: Memo,
        route: List<RouteEntry>,
        allowEmpty: Boolean = false,
    ): List<FieldProblem> {
        val max = workflows.maxApprovers()
        val whole =
            listOfNotNull(
                FieldProblem("route", "empty", "Add at least one approver.").takeIf { route.isEmpty() && !allowEmpty },
                FieldProblem("route", "too-long", "A route can have at most $max approvers.").takeIf { route.size > max },
            )
        if (whole.isNotEmpty() || route.isEmpty()) return whole
        val checks = Checks(memo, route)
        return route.mapIndexedNotNull { index, entry ->
            checks.problem(index, entry)?.let { FieldProblem("route[$index]", it, MESSAGES.getValue(it)) }
        }
    }

    /**
     * Whether [route] gives the DoA for [memo] its approvals (design E04 §4.7), when the memo type asks: one problem per
     * group of the matching rule that the route does not cover, naming the people who could.
     */
    fun authority(
        memo: Memo,
        type: MemoType?,
        route: List<RouteEntry>,
    ): List<FieldProblem> {
        if (type?.doaCheck != true) return emptyList()
        val shortfalls = authority.shortfalls(MemoWorkflowSubject.TYPE, memo.id, memo.initiatorId, route)
        val names = users.of(shortfalls.flatMap { it.people }.toSet()).mapValues { it.value.displayName }
        return shortfalls.map { gap ->
            val who =
                gap.people
                    .mapNotNull(names::get)
                    .sorted()
                    .joinToString(", ")
                    .ifEmpty { "nobody holds it now" }
            FieldProblem("route", "doa-missing", "Authority group ${gap.group + 1} needs ${gap.needed} approval(s) from: $who.")
        }
    }

    /** What one validation needs to know about the people and positions on a route, read once. */
    private inner class Checks(
        private val memo: Memo,
        private val route: List<RouteEntry>,
    ) {
        private val people = route.flatMap { it.people }.toSet()
        private val positions = route.flatMap { it.positions }.toSet()
        private val summaries = users.of(people)
        private val ranks = clearances.rankOf(people)
        private val interested = interests.of(memo.id)
        private val known = directory.positions(positions)
        private val holders = directory.holders(positions)
        private val policy = types.find(memo.typeId)?.allowedApprovers ?: AllowedApprovers()

        fun problem(
            index: Int,
            entry: RouteEntry,
        ): String? =
            when {
                !entry.wellFormed -> "invalid"
                repeated(index, entry) -> "duplicate"
                memo.initiatorId in entry.people || entry.positions.any { holders[it] == memo.initiatorId } -> "self"
                entry.people.any { it in interested } -> "interested"
                entry.people.any { summaries[it]?.status != UserStatus.ACTIVE } -> "approver-inactive"
                entry.people.any { ranks.getValue(it) < memo.classificationRank } -> "approver-not-cleared"
                entry.positions.any { it !in known } -> "position-unknown"
                !policy.anyone && !allowed(entry) -> "not-allowed"
                else -> null
            }

        private fun repeated(
            index: Int,
            entry: RouteEntry,
        ): Boolean {
            val earlier = route.take(index)
            return entry.people.toSet().size != entry.people.size ||
                entry.positions.toSet().size != entry.positions.size ||
                entry.people.any { person -> earlier.any { person in it.people } } ||
                entry.positions.any { position -> earlier.any { position in it.positions } }
        }

        /** The memo type's allowed approvers: named people, holders of the positions, holders of the roles. */
        private fun allowed(entry: RouteEntry): Boolean =
            entry.people.all(::personAllowed) &&
                entry.positions.all { it in policy.positions || holders[it]?.let(::personAllowed) == true }

        private fun personAllowed(person: UUID): Boolean {
            val position = directory.positionsOf(listOf(person))[person]
            return person in policy.people ||
                position?.id in policy.positions ||
                policy.roles.any { person in roles.holders(it, position?.unitId) }
        }
    }

    private companion object {
        val MESSAGES =
            mapOf(
                "invalid" to "An entry is one person, one position, or a group of two or more of them.",
                "duplicate" to "This person or position is already on the route.",
                "self" to "You cannot approve your own memo.",
                "approver-inactive" to "This person cannot sign in at the moment.",
                "approver-not-cleared" to "This person is not cleared for the memo's classification.",
                "interested" to "This person has declared an interest in the memo.",
                "position-unknown" to "This position does not exist.",
                "not-allowed" to "This memo type does not allow this approver.",
            )

        /** An entry for the audit log, ids only. */
        fun token(entry: RouteEntry): String =
            entry.userId?.toString()
                ?: entry.positionId?.let { "position:$it" }
                ?: entry.group.let { g -> "group:${g?.mode}:" + g?.members?.joinToString("+") { token(it) } }
    }
}
