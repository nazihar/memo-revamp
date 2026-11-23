package com.memo.demo

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumSeeding
import com.memo.forum.SubmissionPurpose
import com.memo.identity.UserAccounts
import com.memo.meeting.AgendaSeeding
import com.memo.meeting.DeclarationHow
import com.memo.meeting.DeclarationIntent
import com.memo.meeting.DeclarationSeeding
import com.memo.meeting.MeetingSeeding
import com.memo.meeting.MeetingStatus
import com.memo.meeting.SeedItem
import com.memo.memo.MemoAccessSeeding
import com.memo.memo.MemoSeeding
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Phase 4 E03 demo data (T9): two meetings that show the agenda builder and the meeting page with something in them.
 *
 * - The Board Risk Committee's quarterly risk review: published twice. Revision 1, agreed by the chair (recorded by the
 *   secretary on their behalf), has a consent section, an item whose papers only the chair reads, an in-camera section
 *   and a guest (the CFO); Demo Director has read it and asked to discuss a consent item. The meeting was then brought
 *   forward past its paper deadline, so revision 2 carries a replaced paper and a late paper the chair agreed in the app.
 * - The Management Credit Committee's meeting: scheduled, with a BDT 15 million credit proposal of Demo User's placed on
 *   its draft agenda from the committee's queue (the memo escalated there by "Credit under authority", E02 T5).
 *
 * Phase 4 E04 T9 adds declarations of interest. At the risk review, Demo Director declared in the app on "Term loan, Delta
 * Textiles" and leaves the room (recused), asked the chair to decide on a consent item (waiting for the ruling), and
 * confirmed the review of revision 2; the secretary recorded the Chairman's declaration "by email" on the large exposures
 * item. At the credit committee, Demo Head of Credit stood aside on the proposal at the credit desk (an interest on the
 * memo, SOD-4) and Demo Director recommended it, so placing it carried a declaration to the item: the vice-chair is
 * recused from it.
 *
 * Phase 4 E05 T10 adds replies and a held meeting. At the risk review, Demo Director sent apologies and their alternate,
 * Demo Head of Credit, stands in; the CFO (a guest) sent apologies; the secretary recorded the Chairman's yes "by phone";
 * and the chair's yes was given for the planned date, so it is stale since the meeting was brought forward. The credit
 * committee's "Monthly credit review", hybrid, was held about ten days ago and its register kept as it happened: the CRO
 * arrived late, Demo Head of Credit (online) stepped out for "Working capital limit, Meghna Foods", the item they declared
 * an interest in, Demo Head of Finance left early, and the meeting lost its quorum when the vice-chair's connection dropped
 * and had it restored eight minutes later. The Meghna Foods decision's quorum snapshot is kept for E06's minutes.
 *
 * Phase 4 E06 T12 ([DemoMinutesSeeder]) adds the Board Risk Committee's held "Risk review, third quarter" (seeded before the
 * quarterly review, so it has the earlier number) and the two held meetings' decisions, resolutions and minutes. On a new
 * seed, "Working capital limit, Meghna Foods" is Demo User's credit memo of BDT 80 million, sent to the credit committee
 * through the credit desk (recommended by Demo Head of Credit, who declares their interest at the meeting, as before), so
 * the committee's decision moves the memo on with its resolution number to the delegation of authority (Demo Director
 * and the head of finance's seat, neither of whom acted on it at the desk).
 *
 * Dates are relative to the day of seeding, so on a long-lived environment the meetings drift into the past. Fills gaps
 * only: a meeting already there (by its title, in any state) is left as it is. Needs the files service (scanner) and the
 * demo password (the people acting must exist with sign-in), like the demo memos.
 */
@Component
class DemoMeetingSeeder(
    private val meetings: MeetingSeeding,
    private val agendas: AgendaSeeding,
    private val declarations: DeclarationSeeding,
    private val e05: DemoMeetingAttendanceSeeder,
    private val e06: DemoMinutesSeeder,
    private val memos: MemoSeeding,
    private val memoAccess: MemoAccessSeeding,
    private val forums: ForumSeeding,
    private val directory: ForumDirectory,
    private val accounts: UserAccounts,
    private val transactions: TenantTransactions,
    private val clock: Clock,
    @param:Value("\${memo.demo.password:}") private val demoPassword: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Seeds what is missing; the three meetings' ids, or null when the demo password is not set. */
    fun seed(): Seeded? {
        if (demoPassword.isBlank()) return null
        val ids = ActorContext.asSystem("demo-meetings") { transactions.inTenant(DemoTenant.ID) { people() } }
        val risk = forumId(ids, DemoForumSeeder.RISK)
        val credit = forumId(ids, DemoForumSeeder.CREDIT)
        val today = LocalDate.now(clock.withZone(ZONE))
        val heldRisk =
            asUser(ids.getValue(SECRETARY)) { meetings.find(risk, DemoMinutesSeeder.RISK_TITLE) } ?: e06.heldRiskReview(ids, risk, today)
        val riskMeeting = asUser(ids.getValue(SECRETARY)) { meetings.find(risk, RISK_TITLE) } ?: riskReview(ids, risk, today)
        val creditMeeting = asUser(ids.getValue(SECRETARY)) { meetings.find(credit, CREDIT_TITLE) } ?: creditMeeting(ids, credit, today)
        riskDeclarations(ids, risk, riskMeeting, today)
        e05.riskReplies(ids, risk, riskMeeting, today)
        val held =
            asUser(ids.getValue(SECRETARY)) { meetings.find(credit, HELD_TITLE) }
                ?: e05.heldCreditReview(ids, credit, today, meghnaSubmission(ids))
        e06.seed(ids, heldRisk, held)
        log.info("Demo meetings seeded")
        return Seeded(riskMeeting, creditMeeting, held, heldRisk)
    }

    /** The BRC's meeting with revisions 1 and 2 (see the class). */
    private fun riskReview(
        ids: Map<String, UUID>,
        forum: UUID,
        today: LocalDate,
    ): UUID {
        val secretary = ids.getValue(SECRETARY)
        val soon = weekday(today.plusDays(SOON_DAYS))
        val planned = soon.plusWeeks(PLANNED_WEEKS)
        val meeting =
            asUser(secretary) {
                val id = meetings.create(forum, RISK_TITLE, planned.atTime(RISK_START), planned.atTime(RISK_END), ZONE.id, RISK_ROOM)
                meetings.guest(id, ids.getValue(HEAD_FINANCE), "Chief Financial Officer")
                id
            }
        val agenda = asUser(secretary) { riskAgenda(meeting, ids) }
        // Revision 1: the chair agreed by email, the secretary records it; the director reads it and lifts a consent item.
        asUser(secretary) {
            agendas.agreedOnBehalf(meeting, "By email on ${today.minusDays(1)}")
            meetings.schedule(meeting)
            agendas.publish(meeting, null)
        }
        asUser(ids.getValue(DIRECTOR)) {
            meetings.seen(meeting, 1)
            meetings.askToDiscuss(meeting, agenda.lifted)
        }
        // E05 T10: the chair said yes for the planned date; once the meeting is brought forward the answer is for the old time.
        e05.plannedDateAnswer(ids.getValue(BOARD_MEMBER), meeting)
        // Brought forward past the paper deadline: the late addendum waits for the chair, who agrees it in the app.
        asUser(secretary) {
            meetings.reschedule(meeting, soon.atTime(RISK_START), soon.atTime(RISK_END), ZONE.id, "Brought forward at the chair's request")
            agendas.replace(agenda.appetitePaper, APPETITE_FILE, pdf(APPETITE_TITLE, "Version 2: climate limits revised."))
            agendas.paper(meeting, agenda.liquidity, "liquidity-stress-addendum.pdf", pdf("Liquidity stress test addendum", "Late paper."))
        }
        if (!asUser(secretary) { meetings.deadlinePassed(meeting) }) log.warn("Demo risk review: the addendum is not late")
        asUser(ids.getValue(BOARD_MEMBER)) { agendas.agreeLatePapers(meeting) }
        asUser(secretary) { agendas.publish(meeting, "Updated risk appetite paper; a late liquidity addendum agreed by the chair") }
        return meeting
    }

    /** Revision 1's items and papers, as the bound secretary; the papers and items revision 2 touches. */
    private fun riskAgenda(
        meeting: UUID,
        ids: Map<String, UUID>,
    ): RiskAgenda {
        val appetite =
            agendas.item(
                meeting,
                SeedItem(
                    FOR_DECISION,
                    "Risk appetite statement 2027",
                    SubmissionPurpose.DECISION,
                    minutes = 30,
                    description = "The annual refresh of the bank's risk appetite, with new limits for climate risk.",
                    resolutionSought = "That the committee recommends the 2027 risk appetite statement to the Board.",
                    presenterId = ids.getValue(HEAD_CREDIT),
                ),
            )
        val appetitePaper = agendas.paper(meeting, appetite, APPETITE_FILE, pdf(APPETITE_TITLE, "Version 1."))
        val exposures =
            agendas.item(
                meeting,
                SeedItem(
                    FOR_DECISION,
                    EXPOSURES,
                    SubmissionPurpose.DECISION,
                    minutes = 20,
                    restrictedTo = listOf(ids.getValue(BOARD_MEMBER)),
                ),
            )
        agendas.paper(meeting, exposures, "large-exposures-q3.pdf", pdf("Large exposures, third quarter", "Restricted to the chair."))
        val delta =
            agendas.item(
                meeting,
                SeedItem(
                    FOR_DECISION,
                    DELTA,
                    SubmissionPurpose.DECISION,
                    minutes = 20,
                    description = "A BDT 450 million term loan to Delta Textiles Ltd, above the single-borrower limit.",
                    resolutionSought = "That the committee approves the exposure above the single-borrower limit.",
                ),
            )
        agendas.paper(meeting, delta, "delta-textiles-term-loan.pdf", pdf("Term loan, Delta Textiles", "Exposure BDT 450 million."))
        val incidents = agendas.item(meeting, SeedItem(CONSENT, INCIDENTS))
        agendas.paper(meeting, incidents, "operational-incidents-q3.pdf", pdf("Operational risk incidents", "Twelve incidents."))
        agendas.item(meeting, SeedItem(CONSENT, "Market risk policy: annual review, no changes", SubmissionPurpose.DECISION))
        val continuity = agendas.item(meeting, SeedItem(CONSENT, CONTINUITY))
        agendas.paper(meeting, continuity, "continuity-test.pdf", pdf("Business continuity test", "Data centre failover."))
        val liquidity = agendas.item(meeting, SeedItem(FOR_NOTING, "Liquidity and funding update", minutes = 15))
        agendas.paper(meeting, liquidity, "liquidity-update.pdf", pdf("Liquidity and funding update", "LCR 168 per cent."))
        agendas.section(meeting, IN_CAMERA, membersOnly = true)
        agendas.item(meeting, SeedItem(IN_CAMERA, "Chief Risk Officer: annual objectives", SubmissionPurpose.DECISION, IN_CAMERA_MINUTES))
        return RiskAgenda(appetitePaper, continuity, liquidity)
    }

    /**
     * The risk review's declarations (see the class), each only when its person never held one on its item and the meeting
     * is still scheduled, so a second run, a meeting already held, or one seeded before E04 without the Delta Textiles item
     * gets nothing it should not. On the latest revision, after revision 2.
     */
    private fun riskDeclarations(
        ids: Map<String, UUID>,
        forum: UUID,
        meeting: UUID,
        today: LocalDate,
    ) {
        val secretary = ids.getValue(SECRETARY)
        val director = ids.getValue(DIRECTOR)
        if (asUser(secretary) { meetings.status(meeting) } != MeetingStatus.SCHEDULED) return
        val item = { title: String -> asUser(secretary) { declarations.item(meeting, title) } }
        val missing = { itemId: UUID, user: UUID -> !asUser(secretary) { declarations.held(meeting, itemId, user) } }
        item(DELTA)?.takeIf { missing(it, director) }?.let {
            asUser(director) { declarations.declare(meeting, it, DELTA_INTEREST, DeclarationIntent.LEAVE) }
        }
        item(INCIDENTS)?.takeIf { missing(it, director) }?.let {
            asUser(director) { declarations.declare(meeting, it, INCIDENTS_INTEREST, DeclarationIntent.ASK_CHAIR) }
        }
        val chairman = e05.seatHolder(secretary, forum, "BOD")
        val exposures = item(EXPOSURES)
        if (chairman == null || exposures == null) {
            log.warn("Demo risk review: no Chairman or no large exposures item for the declaration by email")
        } else if (missing(exposures, chairman)) {
            asUser(secretary) {
                declarations.record(
                    meeting,
                    exposures,
                    chairman,
                    EXPOSURES_INTEREST,
                    DeclarationIntent.LEAVE,
                    DeclarationHow.EMAIL,
                    "Email of ${today.minusDays(1)}",
                )
            }
        }
        asUser(director) { declarations.confirmReview(meeting) }
    }

    /** What revision 2 of the risk review changes: the appetite paper, the consent item lifted, the item that gets a late paper. */
    private data class RiskAgenda(
        val appetitePaper: UUID,
        val lifted: UUID,
        val liquidity: UUID,
    )

    /** The MCC's meeting with the credit proposal placed from the queue (see the class). */
    private fun creditMeeting(
        ids: Map<String, UUID>,
        forum: UUID,
        today: LocalDate,
    ): UUID {
        val secretary = ids.getValue(SECRETARY)
        val memo = creditProposal(ids)
        val day = weekday(today.plusDays(CREDIT_DAYS))
        val meeting =
            asUser(secretary) {
                val id = meetings.create(forum, CREDIT_TITLE, day.atTime(CREDIT_START), day.atTime(CREDIT_END), ZONE.id, CREDIT_ROOM)
                meetings.schedule(id)
                id
            }
        val submission =
            asUser(secretary) { transactions.inTenant(DemoTenant.ID) { forums.unplacedSubmission(DemoForumSeeder.CREDIT, memo) } }
        if (submission == null) {
            log.warn("Demo credit proposal is not in the credit committee's queue")
        } else {
            asUser(secretary) { agendas.place(meeting, submission, CREDIT_MINUTES) }
        }
        return meeting
    }

    /** Demo User's credit proposal of BDT 15 million, through the credit desk and so to the credit committee; once. */
    private fun creditProposal(ids: Map<String, UUID>): UUID = creditMemo(ids, PADMA)

    /**
     * E06 T12: Demo User's BDT 80 million working capital memo for Meghna Foods, through the credit desk to the credit
     * committee's queue (once), for the held review; its submission there, or null when it is not in the queue (the review
     * then has an item of its own, as before E06).
     */
    private fun meghnaSubmission(ids: Map<String, UUID>): UUID? {
        val memo = creditMemo(ids, MEGHNA_MEMO)
        return asUser(ids.getValue(SECRETARY)) {
            transactions.inTenant(DemoTenant.ID) { forums.unplacedSubmission(DemoForumSeeder.CREDIT, memo) }
        }
    }

    /**
     * A credit memo of Demo User's as [spec] says, submitted and recommended at the credit desk (by the director when the
     * head of credit stands aside for an interest, else by the head of credit); once.
     */
    private fun creditMemo(
        ids: Map<String, UUID>,
        spec: CreditMemo,
    ): UUID {
        val author = ids.getValue(AUTHOR)
        asUser(author) { memoAccess.find(author, spec.subject) }?.let { return it }
        // The representative type exists (DemoWorkflowSeeder); an administrator looks it up.
        val type = asUser(ids.getValue(ADMIN)) { memos.fixedType(DemoWorkflowSeeder.CREDIT_PROPOSAL, DemoWorkflowSeeder.AUTHORITY_KEY) }
        val memo =
            asUser(author) {
                val id =
                    memos.draft(
                        spec.subject,
                        typeId = type,
                        metadata =
                            mapOf(
                                "customer" to spec.customer,
                                "amount" to mapOf("value" to spec.amount, "currency" to "BDT"),
                                "purpose" to spec.purpose,
                                "tenor" to spec.tenor,
                            ),
                    )
                memos.document(id, spec.file, DemoDocx.of(spec.subject, spec.lines))
                memos.submit(id, "For the committee's decision.")
                id
            }
        val interest = spec.interest
        if (interest != null) {
            // E04 T9: the head of credit has an interest and stands aside at the desk; the director, its other member, recommends.
            asUser(ids.getValue(HEAD_CREDIT)) { memos.act(memo, MemoSeeding.STAND_ASIDE, interest) }
            asUser(ids.getValue(DIRECTOR)) { memos.act(memo, MemoSeeding.APPROVE, "Recommended to the credit committee.") }
        } else {
            asUser(ids.getValue(HEAD_CREDIT)) { memos.act(memo, MemoSeeding.APPROVE, "Recommended to the credit committee.") }
        }
        return memo
    }

    private fun forumId(
        ids: Map<String, UUID>,
        key: String,
    ): UUID =
        checkNotNull(asUser(ids.getValue(SECRETARY)) { transactions.inTenant(DemoTenant.ID) { directory.forumByKey(key) } }) {
            "demo committee $key missing"
        }.id

    private fun people(): Map<String, UUID> =
        listOf(ADMIN, AUTHOR, SECRETARY, BOARD_MEMBER, DIRECTOR, HEAD_CREDIT, HEAD_FINANCE).associateWith {
            checkNotNull(accounts.findByEmail(it)) { "demo user $it missing" }.id.value
        }

    private fun <T> asUser(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { TenantContext.call(DemoTenant.ID, block) }

    /** The demo meetings. */
    data class Seeded(
        val riskReview: UUID,
        val creditMeeting: UUID,
        /** The credit committee's monthly review, held (E05 T10). */
        val heldCreditReview: UUID,
        /** The risk committee's review of the third quarter, held, with circulated minutes (E06 T12). */
        val heldRiskReview: UUID,
    )

    /** A credit memo the demo sends to the credit committee. */
    private data class CreditMemo(
        val subject: String,
        val customer: String,
        val amount: Int,
        val purpose: String,
        val tenor: Int,
        val file: String,
        val lines: List<String>,
        /** Demo Head of Credit's interest, for which they stand aside at the credit desk; null: they recommend it. */
        val interest: String?,
    )

    companion object {
        const val RISK_TITLE = "Quarterly risk review"
        const val CREDIT_TITLE = "Credit approvals"
        const val CREDIT_SUBJECT = "Term loan for Padma Spinning Mills"
        const val CONTINUITY = "Business continuity test results"
        const val DELTA = "Term loan, Delta Textiles"
        const val INCIDENTS = "Operational risk incidents, third quarter"
        const val EXPOSURES = "Large exposures and concentration limits"
        const val DELTA_INTEREST = "My brother-in-law is a director of Delta Textiles."
        const val INCIDENTS_INTEREST = "One of the incidents concerns a supplier my son works for."
        const val EXPOSURES_INTEREST = "I am a director of a group company named among the large exposures."
        const val CREDIT_INTEREST = "My brother holds shares in Padma Spinning Mills."
        const val FOR_DECISION = "For decision"
        const val CONSENT = "Consent items"
        const val FOR_NOTING = "For noting"
        const val IN_CAMERA = "In camera"
        const val ADMIN = "admin@demo.example"
        const val AUTHOR = "user@demo.example"
        const val SECRETARY = "secretary@demo.example"
        const val BOARD_MEMBER = "board.member@demo.example"
        const val DIRECTOR = "director@demo.example"
        const val HEAD_CREDIT = "head.credit@demo.example"
        const val HEAD_FINANCE = "head.finance@demo.example"
        const val HELD_TITLE = "Monthly credit review"
        const val MEGHNA = "Working capital limit, Meghna Foods"
        const val WATCH_LIST = "Watch-list accounts, quarterly review"
        const val PORTFOLIO = "Credit portfolio quality report"
        val ZONE: ZoneId = ZoneId.of("Asia/Dhaka")
        private const val APPETITE_FILE = "risk-appetite-2027.pdf"
        private const val APPETITE_TITLE = "Risk appetite statement 2027"
        private const val RISK_ROOM = "Board Room, Level 12, Head Office"
        private const val CREDIT_ROOM = "Credit Committee Room, Level 9, Head Office"
        private val RISK_START = LocalTime.of(10, 0)
        private val RISK_END = LocalTime.of(12, 30)
        private val CREDIT_START = LocalTime.of(14, 0)
        private val CREDIT_END = LocalTime.of(16, 0)

        /** At least two days ahead, so the deadline five working days before has passed (the addendum is late). */
        private const val SOON_DAYS = 2L

        /** First planned three weeks later, with every paper on time. */
        private const val PLANNED_WEEKS = 3L
        private const val CREDIT_DAYS = 14L
        private const val CREDIT_MINUTES = 30
        private const val IN_CAMERA_MINUTES = 15
        private const val CREDIT_AMOUNT = 15_000_000
        private const val CREDIT_TENOR = 60
        private const val MEGHNA_AMOUNT = 80_000_000
        private const val MEGHNA_TENOR = 12

        private val PADMA =
            CreditMemo(
                CREDIT_SUBJECT,
                "Padma Spinning Mills Ltd",
                CREDIT_AMOUNT,
                "term-loan",
                CREDIT_TENOR,
                "padma-spinning-term-loan.docx",
                listOf(
                    "Term loan of BDT 15 million over 60 months for a new spinning line.",
                    "Security: a first charge on the plant and machinery, and the directors' personal guarantees.",
                ),
                CREDIT_INTEREST,
            )

        /** E06 T12: the held review's limit item as a memo (its subject is the item's title). */
        private val MEGHNA_MEMO =
            CreditMemo(
                MEGHNA,
                "Meghna Foods Ltd",
                MEGHNA_AMOUNT,
                "working-capital",
                MEGHNA_TENOR,
                "meghna-foods-working-capital.docx",
                listOf(
                    "Renewal of the BDT 80 million working capital limit for a year.",
                    "Security: a charge over stock and receivables, and the directors' personal guarantees.",
                ),
                // The head of credit recommends it at the desk and declares their interest at the meeting (E05): the director
                // stays free to decide it under the delegation of authority after the committee (SOD-2).
                null,
            )

        /** [from], or the first Monday to Thursday after it: no weekend meetings, whether the weekend is Fri–Sat or Sat–Sun. */
        fun weekday(from: LocalDate): LocalDate =
            generateSequence(from) { it.plusDays(1) }.first { it.dayOfWeek in DayOfWeek.MONDAY..DayOfWeek.THURSDAY }

        /** [time] on [day] in the demo's zone. */
        fun at(
            day: LocalDate,
            time: LocalTime,
        ): Instant = day.atTime(time).atZone(ZONE).toInstant()

        private fun pdf(
            title: String,
            line: String,
        ) = DemoPdf.of(title, listOf(line, "Demo paper for the Board Risk Committee. Synthetic content."))
    }
}
