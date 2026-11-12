package com.memo.workflow.internal

import com.memo.audit.AuditLog
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.jooq.tables.references.WORKFLOW_DOA_VERSION
import com.memo.org.OrgDirectory
import com.memo.workflow.WorkflowSubject
import com.memo.workflow.definition.Attribute
import com.memo.workflow.definition.Conditions
import com.memo.workflow.engine.Remarks
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/** No DoA version by that number in the tenant: 404. */
class DoaNotFoundException : RuntimeException("doa version not found")

/** The DoA (or the request) does not pass validation: 422 `validation`, reason `DOA_INVALID`. */
class DoaInvalidException(
    val problems: List<FieldProblem>,
) : RuntimeException("doa invalid: ${problems.joinToString { "${it.field}:${it.code}" }}")

/** A DoA version and its rules. */
data class LoadedDoa(
    val version: DoaVersionRow,
    val rules: List<DoaRule>,
)

/**
 * What a DoA version must satisfy (design E04 §3): known subject types, units, positions and roles; conditions that
 * compile against the subject's attributes; bounded rules, groups and members. Inside a tenant transaction.
 */
@Component
class DoaChecks(
    private val checks: DefinitionChecks,
    private val directory: OrgDirectory,
    subjects: List<WorkflowSubject>,
) {
    private val attributes = subjects.associate { subject -> subject.type to subject.attributes.map { Attribute(it.key, it.value) } }

    /** Throws [DoaInvalidException] listing what is wrong with [rules], if anything. */
    fun require(rules: List<DoaRule>) {
        val problems = problems(rules)
        if (problems.isNotEmpty()) throw DoaInvalidException(problems)
    }

    fun problems(rules: List<DoaRule>): List<FieldProblem> =
        listOfNotNull(FieldProblem("rules", "TOO_MANY", "At most $MAX_RULES rules.").takeIf { rules.size > MAX_RULES }) +
            rules.flatMapIndexed { i, rule -> rule("rules/$i", rule) }

    private fun rule(
        at: String,
        rule: DoaRule,
    ): List<FieldProblem> {
        val declared = attributes[rule.subjectType]
        val condition = rule.condition?.let { text -> declared?.let { Conditions.compile(text, it).second } }
        return listOfNotNull(
            FieldProblem("$at/name", "LENGTH", "A name of 1 to 200 characters.").takeIf { rule.name.isBlank() || rule.name.length > NAME },
            FieldProblem("$at/subjectType", "UNKNOWN_SUBJECT", "No subject type '${rule.subjectType}'.").takeIf { declared == null },
            rule.unitId?.takeUnless(directory::unitExists)?.let { FieldProblem("$at/unitId", "UNKNOWN_UNIT", "No unit $it.") },
            condition?.let { FieldProblem("$at/condition", "CONDITION_INVALID", it.message) },
            FieldProblem(
                "$at/groups",
                "COUNT",
                "1 to $MAX_GROUPS groups.",
            ).takeIf { rule.groups.isEmpty() || rule.groups.size > MAX_GROUPS },
        ) + rule.groups.flatMapIndexed { g, group -> group("$at/groups/$g", group) }
    }

    private fun group(
        at: String,
        group: DoaRuleGroup,
    ): List<FieldProblem> =
        listOfNotNull(
            FieldProblem("$at/needed", "RANGE", "1 to $MAX_NEEDED approvals.").takeIf { group.needed !in 1..MAX_NEEDED },
            FieldProblem("$at/of", "COUNT", "1 to $MAX_MEMBERS members.").takeIf { group.of.isEmpty() || group.of.size > MAX_MEMBERS },
        ) + group.of.flatMapIndexed { m, member -> member("$at/of/$m", member) }

    private fun member(
        at: String,
        member: DoaMember,
    ): List<FieldProblem> {
        val assignee = runCatching { DoaMembers.assignee(member) }.getOrNull()
        val references = checks.references()
        return when {
            assignee == null -> {
                listOf(FieldProblem(at, "MEMBER", "A position (positionId), or a role (roleId) in a unit (\"initiator\" or a unit id)."))
            }

            else -> {
                listOfNotNull(
                    assignee.positionId
                        ?.takeUnless(
                            references::positionExists,
                        )?.let { FieldProblem("$at/positionId", "UNKNOWN_POSITION", "No position $it.") },
                    assignee.roleId?.takeUnless(references::roleExists)?.let { FieldProblem("$at/roleId", "UNKNOWN_ROLE", "No role $it.") },
                    assignee.unit?.takeUnless(references::unitExists)?.let { FieldProblem("$at/unit", "UNKNOWN_UNIT", "No unit $it.") },
                )
            }
        }
    }

    private companion object {
        const val NAME = 200
        const val MAX_RULES = 200
        const val MAX_GROUPS = 10
        const val MAX_MEMBERS = 20
        const val MAX_NEEDED = 20
    }
}

/**
 * The DoA lifecycle (design E04 §3, §5, ADR-0040): drafts are edited and submitted by holders of `workflow.doa.manage`,
 * and published or sent back by a holder of `workflow.doa.approve` who did not submit them (maker-checker). A published
 * version takes effect on its date, which is today or later, and stays: new instances pin the newest version in force.
 * Every change is audited in its transaction, without rules or remarks. Inside a tenant transaction.
 */
@Component
class DoaLifecycle(
    private val versions: DoaVersions,
    private val checks: DoaChecks,
    private val directory: OrgDirectory,
    private val audit: AuditLog,
) {
    fun list(): List<DoaVersionRow> = versions.versions()

    fun get(number: Int): LoadedDoa {
        val version = versions.version(number) ?: throw DoaNotFoundException()
        return LoadedDoa(version, versions.rules(version.id))
    }

    /** A new draft from [rules], or from the version in force (else the newest one) when [rules] is null. */
    fun create(
        effectiveFrom: LocalDate?,
        rules: List<DoaRule>?,
    ): LoadedDoa {
        val all = versions.versions()
        refuseIf(all.any { it.state == VersionState.DRAFT || it.state == VersionState.IN_REVIEW }, "DRAFT_EXISTS") {
            "There is already a draft or a version in review."
        }
        val base = versions.inForce(directory.today()) ?: all.firstOrNull { it.state == VersionState.PUBLISHED }
        val body = rules ?: base?.let { versions.rules(it.id) }.orEmpty()
        checks.require(body)
        val number = versions.draft(effectiveFrom, body, actor())
        return get(number).also { record("workflow.doa.created", it.version) }
    }

    /** Replaces a draft's date and rules; [revision] is the client's `If-Match`. */
    fun replace(
        number: Int,
        revision: Long,
        effectiveFrom: LocalDate?,
        rules: List<DoaRule>,
    ): LoadedDoa {
        val loaded = get(number)
        refuseIf(loaded.version.state != VersionState.DRAFT, "WRONG_STATE") { "Only a draft can be changed." }
        checks.require(rules)
        if (!versions.replace(loaded.version.id, revision, effectiveFrom, rules)) {
            throw ConcurrentModificationException("workflow doa version ${loaded.version.id}", revision)
        }
        return get(number).also { record("workflow.doa.draft_updated", it.version) }
    }

    /** `DRAFT → IN_REVIEW`: it needs rules and a date from today on. */
    fun submit(
        number: Int,
        revision: Long,
    ): LoadedDoa {
        val loaded = get(number)
        refuseIf(loaded.version.state != VersionState.DRAFT, "WRONG_STATE") { "Only a draft can be submitted." }
        ready(loaded)
        val fields =
            mapOf(
                WORKFLOW_DOA_VERSION.SUBMITTED_BY to actor(),
                WORKFLOW_DOA_VERSION.SUBMITTED_AT to versions.now(),
                WORKFLOW_DOA_VERSION.REVIEWED_BY to null,
                WORKFLOW_DOA_VERSION.REVIEWED_AT to null,
            )
        if (!versions.move(loaded.version.id, VersionState.DRAFT, VersionState.IN_REVIEW, revision, fields)) {
            throw ConcurrentModificationException("workflow doa version ${loaded.version.id}", revision)
        }
        return get(number).also { record("workflow.doa.submitted", it.version) }
    }

    /** `IN_REVIEW → PUBLISHED`, by someone other than the submitter; checked again, as the organisation may have changed. */
    fun approve(
        number: Int,
        remark: String?,
    ): LoadedDoa {
        val loaded = reviewable(number, remark)
        ready(loaded)
        val date = checkNotNull(loaded.version.effectiveFrom)
        val taken = versions.versions().any { it.state == VersionState.PUBLISHED && it.effectiveFrom == date }
        refuseIf(taken, "EFFECTIVE_DATE_TAKEN") { "Another version takes effect on that date." }
        val at = versions.now()
        val fields =
            mapOf(
                WORKFLOW_DOA_VERSION.REVIEWED_BY to actor(),
                WORKFLOW_DOA_VERSION.REVIEWED_AT to at,
                WORKFLOW_DOA_VERSION.REVIEW_REMARK to remark?.trim()?.ifEmpty { null },
                WORKFLOW_DOA_VERSION.PUBLISHED_AT to at,
            )
        if (!versions.move(loaded.version.id, VersionState.IN_REVIEW, VersionState.PUBLISHED, fields = fields)) {
            throw ConcurrentModificationException("workflow doa version ${loaded.version.id}", loaded.version.revision)
        }
        return get(number).also { record("workflow.doa.published", it.version) }
    }

    /** `IN_REVIEW → DRAFT` with the reviewer's remark (required), by someone other than the submitter. */
    fun sendBack(
        number: Int,
        remark: String?,
    ): LoadedDoa {
        val text = remark?.trim()?.ifEmpty { null } ?: throw DoaInvalidException(listOf(REMARK_REQUIRED))
        val loaded = reviewable(number, text)
        val fields =
            mapOf(
                WORKFLOW_DOA_VERSION.REVIEWED_BY to actor(),
                WORKFLOW_DOA_VERSION.REVIEWED_AT to versions.now(),
                WORKFLOW_DOA_VERSION.REVIEW_REMARK to text,
            )
        if (!versions.move(loaded.version.id, VersionState.IN_REVIEW, VersionState.DRAFT, fields = fields)) {
            throw ConcurrentModificationException("workflow doa version ${loaded.version.id}", loaded.version.revision)
        }
        return get(number).also { record("workflow.doa.sent_back", it.version) }
    }

    private fun reviewable(
        number: Int,
        remark: String?,
    ): LoadedDoa {
        val loaded = get(number)
        refuseIf(loaded.version.state != VersionState.IN_REVIEW, "WRONG_STATE") { "Only a version in review can be decided." }
        refuseIf(loaded.version.submittedBy == actor(), "SAME_PERSON") { "Someone other than the submitter has to review it." }
        if ((remark?.length ?: 0) > Remarks.MAX_LENGTH) throw DoaInvalidException(listOf(REMARK_TOO_LONG))
        return loaded
    }

    private fun ready(loaded: LoadedDoa) {
        val date = loaded.version.effectiveFrom
        val problems =
            listOfNotNull(
                FieldProblem("effectiveFrom", "REQUIRED", "Say when the version takes effect.").takeIf { date == null },
                FieldProblem("effectiveFrom", "PAST", "The version takes effect today or later.").takeIf {
                    date != null && date.isBefore(directory.today())
                },
                FieldProblem("rules", "REQUIRED", "At least one rule.").takeIf { loaded.rules.isEmpty() },
            ) + checks.problems(loaded.rules)
        if (problems.isNotEmpty()) throw DoaInvalidException(problems)
    }

    private fun record(
        action: String,
        version: DoaVersionRow,
    ) = audit.record(
        action,
        "workflow_doa_version",
        version.id.toString(),
        after = mapOf("version" to version.number, "effectiveFrom" to version.effectiveFrom?.toString()),
    )

    private companion object {
        val REMARK_REQUIRED = FieldProblem("remark", "REQUIRED", "Say why the version goes back.")
        val REMARK_TOO_LONG = FieldProblem("remark", "TOO_LONG", "At most ${Remarks.MAX_LENGTH} characters.")
    }
}

private fun actor(): UUID = checkNotNull((ActorContext.current().actor as? Actor.User)?.userId) { "the DoA is changed by signed-in users" }
