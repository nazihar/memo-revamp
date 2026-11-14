package com.memo.memo

import com.memo.common.authz.Clearances
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileUploads
import com.memo.files.Files
import com.memo.memo.internal.MemoDocumentPurpose
import com.memo.memo.internal.MemoFields
import com.memo.memo.internal.MemoListStore
import com.memo.memo.internal.MemoReviews
import com.memo.memo.internal.MemoRoutes
import com.memo.memo.internal.MemoTypeFields
import com.memo.memo.internal.MemoTypeLifecycle
import com.memo.memo.internal.MemoTypeStore
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.MemoWorkflowSubject
import com.memo.memo.internal.Memos
import com.memo.memo.internal.RepresentativeTypes
import com.memo.memo.internal.RoutePolicy
import com.memo.workflow.RouteEntry
import com.memo.workflow.WorkflowQueries
import com.memo.workflow.Workflows
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Memos for seeders (the demo tenant, design E01 §15), through the same services as the API, as the user bound to the
 * current unit of work. Never called from requests. Approving skips the step-up a person would give: seeders have no
 * session. Each call is its own tenant transaction, except uploads, which run outside one as the files service needs.
 */
@Service
class MemoSeeding(
    private val memos: Memos,
    private val types: MemoTypes,
    private val routes: MemoRoutes,
    private val reviews: MemoReviews,
    private val lists: MemoListStore,
    private val workflows: Workflows,
    private val queries: WorkflowQueries,
    private val clearances: Clearances,
    private val files: Files,
    private val uploads: FileUploads,
    private val transactions: TenantTransactions,
    private val lifecycle: MemoTypeLifecycle,
) {
    /** Whether [initiator] has created any memo yet (seeders run once per tenant). */
    fun hasMemos(initiator: UUID): Boolean = tx { lists.anyOf(initiator) }

    /** A draft of [typeId] (the built-in General type when null), classified at [rank]. */
    fun draft(
        subject: String,
        rank: Int = 0,
        typeId: UUID? = null,
    ): UUID =
        tx {
            val type = typeId ?: types.active().first { it.key == MemoTypeStore.GENERAL }.id
            memos.create(MemoFields(subject, type, clearances.levels()[rank].id)).id
        }

    /** The memo's document: its first file, or a new version of it. */
    fun document(
        memo: UUID,
        name: String,
        bytes: ByteArray,
    ) {
        val current = tx { memos.get(memo).documentFileId?.let { files.info(it) } }
        val key = UUID.randomUUID().toString()
        if (current == null) {
            uploads.upload(MemoDocumentPurpose.KEY, memo.toString(), name, bytes, key)
        } else {
            uploads.uploadVersion(current.id, current.version, name, bytes, key)
        }
    }

    fun route(
        memo: UUID,
        approvers: List<UUID>,
    ) {
        tx { routes.replace(memo, memos.get(memo).version, approvers.map(RouteEntry::person)) }
    }

    fun submit(
        memo: UUID,
        remark: String? = null,
    ) {
        tx { reviews.submit(memo, remark) }
    }

    fun resubmit(
        memo: UUID,
        remark: String? = null,
    ) {
        tx { reviews.resubmit(memo, remark) }
    }

    fun withdraw(
        memo: UUID,
        remark: String? = null,
    ) {
        tx { reviews.withdraw(memo, remark) }
    }

    /** Approves, returns or rejects the open task of [memo], which must be the current user's. */
    fun act(
        memo: UUID,
        action: String,
        remark: String? = null,
    ) {
        tx {
            val task = checkNotNull(queries.viewOf(MemoWorkflowSubject.TYPE, memo)?.openTaskId) { "memo $memo has no open task" }
            when (action) {
                APPROVE -> workflows.approve(task, remark)
                RETURN -> workflows.returnToInitiator(task, remark)
                REJECT -> workflows.reject(task, remark)
                else -> throw IllegalArgumentException("unknown action $action")
            }
        }
    }

    /**
     * A memo type called [name] on workflow [definitionKey], unless the tenant has one: `FIXED` (the definition names every
     * step) or, with [routed], chosen by the initiator. Returns its id.
     */
    fun fixedType(
        name: String,
        definitionKey: String,
        routed: Boolean = false,
    ): UUID =
        tx {
            types.all().firstOrNull { it.name == name }?.id
                ?: types
                    .create(
                        MemoTypeFields(
                            name,
                            null,
                            stepUpOnApprove = !routed,
                            active = true,
                            definitionKey,
                            if (routed) RoutePolicy.INITIATOR_DEFINED else RoutePolicy.FIXED,
                        ),
                    ).id
        }

    /**
     * The representative bank memo types (Phase 3 E01 T8): each created if missing and given its fields, numbering and
     * policies through the reviewed lifecycle, drafted and submitted by [author] and approved by [reviewer]. Fills gaps
     * only: a type whose published version has fields already is left alone. Returns how many types were published.
     */
    fun representativeTypes(
        author: UUID,
        reviewer: UUID,
    ): Int =
        RepresentativeTypes.ALL.count { spec ->
            val id =
                asUser(author) {
                    tx {
                        types.all().firstOrNull { it.name == spec.name }?.id
                            ?: types.create(MemoTypeFields(spec.name, null, true, true)).id
                    }
                }
            val published = asUser(author) { tx { types.published(id) } }
            if (published == null || published.settings.fields.isNotEmpty()) return@count false
            val draft = asUser(author) { tx { lifecycle.newDraft(id, spec.settings(published.settings.copy(code = spec.code))) } }
            asUser(author) { tx { lifecycle.submit(id, draft.number, draft.revision) } }
            asUser(reviewer) { tx { lifecycle.approve(id, draft.number, null) } }
            true
        }

    private fun <T> tx(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)

    companion object {
        const val APPROVE = "approve"
        const val RETURN = "return"
        const val REJECT = "reject"
    }
}

/** Runs [block] as [user] (seeders switch between the maker and the checker). */
private fun <T> asUser(
    user: UUID,
    block: () -> T,
): T = ActorContext.call(ActorScope(Actor.User(user), null), block)
