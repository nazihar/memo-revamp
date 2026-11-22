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
