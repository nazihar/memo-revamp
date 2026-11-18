package com.memo.forum.internal

import com.memo.audit.AuditLog
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.review.ReviewRules
import com.memo.common.review.ReviewState
import com.memo.common.web.refuseIf
import com.memo.forum.ForumTypePublished
import com.memo.jooq.tables.references.FORUM_TYPE_VERSION
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The reviewed lifecycle of forum types (ADR-0056, design E01 §1), on the shared `common.review` rules: holders of
 * `forum.types.manage` draft, change and submit a version; a holder of `forum.types.approve` who did not submit it
 * publishes it (after a step-up, checked by the controller) or sends it back with a remark. Audited in the transaction.
 */
@Component
class ForumTypeLifecycle(
    private val store: ForumTypeStore,
    private val versions: ForumTypeVersionStore,
    private val checks: ForumTypeChecks,
    private val audit: AuditLog,
    private val events: ApplicationEventPublisher,
) {
    fun list(typeId: UUID): List<ForumTypeVersionRow> {
        type(typeId)
        return versions.versions(typeId)
    }

    fun get(
        typeId: UUID,
        number: Int,
    ): ForumTypeVersionRow = versions.version(typeId, number) ?: throw ForumNotFoundException("forum type version")

    /** A new draft copied from the published version (else the newest). One open version at a time. */
    fun newDraft(typeId: UUID): ForumTypeVersionRow {
        type(typeId)
        val all = versions.versions(typeId)
        refuseIf(all.any { it.state.open }, "DRAFT_EXISTS") { "This committee type already has a draft or a version in review." }
        val source = all.firstOrNull { it.state == ReviewState.PUBLISHED } ?: all.first()
        val number = versions.insertVersion(typeId, ReviewState.DRAFT, source.settings, actor())
        return get(typeId, number).also { record("forum.type.draft_created", it) }
    }

    /** Replaces a draft's settings; [revision] is the client's `If-Match`. Saved even if incomplete only when valid. */
    fun replace(
        typeId: UUID,
        number: Int,
        revision: Long,
        settings: ForumTypeSettings,
    ): ForumTypeVersionRow {
        val version = get(typeId, number)
        ReviewRules.requireState(version.state, ReviewState.DRAFT, "changed")
        checks.require(settings)
        if (!versions.replace(
                version.id,
                revision,
                settings,
            )
        ) {
            throw ConcurrentModificationException("forum type version ${version.id}", revision)
        }
        return get(typeId, number).also { record("forum.type.version.saved", it) }
    }

    fun submit(
        typeId: UUID,
        number: Int,
        revision: Long,
    ): ForumTypeVersionRow {
        val version = get(typeId, number)
        ReviewRules.requireState(version.state, ReviewState.DRAFT, "submitted")
        checks.require(version.settings)
        val fields =
            mapOf(
                FORUM_TYPE_VERSION.SUBMITTED_BY to actor(),
                FORUM_TYPE_VERSION.SUBMITTED_AT to store.now(),
                FORUM_TYPE_VERSION.REVIEWED_BY to null,
                FORUM_TYPE_VERSION.REVIEWED_AT to null,
            )
        if (!versions.move(version.id, ReviewState.DRAFT, ReviewState.IN_REVIEW, revision, fields)) {
            throw ConcurrentModificationException("forum type version ${version.id}", revision)
        }
        return get(typeId, number).also { record("forum.type.version.submitted", it) }
    }

    /** `IN_REVIEW → PUBLISHED` by someone other than the submitter; the previous version is retired. */
    fun approve(
        typeId: UUID,
        number: Int,
        remark: String?,
    ): ForumTypeVersionRow {
        val version = reviewable(typeId, number, remark, required = false)
        checks.require(version.settings)
        val at = store.now()
        versions.retirePublished(typeId)
        val fields =
            mapOf(
                FORUM_TYPE_VERSION.REVIEWED_BY to actor(),
                FORUM_TYPE_VERSION.REVIEWED_AT to at,
                FORUM_TYPE_VERSION.REVIEW_REMARK to ReviewRules.remark(remark),
                FORUM_TYPE_VERSION.PUBLISHED_AT to at,
            )
        if (!versions.move(version.id, ReviewState.IN_REVIEW, ReviewState.PUBLISHED, fields = fields)) {
            throw ConcurrentModificationException("forum type version ${version.id}", version.revision)
        }
        store.setPublished(typeId, number)
        val published = get(typeId, number)
        record("forum.type.version.published", published)
        events.publishEvent(ForumTypePublished(store.tenant(), typeId, number))
        return published
    }

    fun sendBack(
        typeId: UUID,
        number: Int,
        remark: String?,
    ): ForumTypeVersionRow {
        val version = reviewable(typeId, number, remark, required = true)
        val fields =
            mapOf(
                FORUM_TYPE_VERSION.REVIEWED_BY to actor(),
                FORUM_TYPE_VERSION.REVIEWED_AT to store.now(),
                FORUM_TYPE_VERSION.REVIEW_REMARK to ReviewRules.remark(remark),
            )
        if (!versions.move(version.id, ReviewState.IN_REVIEW, ReviewState.DRAFT, fields = fields)) {
            throw ConcurrentModificationException("forum type version ${version.id}", version.revision)
        }
        return get(typeId, number).also { record("forum.type.version.sent_back", it) }
    }

    private fun reviewable(
        typeId: UUID,
        number: Int,
        remark: String?,
        required: Boolean,
    ): ForumTypeVersionRow {
        val version = get(typeId, number)
        ReviewRules.requireState(version.state, ReviewState.IN_REVIEW, "decided")
        ReviewRules.requireOtherReviewer(version.submittedBy, actor())
        ReviewRules.remarkProblem(remark, required)?.let { throw ForumRejectedException(listOf(it)) }
        return version
    }

    private fun type(typeId: UUID): ForumType = store.find(typeId) ?: throw ForumNotFoundException("forum type")

    private fun record(
        action: String,
        version: ForumTypeVersionRow,
    ) = audit.record(
        action,
        ForumTypes.OBJECT_TYPE,
        version.typeId.toString(),
        after = mapOf("version" to version.number, "state" to version.state.name) + ForumTypes.audited(version.settings),
    )
}
