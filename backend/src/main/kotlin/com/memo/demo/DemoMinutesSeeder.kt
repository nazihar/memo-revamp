package com.memo.demo

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.demo.DemoMeetingSeeder.Companion.DIRECTOR
import com.memo.demo.DemoMeetingSeeder.Companion.HEAD_CREDIT
import com.memo.demo.DemoMeetingSeeder.Companion.HEAD_FINANCE
import com.memo.demo.DemoMeetingSeeder.Companion.MEGHNA
import com.memo.demo.DemoMeetingSeeder.Companion.PORTFOLIO
import com.memo.demo.DemoMeetingSeeder.Companion.SECRETARY
import com.memo.demo.DemoMeetingSeeder.Companion.WATCH_LIST
import com.memo.forum.MinutesApproval
import com.memo.forum.OutcomeKind
import com.memo.meeting.AttendanceSeeding
import com.memo.meeting.DeclarationSeeding
import com.memo.minutes.DecisionSeeding
import com.memo.minutes.MinutesSeeding
import com.memo.minutes.SeedDecision
import com.memo.minutes.SeedVote
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalTime
import java.util.UUID

/**
 * Phase 4 E06 demo data (T12), for [DemoMeetingSeeder]: decisions, numbered resolutions, minutes and an extract on the two
 * held meetings.
 *
 * - **The Board Risk Committee's "Risk review, third quarter"**, held about three and a half weeks ago (seeded by
 *   [DemoHeldRiskReviewSeeder], before the quarterly review so that it is numbered first): Demo Director declared an
 *   interest in the Rupsha Steel exposure and stepped out for it. The exposure and the cyber risk metrics were approved,
 *   and the consent section was decided in one action (the policy approved, the incidents noted): BRC/…/001 to 003. The
 *   minutes, approved at the next meeting by this
 *   committee type, were drafted, agreed by the chair and circulated, so they are linked to the quarterly review's "Minutes
 *   of the last meeting": the director reads a copy without the Rupsha Steel discussion, and commented on the cyber item.
 * - **The Management Credit Committee's "Monthly credit review"** (E05): the Meghna Foods limit (the credit memo, on a fresh
 *   seed) approved with two conditions by a vote of 2–1, Demo Head of Finance's dissent recorded with their reason, Demo
 *   Head of Credit taking no part: MCC/…/001, and the memo moved on with its number at once. The watch-list review deferred
 *   to the next meeting. The portfolio report noted at 15:34 while the meeting was not quorate (the vice-chair's
 *   connection had dropped), under the management committee type's override with a reason, to be ratified. The minutes,
 *   approved by circulation, were agreed by the chair, answered "No comments" by the members, closed early by the
 *   secretary for the CRO's answer by phone and approved by the chair: the record is made by its job. A certified extract
 *   of MCC/…/001 was issued.
 *
 * Fills gaps only: a decision is recorded on an item without one, and each step of the minutes is taken from where they
 * stand. A step that cannot be taken (a meeting held before its committee type allowed it, on an older environment) is
 * logged and left. The approvals and the extract record no authentication factors: the seed acts outside a session. Needs
 * the files service (the draft is stored through it) and a document server for the copies and the record (their jobs).
 */
@Component
class DemoMinutesSeeder(
    private val declarations: DeclarationSeeding,
    private val attendance: AttendanceSeeding,
    private val decisions: DecisionSeeding,
    private val minutes: MinutesSeeding,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** The decisions and minutes of the two held meetings, each only where it is missing (see the class). */
    fun seed(
        ids: Map<String, UUID>,
        riskReview: UUID,
        creditReview: UUID,
    ) {
        step("risk review decisions") { riskDecisions(ids, riskReview) }
        step("credit review decisions") { creditDecisions(ids, creditReview) }
        step("risk review minutes") { riskMinutes(ids, riskReview) }
        step("credit review minutes") { creditMinutes(ids, creditReview) }
    }

    private fun riskDecisions(
        ids: Map<String, UUID>,
        meeting: UUID,
    ) {
        val secretary = ids.getValue(SECRETARY)
        val day = asUser(secretary) { attendance.date(meeting) }
        val at = { time: String -> DemoMeetingSeeder.at(day, LocalTime.parse(time)) }
        decide(secretary, meeting, RUPSHA) { item ->
            decisions.notes(meeting, item, RUPSHA_NOTES)
            SeedDecision(
                OutcomeKind.APPROVED,
                at("10:45"),
                text =
                    "It was RESOLVED that the exposure of BDT 1.2 billion to Rupsha Steel Mills Ltd above the single-borrower " +
                        "limit be approved for twelve months.",
            )
        }
        decide(secretary, meeting, CYBER) { item ->
            decisions.notes(meeting, item, CYBER_NOTES)
            SeedDecision(
                OutcomeKind.APPROVED,
                at("11:10"),
                text = "It was RESOLVED that the four cyber risk appetite metrics be adopted from the first day of next month.",
            )
        }
        val policy = asUser(secretary) { declarations.item(meeting, POLICY) }
        if (policy != null && !asUser(secretary) { decisions.decided(policy) }) {
            asUser(secretary) { decisions.decideConsent(meeting, at("11:20")) }
        }
    }

    private fun creditDecisions(
        ids: Map<String, UUID>,
        meeting: UUID,
    ) {
        val secretary = ids.getValue(SECRETARY)
        val day = asUser(secretary) { attendance.date(meeting) }
        val at = { time: String -> DemoMeetingSeeder.at(day, LocalTime.parse(time)) }
        decide(secretary, meeting, MEGHNA) { item ->
            decisions.notes(meeting, item, MEGHNA_NOTES)
            SeedDecision(
                OutcomeKind.APPROVED_WITH_CONDITIONS,
                DemoMeetingSeeder.at(day, DemoMeetingAttendanceSeeder.SNAPSHOT_AT),
                text =
                    "It was RESOLVED that the working capital limit of BDT 80 million for Meghna Foods Ltd be renewed for twelve " +
                        "months, on the conditions below.",
                conditions = MEGHNA_CONDITIONS,
                vote = SeedVote(votesFor = 2, votesAgainst = 1),
                dissent = mapOf(ids.getValue(HEAD_FINANCE) to MEGHNA_DISSENT),
            )
        }
        decide(secretary, meeting, WATCH_LIST) {
            SeedDecision(OutcomeKind.DEFERRED, at("15:10"), deferToNextMeeting = true)
        }
        decide(secretary, meeting, PORTFOLIO) {
            SeedDecision(OutcomeKind.NOTED, at("15:34"), overrideReason = OVERRIDE_REASON)
        }
    }

    /** Records [decision] on [title] as [secretary] unless the item is missing or decided; a refusal is logged. */
    private fun decide(
        secretary: UUID,
        meeting: UUID,
        title: String,
        decision: (UUID) -> SeedDecision,
    ) {
        val item = asUser(secretary) { declarations.item(meeting, title) } ?: return
        if (asUser(secretary) { decisions.decided(item) }) return
        step("decision on $title") { asUser(secretary) { decisions.decide(meeting, item, decision(item)) } }
    }

    /** At the next meeting: drafted, agreed by the chair and circulated; the recused director's comment. */
    private fun riskMinutes(
        ids: Map<String, UUID>,
        meeting: UUID,
    ) {
        val secretary = ids.getValue(SECRETARY)
        val director = ids.getValue(DIRECTOR)
        toCirculation(secretary, meeting)
        if (stage(secretary, meeting) == CIRCULATED) {
            val cyber = asUser(secretary) { declarations.item(meeting, CYBER) }
            asUser(director) { minutes.comment(meeting, cyber, DIRECTOR_COMMENT) }
        }
    }

    /** By circulation: drafted, agreed, answered, closed early, approved by the chair; an extract of the limit's resolution. */
    private fun creditMinutes(
        ids: Map<String, UUID>,
        meeting: UUID,
    ) {
        val secretary = ids.getValue(SECRETARY)
        val chair = ids.getValue(DIRECTOR)
        toCirculation(secretary, meeting)
        val state = asUser(secretary) { minutes.minutes(meeting) }
        if (state != null && state.approval != MinutesApproval.BY_CIRCULATION) {
            log.warn("Demo credit review: its minutes are approved at the next meeting (held before E06's committee settings)")
        } else {
            if (stage(secretary, meeting) == CIRCULATED) {
                listOf(chair, ids.getValue(HEAD_CREDIT), ids.getValue(HEAD_FINANCE)).forEach { asUser(it) { minutes.noComments(meeting) } }
            }
            if (stage(secretary, meeting) == CIRCULATED) asUser(secretary) { minutes.endCirculation(meeting, CLOSED_EARLY) }
            if (stage(secretary, meeting) == AWAITING_CHAIR) asUser(chair) { minutes.confirm(meeting) }
            extract(secretary, meeting)
        }
    }

    /** A certified extract of the limit's resolution once the minutes are approved, unless it has one. */
    private fun extract(
        secretary: UUID,
        meeting: UUID,
    ) {
        val meghna = asUser(secretary) { declarations.item(meeting, MEGHNA) }
        val resolution = meghna?.let { asUser(secretary) { decisions.resolution(it) } }?.second
        if (resolution != null && stage(secretary, meeting) == APPROVED && !asUser(secretary) { decisions.extracted(resolution) }) {
            asUser(secretary) { decisions.issueExtract(resolution) }
        }
    }

    /** Drafts, sends to the chair and has them agree, as far as the minutes have not gone yet. */
    private fun toCirculation(
        secretary: UUID,
        meeting: UUID,
    ) {
        val state = asUser(secretary) { minutes.minutes(meeting) }
        val undrafted = state == null || (state.stage == DRAFT && !state.drafted)
        if (undrafted && !asUser(secretary) { minutes.draft(meeting) }) {
            log.warn("Demo minutes of meeting {}: the draft was not made", meeting)
            return
        }
        if (stage(secretary, meeting) == DRAFT) asUser(secretary) { minutes.sendForReview(meeting) }
        if (stage(secretary, meeting) == CHAIR_REVIEW) {
            val chair = checkNotNull(asUser(secretary) { minutes.minutes(meeting) }?.reviewerId) { "reviewer of $meeting" }
            asUser(chair) { minutes.agree(meeting) }
        }
    }

    private fun stage(
        secretary: UUID,
        meeting: UUID,
    ): String? = asUser(secretary) { minutes.minutes(meeting) }?.stage

    /** Runs [what]; a refusal is logged and the rest of the seed goes on. */
    @Suppress("TooGenericExceptionCaught")
    private fun step(
        what: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: RuntimeException) {
            log.warn("Demo minutes: {} not seeded: {}", what, e.toString())
        }
    }

    private fun <T> asUser(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { TenantContext.call(DemoTenant.ID, block) }

    companion object {
        const val RISK_TITLE = "Risk review, third quarter"
        const val RUPSHA = "Exposure above the single-borrower limit, Rupsha Steel"
        const val CYBER = "Cyber risk appetite metrics"
        const val POLICY = "Credit risk policy: annual review, no changes"
        const val INCIDENTS = "Operational risk incidents, second quarter"
        const val RUPSHA_INTEREST = "My cousin is the finance director of Rupsha Steel Mills."
        const val RUPSHA_NOTES =
            "The CRO presented the exposure. The committee asked about the group's other borrowings and the steel price " +
                "outlook."
        const val CYBER_NOTES = "The CRO explained the four metrics. The committee asked for the first quarter's figures."
        const val DIRECTOR_COMMENT =
            "Please record that the committee asked for the first quarter's figures at its next meeting."
        const val MEGHNA_NOTES =
            "Demo Head of Finance asked about the company's leverage; the credit desk answered on the stock levels."
        const val MEGHNA_DISSENT = "The company's leverage has risen for two years running."
        val MEGHNA_CONDITIONS =
            listOf(
                "A first charge over the company's stock and receivables, registered before the first drawing.",
                "Quarterly stock reports to the credit desk.",
            )
        const val OVERRIDE_REASON =
            "Noted while Demo Head of Credit's connection was being restored, so that the report's figures could be sent " +
                "on today; to be ratified at the next meeting."
        const val CLOSED_EARLY = "The Chief Risk Officer told the secretary by phone that they have no comments."

        /** The minutes' stages, as [MinutesSeeding] names them. */
        const val DRAFT = "DRAFT"
        const val CHAIR_REVIEW = "CHAIR_REVIEW"
        const val CIRCULATED = "CIRCULATED"
        const val AWAITING_CHAIR = "AWAITING_CHAIR"
        const val APPROVED = "APPROVED"
    }
}
