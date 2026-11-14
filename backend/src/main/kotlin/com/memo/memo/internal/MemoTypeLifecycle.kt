package com.memo.memo.internal

import com.memo.audit.AuditLog
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.review.ReviewRules
import com.memo.common.review.ReviewState
import com.memo.common.web.refuseIf
import com.memo.jooq.tables.references.MEMO_TYPE_VERSION
import org.jooq.exception.IntegrityConstraintViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The reviewed lifecycle of memo types (ADR-0043, design E01 §1): holders of `memo.types.manage` draft a new version,
 * change it and submit it; a holder of `memo.types.approve` who did not submit it publishes it (after a step-up, checked
 * by the controller) or sends it back with a remark. Publishing retires the previous version and copies the settings onto
 * the type row, which every reader uses. Every change is audited in its transaction. Inside a tenant transaction.
 */
@Component
class MemoTypeLifecycle(
    private val types: MemoTypeStore,
    private val versions: MemoTypeVersionStore,
    private val checks: MemoTypeChecks,
    private val audit: AuditLog,
    private val attributes: MemoAttributes,
) {
    fun list(typeId: UUID): List<MemoTypeVersionRow> {
        type(typeId)
        return versions.versions(typeId)
    }

    fun get(
        typeId: UUID,
        number: Int,
    ): MemoTypeVersionRow = versions.version(typeId, number) ?: throw MemoNotFoundException("memo type version")

    /** A new draft from [settings], or from the published version when [settings] is null. One open version at a time. */
    fun newDraft(
        typeId: UUID,
        settings: MemoTypeSettings?,
    ): MemoTypeVersionRow {
        type(typeId)
        val all = versions.versions(typeId)
        refuseIf(all.any { it.state.open }, "DRAFT_EXISTS") { "This memo type already has a draft or a version in review." }
        val body = settings ?: checkNotNull(all.firstOrNull { it.state == ReviewState.PUBLISHED } ?: all.firstOrNull()).settings
        checks.require(body, typeId)
        val number = versions.insert(typeId, ReviewState.DRAFT, body, actor())
        return get(typeId, number).also { record("memo.type.draft_created", it) }
    }

    /** Replaces a draft's settings; [revision] is the client's `If-Match`. */
    fun replace(
        typeId: UUID,
        number: Int,
        revision: Long,
        settings: MemoTypeSettings,
    ): MemoTypeVersionRow {
        val version = get(typeId, number)
        ReviewRules.requireState(version.state, ReviewState.DRAFT, "changed")
        checks.require(settings, typeId)
        if (!versions.replace(
                version.id,
                revision,
                settings,
            )
        ) {
            throw ConcurrentModificationException("memo type version ${version.id}", revision)
        }
        return get(typeId, number).also { record("memo.type.draft_updated", it) }
    }

    /** `DRAFT → IN_REVIEW`, checked in full. */
    fun submit(
        typeId: UUID,
        number: Int,
        revision: Long,
    ): MemoTypeVersionRow {
        val version = get(typeId, number)
        ReviewRules.requireState(version.state, ReviewState.DRAFT, "submitted")
        checks.require(version.settings, typeId)
        val fields =
            mapOf(
                MEMO_TYPE_VERSION.SUBMITTED_BY to actor(),
                MEMO_TYPE_VERSION.SUBMITTED_AT to versions.now(),
                MEMO_TYPE_VERSION.REVIEWED_BY to null,
                MEMO_TYPE_VERSION.REVIEWED_AT to null,
            )
        if (!versions.move(version.id, ReviewState.DRAFT, ReviewState.IN_REVIEW, revision, fields)) {
            throw ConcurrentModificationException("memo type version ${version.id}", revision)
        }
        return get(typeId, number).also { record("memo.type.submitted", it) }
    }

    /**
     * `IN_REVIEW → PUBLISHED`, by someone other than the submitter. Checked again (a definition or another type's name may
     * have changed); the previous version is retired and the type row takes the new settings.
     */
    fun approve(
        typeId: UUID,
        number: Int,
        remark: String?,
    ): MemoTypeVersionRow {
        val version = reviewable(typeId, number, remark, required = false)
        checks.require(version.settings, typeId)
        val type = type(typeId)
        val at = versions.now()
        versions.retirePublished(typeId)
        val fields =
            mapOf(
                MEMO_TYPE_VERSION.REVIEWED_BY to actor(),
                MEMO_TYPE_VERSION.REVIEWED_AT to at,
                MEMO_TYPE_VERSION.REVIEW_REMARK to ReviewRules.remark(remark),
                MEMO_TYPE_VERSION.PUBLISHED_AT to at,
            )
        if (!versions.move(version.id, ReviewState.IN_REVIEW, ReviewState.PUBLISHED, fields = fields)) {
            throw ConcurrentModificationException("memo type version ${version.id}", version.revision)
        }
        mirror(type, version)
        // Workflow conditions and DoA rules see the new fields from this commit on.
        attributes.changed()
        return get(typeId, number).also { record("memo.type.published", it) }
    }

    /** Copies a newly published [version] onto the [type] row, which every reader of the type uses. */
    private fun mirror(
        type: MemoType,
        version: MemoTypeVersionRow,
    ) {
        val s = version.settings
        val fields =
            MemoTypeFields(
                name = s.name,
                defaultClassificationId = s.defaultClassificationId,
                stepUpOnApprove = s.stepUpOnApprove,
                active = type.active,
                definitionKey = s.definitionKey,
                routePolicy = s.routePolicy,
                allowedApprovers = s.allowedApprovers,
                doaCheck = s.doaCheck,
                code = s.code,
            )
        // A unique name or code taken by another type after the checks: nothing is published.
        val mirrored =
            try {
                types.update(type.id, null, fields, version.number)
            } catch (_: DuplicateKeyException) {
                false
            } catch (_: IntegrityConstraintViolationException) {
                false
            }
        if (!mirrored) throw ConcurrentModificationException("memo type ${type.id}", type.version)
    }

    /** `IN_REVIEW → DRAFT` with the reviewer's remark (required), by someone other than the submitter. */
    fun sendBack(
        typeId: UUID,
        number: Int,
        remark: String?,
    ): MemoTypeVersionRow {
        val version = reviewable(typeId, number, remark, required = true)
        val fields =
            mapOf(
                MEMO_TYPE_VERSION.REVIEWED_BY to actor(),
                MEMO_TYPE_VERSION.REVIEWED_AT to versions.now(),
                MEMO_TYPE_VERSION.REVIEW_REMARK to ReviewRules.remark(remark),
            )
        if (!versions.move(version.id, ReviewState.IN_REVIEW, ReviewState.DRAFT, fields = fields)) {
            throw ConcurrentModificationException("memo type version ${version.id}", version.revision)
        }
        return get(typeId, number).also { record("memo.type.sent_back", it) }
    }

    private fun reviewable(
        typeId: UUID,
        number: Int,
        remark: String?,
        required: Boolean,
    ): MemoTypeVersionRow {
        val version = get(typeId, number)
        ReviewRules.requireState(version.state, ReviewState.IN_REVIEW, "decided")
        ReviewRules.requireOtherReviewer(version.submittedBy, actor())
        ReviewRules.remarkProblem(remark, required)?.let { throw MemoRejectedException(listOf(it)) }
        return version
    }

    private fun type(typeId: UUID): MemoType = types.find(typeId) ?: throw MemoNotFoundException("memo type")

    private fun record(
        action: String,
        version: MemoTypeVersionRow,
    ) = audit.record(
        action,
        MemoTypes.OBJECT_TYPE,
        version.typeId.toString(),
        after = mapOf("version" to version.number, "state" to version.state.name) + MemoTypeChecks.audited(version.settings),
    )
}

private fun actor(): UUID = checkNotNull(actorOrNull()) { "memo types are reviewed by signed-in users" }
