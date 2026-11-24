package com.memo.actions

import com.memo.actions.internal.ActionClosure
import com.memo.actions.internal.ActionDates
import com.memo.actions.internal.ActionEnding
import com.memo.actions.internal.ActionEvidencePurpose
import com.memo.actions.internal.ActionFields
import com.memo.actions.internal.ActionFollowUps
import com.memo.actions.internal.ActionIssuing
import com.memo.actions.internal.ActionMemoStore
import com.memo.actions.internal.ActionProgress
import com.memo.actions.internal.ActionRaising
import com.memo.actions.internal.ActionReportStore
import com.memo.actions.internal.ActionSource
import com.memo.actions.internal.ActionStore
import com.memo.actions.internal.CancelInput
import com.memo.actions.internal.CancelKind
import com.memo.actions.internal.Confidence
import com.memo.actions.internal.DecisionSource
import com.memo.actions.internal.DueDecision
import com.memo.actions.internal.OwnerInput
import com.memo.actions.internal.OwnerKind
import com.memo.actions.internal.RaiseInput
import com.memo.actions.internal.RaiseMemoInput
import com.memo.actions.internal.UpdateInput
import com.memo.actions.internal.VerifyDecision
import com.memo.actions.internal.titled
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileUploads
import com.memo.files.Files
import com.memo.forum.ActionsPolicy
import com.memo.org.OrgDirectory
import com.memo.workflow.WorkflowTimerSeeding
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/** An action's owner as a seeder names it: a person, or a position (whoever holds it). */
data class SeedOwner(
    val userId: UUID? = null,
    val positionId: UUID? = null,
)

/** What a seeder writes on a draft action: what is to be done, what the owner is told, the owner and the date. */
data class SeedAction(
    val title: String,
    val owner: SeedOwner,
    val dueDate: LocalDate,
    val context: String? = null,
    val description: String? = null,
    /** A direct action only: where it comes from. */
    val sourceNote: String? = null,
)

/** An action as a seeder finds it: its id, status (`DRAFT`, `OPEN`, `IN_PROGRESS`, `DONE_PENDING`, `CLOSED`, `CANCELLED`) and reference. */
data class SeededAction(
    val id: UUID,
    val status: String,
    val reference: String?,
    val dueRevisions: Int,
)

/**
 * Action items for seeders (the demo tenant, Phase 4 E07 T12), through the same services as the API, as the user bound to
 * the current unit of work. Never called from requests. Each call is its own tenant transaction at the action's current
 * version; uploads run outside one, as the files API's do. The seeder decides what already exists ("seeders only fill
 * gaps", by an action's title on its committee). Two things only a seeder does, so that an action issued at a meeting
 * weeks ago looks as it would had the app been running since: [issue] takes the day it was issued on, and [catchUp]
 * fires its timers that have come due since (the engine's own firing, including the escalation).
 */
@Service
@Suppress("TooManyFunctions")
class ActionSeeding(
    private val raising: ActionRaising,
    private val issuing: ActionIssuing,
    private val progress: ActionProgress,
    private val dates: ActionDates,
    private val closure: ActionClosure,
    private val ending: ActionEnding,
    private val followUps: ActionFollowUps,
    private val decisions: DecisionSource,
    private val store: ActionStore,
    private val memoLinks: ActionMemoStore,
    private val reports: ActionReportStore,
    private val uploads: FileUploads,
    private val files: Files,
    private val workflows: WorkflowTimerSeeding,
    private val org: OrgDirectory,
    private val transactions: TenantTransactions,
) {
    /** The action called [title] on [forumId], the latest raised; null when there is none. */
    fun find(
        forumId: UUID,
        title: String,
    ): SeededAction? =
        tx {
            store
                .titled(forumId, title)
                .lastOrNull()
                ?.let { SeededAction(it.id, it.status.name, it.reference, it.dueRevisions) }
        }

    /** A draft from condition [conditionIndex] (from 0) of the decision on [itemId] of [meetingId]; its id. */
    fun raiseFromCondition(
        meetingId: UUID,
        itemId: UUID,
        conditionIndex: Int,
        action: SeedAction,
    ): UUID =
        tx {
            val decision = checkNotNull(decisions.ofItem(itemId)) { "no decision on item $itemId" }
            raising.raise(
                meetingId,
                RaiseInput(
                    ActionSource.DECISION,
                    decisionId = decision.decisionId,
                    conditionIndex = conditionIndex,
                    fields = fields(action),
                ),
            )
        }

    /** A draft from the item [itemId] of [meetingId] (what the committee asked in its discussion); its id. */
    fun raiseFromItem(
        meetingId: UUID,
        itemId: UUID,
        action: SeedAction,
    ): UUID = tx { raising.raise(meetingId, RaiseInput(ActionSource.ITEM, itemId = itemId, fields = fields(action))) }

    /** A direct draft of [forumId] (between meetings); its id. */
    fun raiseDirect(
        forumId: UUID,
        action: SeedAction,
    ): UUID = tx { raising.raiseDirect(forumId, fields(action)) }

    /**
     * Issues the draft [actionId] due on [dueDate], as on [asOf] (the day of the meeting it was agreed at; today when
     * null), which the date is checked against. The draft's date is written as it stands, so an action agreed weeks ago
     * may be due on a day now past. Its reference.
     */
    fun issue(
        actionId: UUID,
        dueDate: LocalDate,
        asOf: LocalDate? = null,
    ): String =
        tx {
            val draft = checkNotNull(store.find(actionId)) { "action $actionId" }
            if (draft.dueDate != dueDate && !store.editDraft(draft.copy(dueDate = dueDate), draft.version)) {
                throw ConcurrentModificationException("action $actionId", draft.version)
            }
            checkNotNull(issuing.issue(actionId, null, asOf).reference)
        }

    /**
     * Fires [actionId]'s timers that have come due (see [WorkflowTimerSeeding.catchUp]), with its escalation when [escalate];
     * how many fired.
     */
    fun catchUp(
        actionId: UUID,
        escalate: Boolean,
    ): Int = workflows.catchUp(ActionsPolicy.SUBJECT, actionId, escalate)

    /** The owner's update on [actionId] with how it is going ([confidence]: `ON_TRACK`, `AT_RISK`, `OFF_TRACK`). */
    fun update(
        actionId: UUID,
        text: String,
        confidence: String? = null,
        fileIds: List<UUID> = emptyList(),
    ) {
        tx { progress.update(actionId, version(actionId), UpdateInput(text, confidence?.let(Confidence::valueOf), fileIds)) }
    }

    /** Uploads [bytes] as an evidence file of [actionId] for the bound user; the file's id. */
    fun evidence(
        actionId: UUID,
        name: String,
        bytes: ByteArray,
    ): UUID {
        val uploaded = uploads.upload(ActionEvidencePurpose.KEY, actionId.toString(), name, bytes, UUID.randomUUID().toString())
        return tx { checkNotNull(files.version(uploaded)) { "version $uploaded" }.fileId }
    }

    /** The owner asks for [newDate] for [reason]; the request's id. */
    fun requestDate(
        actionId: UUID,
        newDate: LocalDate,
        reason: String,
    ): UUID = tx { dates.request(actionId, version(actionId), newDate, reason) }

    /** A secretary (or the chair, as the committee says) agrees the request [requestId] with [note]. */
    fun agreeDate(
        actionId: UUID,
        requestId: UUID,
        note: String?,
    ) {
        tx { dates.decide(actionId, requestId, version(actionId), DueDecision.AGREE, note) }
    }

    /** Whoever holds the owner's task marks [actionId] as done with [note] and the evidence [fileIds]. */
    fun done(
        actionId: UUID,
        note: String,
        fileIds: List<UUID> = emptyList(),
    ) {
        tx { closure.done(actionId, version(actionId), note, fileIds) }
    }

    /** The verifier closes [actionId] with [remark]. */
    fun close(
        actionId: UUID,
        remark: String?,
    ) {
        tx { closure.verify(actionId, version(actionId), VerifyDecision.CLOSE, remark) }
    }

    /** The chair cancels [actionId]: the committee accepts the risk, for [reason]. */
    fun cancelRiskAccepted(
        actionId: UUID,
        reason: String,
    ) {
        tx { ending.cancel(actionId, version(actionId), CancelInput(CancelKind.RISK_ACCEPTED, reason)) }
    }

    /** The owner raises a draft memo of [typeId] on [subject] from [actionId]; the memo's id. */
    fun raiseMemo(
        actionId: UUID,
        typeId: UUID,
        subject: String,
    ): UUID = tx { followUps.raise(actionId, RaiseMemoInput(typeId, subject)) }

    /** The follow-up memos of [actionId], oldest first. */
    fun memos(actionId: UUID): List<UUID> = tx { memoLinks.of(actionId).map { it.memoId } }

    /** The actions on the latest action taken report of [meetingId]; empty when it has none. */
    fun reported(meetingId: UUID): Set<UUID> =
        tx {
            reports
                .find(meetingId, null)
                ?.let { header ->
                    reports.rows(header.id, DSL.noCondition()).mapNotNull { it.actionId }.toSet()
                }.orEmpty()
        }

    private fun version(actionId: UUID): Long = checkNotNull(store.find(actionId)) { "action $actionId" }.version

    private fun fields(action: SeedAction): ActionFields =
        ActionFields(
            title = action.title,
            description = action.description,
            context = action.context,
            owner =
                when {
                    action.owner.positionId != null -> OwnerInput(OwnerKind.POSITION, positionId = action.owner.positionId)
                    else -> OwnerInput(OwnerKind.PERSON, userId = checkNotNull(action.owner.userId) { "an owner" })
                },
            // A date now past is written when the action is issued as at its meeting ([issue]).
            dueDate = maxOf(action.dueDate, org.today()),
            sourceNote = action.sourceNote,
        )

    private fun <T> tx(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}
