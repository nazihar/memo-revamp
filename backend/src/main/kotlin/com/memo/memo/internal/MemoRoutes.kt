package com.memo.memo.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.Clearances
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.identity.UserStatus
import com.memo.identity.UserSummaries
import com.memo.workflow.Workflows
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * A memo's route (design E01 §2, invariant 11): the initiator's ordered approvers, replaced as a whole while they hold the
 * memo. Every approver must be another active user cleared for the memo's classification, at most once, and the route
 * may not be longer than the tenant's limit. The same rules are checked again at submit and resubmit. Inside a tenant
 * transaction.
 */
@Service
class MemoRoutes(
    private val memos: Memos,
    private val routes: MemoRouteStore,
    private val users: UserSummaries,
    private val clearances: Clearances,
    private val interests: MemoInterestStore,
    private val workflows: Workflows,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    fun route(memo: UUID): List<UUID> = routes.route(memo)

    fun replace(
        id: UUID,
        expectedVersion: Long,
        approvers: List<UUID>,
    ): Memo {
        val memo = memos.own(id)
        refuseIf(!memo.status.editable, "WRONG_STATE") { "The route can be changed only while the memo is a draft or returned to you." }
        val problems = problems(memo, approvers, allowEmpty = true)
        if (problems.isNotEmpty()) throw MemoRejectedException(problems)
        val before = routes.route(id)
        if (before == approvers) return memo
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        if (!routes.replace(id, expectedVersion, approvers, now)) throw ConcurrentModificationException("memo $id", expectedVersion)
        audit.record(
            "memo.route.updated",
            Memos.OBJECT_TYPE,
            id.toString(),
            before = mapOf("approvers" to before.map { it.toString() }),
            after = mapOf("approvers" to approvers.map { it.toString() }),
        )
        return memos.get(id)
    }

    /**
     * What is wrong with [approvers] for [memo]: `route` for the list as a whole (`empty`, `too-long`), `route[i]` for an
     * entry (`duplicate`, `self`, `approver-inactive`, `approver-not-cleared`). A draft may save an empty route.
     */
    fun problems(
        memo: Memo,
        approvers: List<UUID>,
        allowEmpty: Boolean = false,
    ): List<FieldProblem> {
        val max = workflows.maxApprovers()
        val whole =
            listOfNotNull(
                FieldProblem("route", "empty", "Add at least one approver.").takeIf { approvers.isEmpty() && !allowEmpty },
                FieldProblem("route", "too-long", "A route can have at most $max approvers.").takeIf { approvers.size > max },
            )
        if (whole.isNotEmpty() || approvers.isEmpty()) return whole
        val people = users.of(approvers.toSet())
        val ranks = clearances.rankOf(approvers.toSet())
        val interested = interests.of(memo.id)
        return approvers.mapIndexedNotNull { index, approver ->
            val code =
                when {
                    approvers.indexOf(approver) != index -> "duplicate"
                    approver == memo.initiatorId -> "self"
                    approver in interested -> "interested"
                    people[approver]?.status != UserStatus.ACTIVE -> "approver-inactive"
                    ranks.getValue(approver) < memo.classificationRank -> "approver-not-cleared"
                    else -> null
                }
            code?.let { FieldProblem("route[$index]", it, MESSAGES.getValue(it)) }
        }
    }

    private companion object {
        val MESSAGES =
            mapOf(
                "duplicate" to "This person is already on the route.",
                "self" to "You cannot approve your own memo.",
                "approver-inactive" to "This person cannot sign in at the moment.",
                "approver-not-cleared" to "This person is not cleared for the memo's classification.",
                "interested" to "This person has declared an interest in the memo.",
            )
    }
}
