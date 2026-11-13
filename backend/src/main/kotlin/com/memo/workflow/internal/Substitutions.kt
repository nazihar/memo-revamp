package com.memo.workflow.internal

import com.memo.audit.AuditLog
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.common.web.refuseIf
import com.memo.jooq.tables.records.WorkflowSubstitutionRecord
import com.memo.jooq.tables.references.WORKFLOW_SUBSTITUTION
import com.memo.org.OrgDirectory
import com.memo.workflow.WorkflowRefusedException
import com.memo.workflow.engine.Reassign
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A substitution: from [validFrom] until [validTo] (exclusive; open-ended when null), [substituteId] acts for [principalId]. */
data class SubstitutionRow(
    val id: UUID,
    val principalId: UUID,
    val substituteId: UUID,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
    val reason: String?,
    val createdBy: UUID,
) {
    fun inForceOn(date: LocalDate) = !validFrom.isAfter(date) && (validTo == null || validTo.isAfter(date))
}

/**
 * Substitutions (design E04 §4.5, ADR-0040): while one is in force, new tasks for the principal go to the substitute,
 * acting for them. At most one per principal at any date. An administrator can also move the principal's open tasks to
 * the substitute. Phase 1b adds self-service. Dates are the tenant's calendar dates. Audited. Inside a tenant transaction.
 */
@Component
class Substitutions(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val directory: OrgDirectory,
    private val tasks: TaskQueryStore,
    private val runner: WorkflowRunner,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    fun list(principal: UUID?): List<SubstitutionRow> =
        tenantDsl
            .current()
            .selectFrom(WORKFLOW_SUBSTITUTION)
            .where(principal?.let { WORKFLOW_SUBSTITUTION.PRINCIPAL_ID.eq(it) } ?: DSL.noCondition())
            .orderBy(WORKFLOW_SUBSTITUTION.VALID_FROM.desc(), WORKFLOW_SUBSTITUTION.ID)
            .fetch(::row)

    /** Who acts for [person] today, if anyone. */
    fun inForce(person: UUID): UUID? {
        val today = directory.today()
        return list(person).firstOrNull { it.inForceOn(today) }?.substituteId
    }

    fun create(
        principal: UUID,
        substitute: UUID,
        from: LocalDate,
        to: LocalDate?,
        reason: String?,
    ): SubstitutionRow {
        require(principal != substitute) { "Someone cannot substitute for themselves." }
        require(to == null || to.isAfter(from)) { "The end comes after the start." }
        lock(principal)
        val overlaps =
            list(principal).any { other ->
                other.validTo?.isAfter(from) != false &&
                    (to == null || to.isAfter(other.validFrom))
            }
        refuseIf(overlaps, "OVERLAP") { "This person already has a substitute for some of these dates." }
        val id = ids.next()
        tenantDsl
            .current()
            .insertInto(WORKFLOW_SUBSTITUTION)
            .set(WORKFLOW_SUBSTITUTION.ID, id)
            .set(WORKFLOW_SUBSTITUTION.TENANT_ID, tenantDsl.tenant().value)
            .set(WORKFLOW_SUBSTITUTION.PRINCIPAL_ID, principal)
            .set(WORKFLOW_SUBSTITUTION.SUBSTITUTE_ID, substitute)
            .set(WORKFLOW_SUBSTITUTION.VALID_FROM, from)
            .set(WORKFLOW_SUBSTITUTION.VALID_TO, to)
            .set(WORKFLOW_SUBSTITUTION.REASON, reason)
            .set(WORKFLOW_SUBSTITUTION.CREATED_BY, actor().userId)
            .set(WORKFLOW_SUBSTITUTION.CREATED_AT, OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
            .execute()
        record("workflow.substitution.created", id, principal, substitute)
        return list(principal).first { it.id == id }
    }

    /** Ends the substitution today (or cancels it when it has not started yet). */
    fun end(id: UUID): SubstitutionRow {
        val row = find(id)
        val today = directory.today()
        refuseIf(row.validTo?.isAfter(today) == false, "ENDED") { "This substitution has ended." }
        val dsl = tenantDsl.current()
        if (row.validFrom.isBefore(today)) {
            dsl
                .update(WORKFLOW_SUBSTITUTION)
                .set(WORKFLOW_SUBSTITUTION.VALID_TO, today)
                .where(WORKFLOW_SUBSTITUTION.ID.eq(id))
                .execute()
        } else {
            dsl.deleteFrom(WORKFLOW_SUBSTITUTION).where(WORKFLOW_SUBSTITUTION.ID.eq(id)).execute()
        }
        record("workflow.substitution.ended", id, row.principalId, row.substituteId)
        return row.copy(validTo = maxOf(today, row.validFrom))
    }

    /**
     * Moves the principal's open decision tasks to the substitute, acting for the principal (design E04 §4.5). Tasks the
     * engine refuses to move (the substitute already holds a step of that round, or is its initiator) stay; returns how
     * many moved.
     */
    fun apply(id: UUID): Int {
        val row = find(id)
        refuseIf(!row.inForceOn(directory.today()), "NOT_IN_FORCE") { "This substitution is not in force today." }
        val actor = actor()
        val open = tasks.open(row.principalId, blockedOnly = false, after = null, limit = MAX_APPLIED)
        val moved =
            open.count { task ->
                runCatching {
                    runner.onTask(task.taskId, actor) {
                        Reassign(it, task.taskId, row.substituteId, SUBSTITUTION, onBehalfOf = row.principalId)
                    }
                }.onFailure { if (it !is WorkflowRefusedException) throw it }
                    .isSuccess
            }
        record("workflow.substitution.applied", id, row.principalId, row.substituteId)
        return moved
    }

    private fun find(id: UUID): SubstitutionRow =
        tenantDsl
            .current()
            .selectFrom(WORKFLOW_SUBSTITUTION)
            .where(WORKFLOW_SUBSTITUTION.ID.eq(id))
            .fetchOne(::row)
            ?: throw SubstitutionNotFoundException()

    private fun lock(principal: UUID) {
        tenantDsl.current().fetch(
            "select pg_advisory_xact_lock(hashtextextended(?, 0))",
            "workflow.substitution:${tenantDsl.tenant()}:$principal",
        )
    }

    private fun record(
        action: String,
        id: UUID,
        principal: UUID,
        substitute: UUID,
    ) = audit.record(
        action,
        OBJECT_TYPE,
        id.toString(),
        after =
            mapOf(
                "principal" to principal.toString(),
                "substitute" to substitute.toString(),
            ),
    )

    private fun actor(): Actor.User =
        checkNotNull(ActorContext.current().actor as? Actor.User) {
            "substitutions are managed by signed-in users"
        }

    private fun row(r: WorkflowSubstitutionRecord) =
        SubstitutionRow(r.id, r.principalId, r.substituteId, r.validFrom, r.validTo, r.reason, r.createdBy)

    private companion object {
        const val OBJECT_TYPE = "workflow_substitution"
        const val SUBSTITUTION = "Substitution in force"
        const val MAX_APPLIED = 100
    }
}

/** No substitution by that id in the tenant: 404. */
class SubstitutionNotFoundException : RuntimeException("substitution not found")
