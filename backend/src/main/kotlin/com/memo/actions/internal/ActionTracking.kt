package com.memo.actions.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.ACTIONS_ITEM
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * What other modules' events change on an action (design E07 §1.1, §1.4), inside their transaction: the engine's view
 * (the instance, the holder of the owner's task, its step, the open step's name, the status) under the lock the engine
 * took through the subject, and the source item's copied columns when its meeting publishes again. Each change moves the
 * version by one, as the trigger requires.
 */
@Repository
class ActionTracking(
    private val tenantDsl: TenantDsl,
) {
    /**
     * The engine's view of the action: null arguments keep what is there, except [stepName], which is always set (null
     * when nothing is open). [vacant] clears the holder: the owner's task waits for an administrator (nobody holds the
     * owner's position any more).
     */
    @Suppress("LongParameterList")
    fun track(
        id: UUID,
        holder: UUID? = null,
        ownerStep: String? = null,
        stepName: String?,
        status: ActionStatus? = null,
        doneAt: Instant? = null,
        closedAt: Instant? = null,
        cancelKind: CancelKind? = null,
        closedBy: UUID? = null,
        vacant: Boolean = false,
    ) {
        val a = ACTIONS_ITEM
        var update =
            tenantDsl
                .current()
                .update(a)
                .set(a.STEP_NAME, stepName?.take(STEP_NAME_MAX))
                .set(a.VERSION, a.VERSION.plus(1))
        holder?.let { update = update.set(a.HOLDER_USER_ID, it) }
        if (vacant) update = update.setNull(a.HOLDER_USER_ID)
        ownerStep?.let { update = update.set(a.OWNER_STEP, it) }
        status?.let { update = update.set(a.STATUS, it.name) }
        doneAt?.let { update = update.set(a.DONE_AT, it.atOffset(ZoneOffset.UTC)) }
        closedAt?.let { update = update.set(a.CLOSED_AT, it.atOffset(ZoneOffset.UTC)) }
        cancelKind?.let { update = update.set(a.CANCEL_KIND, it.name) }
        closedBy?.let { update = update.set(a.CLOSED_BY, it) }
        update.where(a.ID.eq(id)).execute()
    }

    /** Records the workflow instance an issued action runs on. */
    fun setInstance(
        id: UUID,
        instanceId: UUID,
    ) {
        val a = ACTIONS_ITEM
        tenantDsl
            .current()
            .update(a)
            .set(a.WORKFLOW_INSTANCE_ID, instanceId)
            .set(a.VERSION, a.VERSION.plus(1))
            .where(a.ID.eq(id))
            .execute()
    }

    /** Copies the source item's [copy] and [label] onto [id] (a later revision of its meeting was published). */
    fun refreshItem(
        id: UUID,
        copy: ItemCopy,
        label: String,
    ) {
        val a = ACTIONS_ITEM
        tenantDsl
            .current()
            .update(a)
            .set(a.ITEM_PARENT_ID, copy.parentId)
            .set(a.ITEM_CLASSIFICATION_ID, copy.classificationId)
            .set(a.ITEM_RESTRICTED_TO, copy.restrictedTo?.toTypedArray<UUID?>())
            .set(a.ITEM_TITLE_HIDDEN, copy.titleHidden)
            .set(a.ITEM_MEMBERS_ONLY, copy.membersOnly)
            .set(a.SOURCE_LABEL, label)
            .set(a.VERSION, a.VERSION.plus(1))
            .where(a.ID.eq(id))
            .execute()
    }

    /** Sets [id]'s source label (its decision's resolution number was given or voided). */
    fun relabel(
        id: UUID,
        label: String,
    ) {
        val a = ACTIONS_ITEM
        tenantDsl
            .current()
            .update(a)
            .set(a.SOURCE_LABEL, label)
            .set(a.VERSION, a.VERSION.plus(1))
            .where(a.ID.eq(id))
            .execute()
    }

    private companion object {
        const val STEP_NAME_MAX = 200
    }
}
