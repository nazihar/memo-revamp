package com.memo.demo

import com.memo.actions.ActionSeeding
import com.memo.actions.SeedAction
import com.memo.actions.SeedOwner
import com.memo.actions.SeededAction
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.demo.DemoMeetingSeeder.Companion.BOARD_MEMBER
import com.memo.demo.DemoMeetingSeeder.Companion.DIRECTOR
import com.memo.demo.DemoMeetingSeeder.Companion.HEAD_FINANCE
import com.memo.demo.DemoMeetingSeeder.Companion.MEGHNA
import com.memo.demo.DemoMeetingSeeder.Companion.SECRETARY
import com.memo.demo.DemoMinutesSeeder.Companion.RUPSHA
import com.memo.identity.UserAccounts
import com.memo.meeting.AgendaSeeding
import com.memo.meeting.AttendanceSeeding
import com.memo.meeting.DeclarationSeeding
import com.memo.meeting.MeetingSeeding
import com.memo.meeting.MeetingStatus
import com.memo.memo.MemoSeeding
import com.memo.org.OrgDirectory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/**
 * Phase 4 E07 demo data (T12), for [DemoMeetingSeeder]: action items on E06's two held meetings, a follow-up memo, and the
 * action taken report on the Board Risk Committee's next agenda.
 *
 * - **MCC A1/…**, from the first condition of MCC/…/001 (the Meghna Foods limit: the charge registered before the first
 *   drawing), owned by the position Head of Credit Risk (held by Laila Arjumand, a demo account): she raised a follow-up
 *   memo from it, which Demo Director approved, then marked the action done with the registration certificate as
 *   evidence, and the company secretary verified and closed it. **Closed with evidence.**
 * - **BRC A1/…**, raised under "Any other business" at the held "Risk review, third quarter" (an item discussed without
 *   a decision: the cyber insurance cover), owned by Demo Director, due by the next meeting: done, **waiting for
 *   verification**.
 * - **BRC A2/…**, from the Rupsha Steel item (which Demo Director stepped out for, so they never see it; an item with a
 *   decision raises from its decision, so the action names its resolution), owned by Head of Credit Risk, due two days
 *   after that meeting:
 *   **overdue and escalated** to the holder's supervisor (the Chief Risk Officer), the engine's timers caught up since
 *   ([ActionSeeding.catchUp]).
 * - **BRC A3/…**, a direct action on the chair's instruction (the contingency funding plan), owned by Demo Head of
 *   Finance: they asked for a later date, which the secretary agreed. **Revised date.**
 * - **BRC A4/…**, a direct action (an independent validation of the liquidity stress model), owned by Demo Head of Finance:
 *   **cancelled, the risk accepted** by the chair, Demo Board Member.
 * - **The quarterly risk review's agenda** (BRC's next meeting) gets a third revision with the cyber insurance summary on
 *   "Matters arising and actions", agreed late by the chair; its publication builds the action taken report, which
 *   lists BRC A1 to close, BRC A2 overdue, BRC A3 open and BRC A4 cancelled since the last meeting.
 *
 * Fills gaps only: each action is found by its title on its committee and taken on from where it stands; the report is
 * published again only when its latest one lacks the overdue action. A step that cannot be taken (a meeting held before
 * E06 on an older environment has no decision with conditions; a holder without sign-in cannot own an action) is logged
 * and left. Through the same services as the API, as the people who would act; the seed acts outside a session, so no
 * step-up is asked for.
 */
@Component
class DemoActionsSeeder(
    private val actions: ActionSeeding,
    private val declarations: DeclarationSeeding,
    private val attendance: AttendanceSeeding,
    private val agendas: AgendaSeeding,
    private val meetings: MeetingSeeding,
    private val memos: MemoSeeding,
    private val accounts: UserAccounts,
    private val org: OrgDirectory,
    private val transactions: TenantTransactions,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * The actions of the two held meetings and the next BRC agenda's report, each only where it is missing (see the class);
     * [forums] are the risk and the credit committee.
     */
    fun seed(
        ids: Map<String, UUID>,
        forums: Pair<UUID, UUID>,
        riskReview: UUID,
        creditReview: UUID,
        nextRiskReview: UUID,
    ) {
        val people = ids + mapOf(HEAD_CREDIT_RISK to person(DemoOrg.staffEmail(HEAD_CREDIT_RISK_NAME)))
        val today = ActorContext.asSystem("demo-actions") { transactions.inTenant(DemoTenant.ID) { org.today() } }
        val risk = Scene(people, forums.first, riskReview, asUser(people.getValue(SECRETARY)) { attendance.date(riskReview) }, today)
        val credit = Scene(people, forums.second, creditReview, asUser(people.getValue(SECRETARY)) { attendance.date(creditReview) }, today)
        val next = asUser(people.getValue(SECRETARY)) { attendance.date(nextRiskReview) }
        step("the charge condition's action") { chargeAction(credit) }
        step("the cyber insurance action") { insuranceAction(risk, next) }
        step("the Rupsha Steel action") { rupshaAction(risk) }
        step("the contingency funding plan action") { fundingPlanAction(risk) }
        step("the stress model action") { stressModelAction(risk) }
        step("the next risk review's action taken report") { report(risk, nextRiskReview) }
    }

    /** MCC A1: from the first condition, the follow-up memo approved, done with evidence and closed (see the class). */
    private fun chargeAction(s: Scene) {
        val forum = s.forum()
        var action = s.found(forum, CHARGE)
        if (action == null) {
            val meghna = s.item(MEGHNA) ?: return log.warn("Demo actions: the held credit review has no Meghna Foods item")
            val id =
                s.secretary {
                    actions.raiseFromCondition(
                        s.meeting,
                        meghna,
                        0,
                        SeedAction(CHARGE, SeedOwner(positionId = HEAD_CREDIT_RISK_POSITION), s.day.plusDays(CHARGE_DAYS), CHARGE_CONTEXT),
                    )
                }
            s.secretary { actions.issue(id, s.day.plusDays(CHARGE_DAYS), s.day) }
            action = s.found(forum, CHARGE)
        }
        val id = checkNotNull(action).id
        if (action.status in UNDERWAY && s.owner { actions.memos(id) }.isEmpty()) followUpMemo(s, id)
        if (action.status in UNDERWAY) {
            s.owner { actions.update(id, CHARGE_UPDATE, "ON_TRACK") }
            val certificate = s.owner { actions.evidence(id, CERTIFICATE_FILE, DemoPdf.of(CERTIFICATE_TITLE, CERTIFICATE_LINES)) }
            s.owner { actions.done(id, CHARGE_DONE, listOf(certificate)) }
        }
        if (s.found(forum, CHARGE)?.status == DONE_PENDING) s.secretary { actions.close(id, CHARGE_CLOSED) }
    }

    /** Laila's General memo from MCC A1, approved by Demo Director (not its initiator). */
    private fun followUpMemo(
        s: Scene,
        actionId: UUID,
    ) {
        val type = asUser(s.people.getValue(SECRETARY)) { memos.generalType() }
        val memo = s.owner { actions.raiseMemo(actionId, type, MEMO_SUBJECT) }
        s.owner {
            memos.document(memo, MEMO_FILE, DemoDocx.of(MEMO_SUBJECT, MEMO_LINES))
            memos.route(memo, listOf(s.people.getValue(DIRECTOR)))
            memos.submit(memo, "For your approval: the condition of MCC/${s.day.year}/001 is met.")
        }
        asUser(s.people.getValue(DIRECTOR)) { memos.act(memo, MemoSeeding.APPROVE, "Approved: the first drawing may proceed.") }
    }

    /** BRC A1: from "Any other business", Demo Director's, done and waiting for verification. */
    private fun insuranceAction(
        s: Scene,
        next: LocalDate,
    ) {
        val forum = s.forum()
        val due = maxOf(next, s.today)
        val action =
            s.found(forum, INSURANCE) ?: run {
                val aob = s.item(AOB) ?: return log.warn("Demo actions: the held risk review has no item for any other business")
                val owner = SeedOwner(userId = s.people.getValue(DIRECTOR))
                val id = s.secretary { actions.raiseFromItem(s.meeting, aob, SeedAction(INSURANCE, owner, due, INSURANCE_CONTEXT)) }
                s.secretary { actions.issue(id, due) }
                checkNotNull(s.found(forum, INSURANCE))
            }
        val director = s.people.getValue(DIRECTOR)
        if (action.status == OPEN) asUser(director) { actions.update(action.id, INSURANCE_UPDATE, "ON_TRACK") }
        if (action.status in UNDERWAY) asUser(director) { actions.done(action.id, INSURANCE_DONE) }
    }

    /** BRC A2: from the Rupsha Steel item, Head of Credit Risk's, due two days after the meeting: overdue and escalated. */
    private fun rupshaAction(s: Scene) {
        val forum = s.forum()
        val due = DemoMeetingSeeder.weekday(s.day.plusDays(RUPSHA_DAYS))
        val action =
            s.found(forum, RUPSHA_BORROWINGS) ?: run {
                val rupsha = s.item(RUPSHA) ?: return log.warn("Demo actions: the held risk review has no Rupsha Steel item")
                val owner = SeedOwner(positionId = HEAD_CREDIT_RISK_POSITION)
                val id = s.secretary { actions.raiseFromItem(s.meeting, rupsha, SeedAction(RUPSHA_BORROWINGS, owner, due, RUPSHA_CONTEXT)) }
                s.secretary { actions.issue(id, due, s.day) }
                checkNotNull(s.found(forum, RUPSHA_BORROWINGS))
            }
        if (action.status in UNDERWAY) {
            // The engine's timers since the date passed: overdue at once, and the escalation ten working days later.
            ActorContext.asSystem("demo-actions") { TenantContext.call(DemoTenant.ID) { actions.catchUp(action.id, escalate = true) } }
        }
    }

    /** BRC A3: direct, Demo Head of Finance's, a later date asked for and agreed by the secretary. */
    private fun fundingPlanAction(s: Scene) {
        val forum = s.forum()
        val due = s.today.plusDays(FUNDING_DAYS)
        val action =
            s.found(forum, FUNDING_PLAN) ?: run {
                val owner = SeedOwner(userId = s.people.getValue(HEAD_FINANCE))
                val draft = SeedAction(FUNDING_PLAN, owner, due, FUNDING_CONTEXT, sourceNote = "Chair's instruction of ${s.day}")
                val id = s.secretary { actions.raiseDirect(forum, draft) }
                s.secretary { actions.issue(id, due) }
                checkNotNull(s.found(forum, FUNDING_PLAN))
            }
        if (action.status in UNDERWAY && action.dueRevisions == 0) {
            val request =
                asUser(s.people.getValue(HEAD_FINANCE)) {
                    actions.requestDate(action.id, due.plusDays(FUNDING_EXTRA_DAYS), FUNDING_REASON)
                }
            s.secretary { actions.agreeDate(action.id, request, "Agreed: the plan follows the new stress scenarios.") }
        }
    }

    /** BRC A4: direct, Demo Head of Finance's, cancelled by the chair with the risk accepted. */
    private fun stressModelAction(s: Scene) {
        val forum = s.forum()
        val due = s.today.plusDays(STRESS_DAYS)
        val action =
            s.found(forum, STRESS_MODEL) ?: run {
                val owner = SeedOwner(userId = s.people.getValue(HEAD_FINANCE))
                val draft = SeedAction(STRESS_MODEL, owner, due, STRESS_CONTEXT, sourceNote = "Chair's instruction of ${s.day}")
                val id = s.secretary { actions.raiseDirect(forum, draft) }
                s.secretary { actions.issue(id, due) }
                checkNotNull(s.found(forum, STRESS_MODEL))
            }
        if (action.status in UNDERWAY) asUser(s.people.getValue(BOARD_MEMBER)) { actions.cancelRiskAccepted(action.id, STRESS_REASON) }
    }

    /**
     * The next risk review's third revision: the cyber insurance summary on "Matters arising and actions", agreed late
     * by the chair, published, so the action taken report lists the committee's actions; unless its latest report lists
     * the overdue action already, or the meeting is no longer to come.
     */
    private fun report(
        s: Scene,
        meeting: UUID,
    ) {
        val overdue = s.found(s.forum(), RUPSHA_BORROWINGS) ?: return
        if (overdue.id in s.secretary { actions.reported(meeting) }) return
        if (s.secretary { meetings.status(meeting) } != MeetingStatus.SCHEDULED) {
            return log.warn("Demo actions: the quarterly risk review is not scheduled, so its report is not published again")
        }
        val item = s.secretary { declarations.item(meeting, MATTERS_ARISING) } ?: return log.warn("Demo actions: no matters arising item")
        s.secretary { agendas.paper(meeting, item, FIGURES_FILE, DemoPdf.of(FIGURES_TITLE, FIGURES_LINES)) }
        asUser(s.people.getValue(BOARD_MEMBER)) { agendas.agreeLatePapers(meeting) }
        s.secretary { agendas.publish(meeting, PUBLISH_NOTE) }
    }

    /** One held meeting, the people acting and the days that matter. */
    private inner class Scene(
        val people: Map<String, UUID>,
        /** The committee. */
        val forumId: UUID,
        val meeting: UUID,
        /** The day it was held on. */
        val day: LocalDate,
        val today: LocalDate,
    ) {
        fun <T> secretary(block: () -> T): T = asUser(people.getValue(SECRETARY), block)

        fun <T> owner(block: () -> T): T = asUser(people.getValue(HEAD_CREDIT_RISK), block)

        fun forum(): UUID = forumId

        fun item(title: String): UUID? = secretary { declarations.item(meeting, title) }

        fun found(
            forum: UUID,
            title: String,
        ): SeededAction? = secretary { actions.find(forum, title) }
    }

    private fun person(email: String): UUID =
        ActorContext.asSystem("demo-actions") {
            transactions.inTenant(DemoTenant.ID) { checkNotNull(accounts.findByEmail(email)) { "demo user $email missing" }.id.value }
        }

    /** Runs [what]; a refusal is logged and the rest of the seed goes on. */
    @Suppress("TooGenericExceptionCaught")
    private fun step(
        what: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: RuntimeException) {
            log.warn("Demo actions: {} not seeded: {}", what, e.toString())
        }
    }

    private fun <T> asUser(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { TenantContext.call(DemoTenant.ID, block) }

    companion object {
        /** The demo account holding Head of Credit Risk (a staff seat given sign-in for E07 T12). */
        const val HEAD_CREDIT_RISK = "head.credit.risk"
        const val HEAD_CREDIT_RISK_NAME = "Laila Arjumand"
        val HEAD_CREDIT_RISK_POSITION: UUID = DemoOrg.unitByCode("RSK-CR").headPosition
        const val MATTERS_ARISING = "Matters arising and actions"

        const val CHARGE = "Register the charge over Meghna Foods' stock and receivables before the first drawing"
        const val CHARGE_CONTEXT =
            "The committee approved the limit on condition that a first charge over the company's stock and receivables is " +
                "registered before the first drawing."
        const val CHARGE_UPDATE = "The charge documents are signed; filing with the registrar is booked for this week."
        const val CHARGE_DONE = "The charge is registered; the certificate is attached and the follow-up memo approved."
        const val CHARGE_CLOSED = "Certificate checked against the condition. Closed."
        const val CERTIFICATE_FILE = "charge-registration-certificate.pdf"
        const val CERTIFICATE_TITLE = "Certificate of registration of a charge"
        val CERTIFICATE_LINES =
            listOf("Company: Meghna Foods Ltd. Charge: first fixed and floating charge over stock and receivables.", "Synthetic demo file.")
        const val MEMO_SUBJECT = "Meghna Foods: charge registered, first drawing may proceed"
        const val MEMO_FILE = "meghna-foods-charge-registered.docx"
        val MEMO_LINES =
            listOf(
                "The first charge over Meghna Foods Ltd's stock and receivables is registered (certificate on file).",
                "The condition of the committee's resolution is met: we ask approval for the first drawing to proceed.",
            )

        /** The held risk review's last item, discussed without a decision. */
        const val AOB = "Any other business (notified in advance)"
        const val INSURANCE = "Brief the committee on the renewal of the cyber insurance cover"
        const val INSURANCE_CONTEXT =
            "Raised under any other business: the chair asked for a summary of the renewed cover and its exclusions."
        const val INSURANCE_UPDATE = "The broker's summary of the renewed cover is due this week."
        const val INSURANCE_DONE = "The summary of the renewed cover is in the papers for matters arising at the next meeting."

        const val RUPSHA_BORROWINGS = "Report on the Rupsha Steel group's other borrowings and the steel price outlook"
        const val RUPSHA_CONTEXT = "The committee asked about the group's other borrowings and the steel price outlook."

        const val FUNDING_PLAN = "Refresh the contingency funding plan for the new liquidity stress scenarios"
        const val FUNDING_CONTEXT = "The chair asked for the plan to be refreshed once the new scenarios are agreed."
        const val FUNDING_REASON = "The new stress scenarios are agreed only at the end of next month."

        const val STRESS_MODEL = "Commission an independent validation of the liquidity stress model"
        const val STRESS_CONTEXT = "The chair asked whether the stress model should be validated outside the bank this year."
        const val STRESS_REASON =
            "The committee accepts the model risk until the 2027 model review; the external validation is not commissioned this year."

        const val FIGURES_FILE = "cyber-insurance-cover-summary.pdf"
        const val FIGURES_TITLE = "Cyber insurance: summary of the renewed cover"
        val FIGURES_LINES = listOf("For matters arising (the committee's action on the cyber insurance cover).", "Synthetic demo paper.")
        const val PUBLISH_NOTE = "The cyber insurance summary for matters arising, agreed late by the chair"

        private const val CHARGE_DAYS = 30L
        private const val RUPSHA_DAYS = 2L
        private const val FUNDING_DAYS = 14L
        private const val FUNDING_EXTRA_DAYS = 28L
        private const val STRESS_DAYS = 45L
        private const val OPEN = "OPEN"
        private const val DONE_PENDING = "DONE_PENDING"
        private val UNDERWAY = setOf(OPEN, "IN_PROGRESS")
    }
}
