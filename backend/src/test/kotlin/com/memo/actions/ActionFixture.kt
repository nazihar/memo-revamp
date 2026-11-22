package com.memo.actions

import com.memo.actions.internal.ActionAccess
import com.memo.actions.internal.ActionFields
import com.memo.actions.internal.ActionNotFoundException
import com.memo.actions.internal.ActionRaising
import com.memo.actions.internal.ActionRejectedException
import com.memo.actions.internal.ActionRow
import com.memo.actions.internal.ActionSource
import com.memo.actions.internal.ActionStore
import com.memo.actions.internal.ActionsController
import com.memo.actions.internal.OwnerInput
import com.memo.actions.internal.OwnerKind
import com.memo.actions.internal.RaiseInput
import com.memo.common.authz.Clearances
import com.memo.common.authz.ForbiddenException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.web.RefusedException
import com.memo.identity.UserAccount
import com.memo.minutes.DecisionFixture
import com.memo.org.OrgSeeding
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.query
import com.memo.workflow.WorkflowQueries
import com.memo.workflow.Workflows
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * E06's running BRC meeting with the positions actions are given to (test plan E07 §1): "Head of Treasury" held by
 * [treasurer], an executive outside every committee, and helpers to raise, issue, read and refuse actions as a person.
 */
abstract class ActionFixture : DecisionFixture() {
    @Autowired lateinit var raising: ActionRaising

    @Autowired lateinit var actionAccess: ActionAccess

    @Autowired lateinit var actionStore: ActionStore

    @Autowired lateinit var actionLines: com.memo.actions.internal.ActionLines

    @Autowired lateinit var issuing: com.memo.actions.internal.ActionIssuing

    @Autowired lateinit var actionsController: ActionsController

    @Autowired lateinit var orgSeeding: OrgSeeding

    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var workflowQueries: WorkflowQueries

    @Autowired lateinit var clearanceLevels: Clearances

    @Autowired lateinit var progress: com.memo.actions.internal.ActionProgress

    @Autowired lateinit var dates: com.memo.actions.internal.ActionDates

    @Autowired lateinit var ownership: com.memo.actions.internal.ActionOwnership

    @Autowired lateinit var ending: com.memo.actions.internal.ActionEnding

    @Autowired lateinit var forumPolicies: com.memo.forum.ForumPolicies

    @Autowired lateinit var closure: com.memo.actions.internal.ActionClosure

    @Autowired lateinit var daily: com.memo.actions.internal.ActionsDaily

    @Autowired lateinit var workingDays: com.memo.workflow.WorkingDays

    /** Publishes the next version of the committee's type with [policy] as its actions policy (a second administrator approves). */
    fun actionsPolicy(policy: com.memo.forum.ActionsPolicy) {
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        val draft = asUser(admin) { typeVersions.newDraft(typeId) }
        val saved = asUser(admin) { typeVersions.replace(typeId, draft.number, draft.revision, draft.settings.copy(actions = policy)) }
        asUser(admin) { typeVersions.submit(typeId, draft.number, saved.revision) }
        asUser(checker) { typeVersions.approve(typeId, draft.number, null) }
    }

    /** An issued action on [item] of a running meeting, owned by [owner] (a person by default); its row. */
    fun issued(
        owner: OwnerInput? = null,
        contributors: List<UUID> = emptyList(),
        deciding: DecisionFixture.Deciding = deciding(),
        item: UUID? = deciding.credit,
    ): ActionRow {
        val id = raise(deciding.meeting.id, item, fields(owner = owner ?: person(director2), contributors = contributors))
        return issue(id)
    }

    /** [account]'s open task on [actionId], or null. */
    fun taskOf(
        actionId: UUID,
        account: UserAccount,
    ) = asUser(account) { workflowQueries.myTask(com.memo.forum.ActionsPolicy.SUBJECT, actionId, account.id.value) }

    /** The kinds of [actionId]'s timeline, the newest first. */
    fun timeline(actionId: UUID): List<String> = transactions.readInTenant(tenant.id) { actionLines.entries(actionId).map { it.kind } }

    @Autowired lateinit var actionsDsl: com.memo.common.tenant.TenantDsl

    /** Sets [sql] (an `UPDATE actions_item SET …` fragment) on [actionId] as the app role, committed, moving its version. */
    fun alter(
        actionId: UUID,
        set: String,
        vararg values: Any,
    ) = transactions.inTenant(tenant.id) {
        actionsDsl.current().execute("update actions_item set $set, version = version + 1 where id = ?", *values, actionId)
    }

    /** The pending timers of [instanceId], by kind, with when they fire. */
    fun pendingTimers(instanceId: UUID): List<Pair<String, java.time.Instant>> =
        com.memo.testsupport.AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.query(
                "select kind, fires_at from workflow_timer where instance_id = ? and state = 'PENDING' order by fires_at",
                instanceId,
            ) { it.getString(1) to it.getObject(2, java.time.OffsetDateTime::class.java).toInstant() }
        }

    /** The 403 reason of [block] ("" for a plain 403), or "404", or the 409 or 422 code. */
    fun outcome(
        by: UserAccount,
        block: () -> Any,
    ): String =
        when (val e = runCatching { asUser(by, block) }.exceptionOrNull()) {
            null -> "OK"
            is ForbiddenException -> "403 ${e.reason.orEmpty()}".trim()
            is ActionNotFoundException -> "404"
            is RefusedException -> "409 ${e.reason}"
            is ActionRejectedException -> "422 " + e.problems.joinToString(",") { it.code }
            is com.memo.common.concurrency.ConcurrentModificationException -> "409 CONFLICT"
            else -> throw AssertionError("unexpected", e)
        }

    /** The holder of "Head of Treasury", outside every committee. */
    lateinit var treasurer: UserAccount
    lateinit var headOfTreasury: UUID

    @BeforeEach
    fun positions() {
        treasurer = accounts.anActiveUser(transactions, tenant, aPassword())
        headOfTreasury = UUID.randomUUID()
        val entity = UUID.randomUUID()
        val unit = UUID.randomUUID()
        val start = LocalDate.now(ZoneOffset.UTC).minusYears(1)
        asUser(admin) {
            orgSeeding.legalEntity(entity, "Bank", start)
            orgSeeding.unit(unit, entity, null, "TR", "Treasury", start)
            orgSeeding.position(headOfTreasury, unit, "Head of Treasury", head = true, start)
            orgSeeding.assignment(headOfTreasury, treasurer.id.value, start)
        }
    }

    fun levels() = asUser(admin) { clearanceLevels.levels() }

    /** A month ahead: a due date that is never in the past. */
    val due: LocalDate get() = LocalDate.now(ZoneOffset.UTC).plusDays(30)

    fun person(account: UserAccount) = OwnerInput(OwnerKind.PERSON, userId = account.id.value)

    fun position(id: UUID = headOfTreasury) = OwnerInput(OwnerKind.POSITION, positionId = id)

    fun fields(
        title: String? = "Report the revised collateral valuation",
        owner: OwnerInput? = position(),
        dueDate: LocalDate? = due,
        contributors: List<UUID> = emptyList(),
        context: String? = null,
        classificationId: UUID? = null,
    ) = ActionFields(title, "Details", context, owner, contributors, dueDate, classificationId = classificationId)

    /** Raises a draft on [meetingId] from [item] (an ITEM source), or the meeting when null; its id. */
    fun raise(
        meetingId: UUID,
        item: UUID?,
        fields: ActionFields = fields(),
        by: UserAccount = secretary,
        source: ActionSource = if (item == null) ActionSource.MEETING else ActionSource.ITEM,
        decisionId: UUID? = null,
        conditionIndex: Int? = null,
    ): UUID = asUser(by) { raising.raise(meetingId, RaiseInput(source, item, decisionId, conditionIndex, fields)) }

    fun issue(
        actionId: UUID,
        by: UserAccount = secretary,
    ): ActionRow = asUser(by) { issuing.issue(actionId, row(actionId).version) }

    fun row(actionId: UUID): ActionRow = transactions.readInTenant(tenant.id) { checkNotNull(actionStore.find(actionId)) }

    /** [by]'s view of the action, or null when they get 404. */
    fun seen(
        actionId: UUID,
        by: UserAccount,
    ) = runCatching { asUser(by) { actionAccess.action(actionId) } }.getOrElse {
        if (it is ActionNotFoundException) null else throw it
    }

    fun meetingActions(
        meetingId: UUID,
        by: UserAccount,
    ) = asUser(by) { actionAccess.ofMeeting(meetingId) }.actions.map { it.id }

    fun mine(by: UserAccount) = asUser(by) { actionAccess.mine(com.memo.actions.internal.MyActionsTab.MINE, null, null, 50) }

    /** The 422 codes of [block] as [by]. */
    fun rejectedCodes(
        by: UserAccount = secretary,
        block: () -> Any,
    ): List<String> =
        runCatching { asUser(by, block) }.exceptionOrNull().let {
            (it as? ActionRejectedException)?.problems?.map { p -> p.code } ?: throw AssertionError("not rejected", it)
        }

    /** The 409 reason of [block]. */
    fun refusedReason(
        by: UserAccount = secretary,
        block: () -> Any,
    ): String = (runCatching { asUser(by, block) }.exceptionOrNull() as? RefusedException)?.reason ?: throw AssertionError("not refused")

    /** "403" plus the reason ("403 RECUSED"), or "404". */
    fun deniedAs(
        by: UserAccount,
        block: () -> Any,
    ): String =
        when (val e = runCatching { asUser(by, block) }.exceptionOrNull()) {
            is ForbiddenException -> "403 ${e.reason.orEmpty()}".trim()
            is ActionNotFoundException -> "404"
            else -> if (e?.javaClass?.simpleName == "MeetingNotFoundException") "404" else throw AssertionError("not denied", e)
        }

    /** [block] as [account] in the tenant, outside any transaction (the controller opens its own). */
    fun <T> signedIn(
        account: UserAccount,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(account.id.value), null)) { TenantContext.call(tenant.id) { block() } }
}
