package com.memo.workflow.internal

import com.memo.audit.AuditLog
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.jooq.tables.references.WORKFLOW_DEFINITION_VERSION
import com.memo.workflow.WorkflowDefinitionUsage
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.DefinitionCompiler
import com.memo.workflow.definition.DefinitionJson
import com.memo.workflow.engine.Remarks
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** No definition (or version) by that key and number in the tenant: 404. */
class DefinitionNotFoundException : RuntimeException("workflow definition not found")

/** The definition (or the request) does not pass validation: 422 `validation`, reason `DEFINITION_INVALID`. */
class DefinitionInvalidException(
    val problems: List<FieldProblem>,
) : RuntimeException("workflow definition invalid: ${problems.joinToString { "${it.field}:${it.code}" }}")

/** A definition and one of its versions, as the lifecycle reads them. */
data class LoadedVersion(
    val definition: DefinitionRow,
    val version: VersionRow,
)

/** Reading definitions for the admin screens (design E03 §5). Inside a tenant transaction. */
@Component
class DefinitionReads(
    private val versions: DefinitionVersions,
    private val checks: DefinitionChecks,
    private val json: JsonMapper,
) {
    fun definitions(): List<Pair<DefinitionRow, List<VersionRow>>> {
        val definitions = versions.definitions()
        val all = versions.versions(definitions.map { it.id }).groupBy { it.definitionId }
        return definitions.map { it to all[it.id].orEmpty() }
    }

    fun definition(key: String): Pair<DefinitionRow, List<VersionRow>> {
        val definition = versions.definition(key) ?: throw DefinitionNotFoundException()
        return definition to versions.versions(listOf(definition.id))
    }

    fun version(
        key: String,
        number: Int,
    ): LoadedVersion {
        val (definition, all) = definition(key)
        return LoadedVersion(definition, all.firstOrNull { it.number == number } ?: throw DefinitionNotFoundException())
    }

    /** The body in the current schema: the E01 built-in (schema 1) reads as the sequential definition it means. */
    fun body(version: VersionRow): JsonNode =
        if (version.schemaVersion == Definition.SCHEMA_VERSION) {
            json.readTree(version.body)
        } else {
            DefinitionJson.print(DefinitionCompiler.read(json.readTree(version.body), version.schemaVersion))
        }

    /** What submitting [version] would refuse; nothing for versions past review. */
    fun problems(version: VersionRow): List<FieldProblem> =
        if (version.state == VersionState.DRAFT || version.state == VersionState.IN_REVIEW) {
            checks.validate(body(version)).problems.map { FieldProblem(it.pointer, it.code, it.message) }
        } else {
            emptyList()
        }
}

/**
 * The definition lifecycle (ADR-0038, design E03 §5–§7): drafts are edited and submitted by holders of
 * `workflow.definitions.manage`, and approved or sent back by a holder of `.approve` who did not submit them
 * (maker-checker). Every change is audited in its transaction, without bodies or remarks. Inside a tenant transaction.
 */
@Component
class DefinitionLifecycle(
    private val versions: DefinitionVersions,
    private val reads: DefinitionReads,
    private val checks: DefinitionChecks,
    private val usages: List<WorkflowDefinitionUsage>,
    private val audit: AuditLog,
    private val json: JsonMapper,
) {
    /** A new definition with [body] as its draft version 1 (the body may still be invalid). */
    fun create(
        key: String,
        name: String,
        description: String?,
        fragment: Boolean,
        body: JsonNode,
    ): LoadedVersion {
        refuseIf(versions.definition(key) != null, "KEY_TAKEN") { "A workflow definition with this key exists." }
        val id = versions.create(key, name, description, fragment)
        versions.draft(id, json.writeValueAsString(body))
        return reads.version(key, 1).also { record("workflow.definition.created", it) }
    }

    /** Replaces a draft's body (import, or the editor saving); [revision] is the client's `If-Match`. */
    fun replace(
        key: String,
        number: Int,
        revision: Long,
        body: JsonNode,
    ): LoadedVersion {
        val loaded = reads.version(key, number)
        refuseIf(loaded.version.state != VersionState.DRAFT, "WRONG_STATE") { "Only a draft can be changed." }
        if (!versions.replaceBody(loaded.version.id, revision, json.writeValueAsString(body))) {
            throw ConcurrentModificationException("workflow definition version ${loaded.version.id}", revision)
        }
        return reads.version(key, number).also { record("workflow.definition.draft_updated", it) }
    }

    /** A new draft from [body], or from the published version (else the newest one) when [body] is null. */
    fun newDraft(
        key: String,
        body: JsonNode?,
    ): LoadedVersion {
        val (definition, all) = reads.definition(key)
        refuseIf(definition.builtIn, "BUILT_IN") { "Built-in definitions cannot be changed." }
        refuseIf(all.any { it.state == VersionState.DRAFT || it.state == VersionState.IN_REVIEW }, "DRAFT_EXISTS") {
            "This definition already has a draft or a version in review."
        }
        val base = all.firstOrNull { it.state == VersionState.PUBLISHED } ?: all.first()
        val (_, number) = versions.draft(definition.id, body?.let(json::writeValueAsString) ?: base.body)
        return reads.version(key, number).also { record("workflow.definition.draft_created", it) }
    }

    /** `DRAFT → IN_REVIEW`, refused while the body is invalid. */
    fun submit(
        key: String,
        number: Int,
        revision: Long,
    ): LoadedVersion {
        val loaded = reads.version(key, number)
        refuseIf(loaded.version.state != VersionState.DRAFT, "WRONG_STATE") { "Only a draft can be submitted." }
        valid(loaded)
        val fields =
            mapOf(
                SUBMITTED_BY to actor(),
                SUBMITTED_AT to versions.now(),
                REVIEWED_BY to null,
                REVIEWED_AT to null,
            )
        if (!versions.move(loaded.version.id, VersionState.DRAFT, VersionState.IN_REVIEW, revision, fields)) {
            throw ConcurrentModificationException("workflow definition version ${loaded.version.id}", revision)
        }
        return reads.version(key, number).also { record("workflow.definition.submitted", it) }
    }

    /** `IN_REVIEW → PUBLISHED`, by someone other than the submitter; the version published before is retired. */
    fun approve(
        key: String,
        number: Int,
        remark: String?,
    ): LoadedVersion {
        val loaded = reviewable(key, number, remark)
        // Checked again: a fragment it uses may have been retired since it was submitted.
        val compiled = checks.compile(valid(loaded))
        val previous = reads.definition(key).second.firstOrNull { it.state == VersionState.PUBLISHED }
        val published =
            versions.publish(loaded.definition.id, loaded.version.id, actor(), remark?.trim(), json.writeValueAsString(compiled))
        if (!published) throw ConcurrentModificationException("workflow definition version ${loaded.version.id}", loaded.version.revision)
        val retired = previous?.let { mapOf("retired" to it.number) }
        return reads.version(key, number).also { record("workflow.definition.published", it, retired) }
    }

    /** `IN_REVIEW → DRAFT` with the reviewer's remark (required), by someone other than the submitter. */
    fun sendBack(
        key: String,
        number: Int,
        remark: String?,
    ): LoadedVersion {
        val text = remark?.trim()?.ifEmpty { null } ?: throw DefinitionInvalidException(listOf(REMARK_REQUIRED))
        val loaded = reviewable(key, number, text)
        val fields = mapOf(REVIEWED_BY to actor(), REVIEWED_AT to versions.now(), REVIEW_REMARK to text)
        if (!versions.move(loaded.version.id, VersionState.IN_REVIEW, VersionState.DRAFT, fields = fields)) {
            throw ConcurrentModificationException("workflow definition version ${loaded.version.id}", loaded.version.revision)
        }
        return reads.version(key, number).also { record("workflow.definition.sent_back", it) }
    }

    /**
     * `PUBLISHED → RETIRED` while nothing chooses the definition: new items could not start otherwise. Instances already
     * on the version keep running on it (ADR-0038).
     */
    fun retire(
        key: String,
        number: Int,
    ): LoadedVersion {
        val loaded = reads.version(key, number)
        refuseIf(loaded.definition.builtIn, "BUILT_IN") { "Built-in definitions cannot be retired." }
        refuseIf(loaded.version.state != VersionState.PUBLISHED, "WRONG_STATE") { "Only the published version can be retired." }
        refuseIf(usages.any { it.inUse(key) }, "IN_USE") { "A memo type still uses this definition." }
        if (!versions.move(loaded.version.id, VersionState.PUBLISHED, VersionState.RETIRED)) {
            throw ConcurrentModificationException("workflow definition version ${loaded.version.id}", loaded.version.revision)
        }
        return reads.version(key, number).also { record("workflow.definition.retired", it) }
    }

    /** A version in review that the actor may decide on: not their own submission, remark within limits. */
    private fun reviewable(
        key: String,
        number: Int,
        remark: String?,
    ): LoadedVersion {
        val loaded = reads.version(key, number)
        refuseIf(loaded.version.state != VersionState.IN_REVIEW, "WRONG_STATE") { "Only a version in review can be decided." }
        refuseIf(loaded.version.submittedBy == actor(), "SAME_PERSON") { "Someone other than the submitter has to review it." }
        if ((remark?.length ?: 0) > Remarks.MAX_LENGTH) throw DefinitionInvalidException(listOf(REMARK_TOO_LONG))
        return loaded
    }

    private fun valid(loaded: LoadedVersion): Definition {
        val validation = checks.validate(reads.body(loaded.version))
        if (!validation.valid) {
            throw DefinitionInvalidException(validation.problems.map { FieldProblem(it.pointer, it.code, it.message) })
        }
        return checkNotNull(validation.definition)
    }

    private fun actor(): UUID =
        checkNotNull((ActorContext.current().actor as? Actor.User)?.userId) { "definitions are changed by signed-in users" }

    private fun record(
        action: String,
        loaded: LoadedVersion,
        extra: Map<String, Any?>? = null,
    ) = audit.record(
        action,
        "workflow_definition_version",
        loaded.version.id.toString(),
        after = mapOf("definition" to loaded.definition.key, "version" to loaded.version.number) + extra.orEmpty(),
    )

    private companion object {
        val SUBMITTED_BY = WORKFLOW_DEFINITION_VERSION.SUBMITTED_BY
        val SUBMITTED_AT = WORKFLOW_DEFINITION_VERSION.SUBMITTED_AT
        val REVIEWED_BY = WORKFLOW_DEFINITION_VERSION.REVIEWED_BY
        val REVIEWED_AT = WORKFLOW_DEFINITION_VERSION.REVIEWED_AT
        val REVIEW_REMARK = WORKFLOW_DEFINITION_VERSION.REVIEW_REMARK
        val REMARK_REQUIRED = FieldProblem("remark", "REQUIRED", "Say why the version goes back.")
        val REMARK_TOO_LONG = FieldProblem("remark", "TOO_LONG", "At most ${Remarks.MAX_LENGTH} characters.")
    }
}
