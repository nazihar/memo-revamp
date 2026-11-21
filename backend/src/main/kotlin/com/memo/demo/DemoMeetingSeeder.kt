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
import com.memo.meeting.MeetingSeeding
import com.memo.meeting.SeedItem
import com.memo.memo.MemoAccessSeeding
import com.memo.memo.MemoSeeding
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.DayOfWeek
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
 * Dates are relative to the day of seeding, so on a long-lived environment both meetings drift into the past. Fills gaps
 * only: a meeting already there (by its title, in any state) is left as it is. Needs the files service (scanner) and the
 * demo password (the people acting must exist with sign-in), like the demo memos.
 */
@Component
class DemoMeetingSeeder(
    private val meetings: MeetingSeeding,
    private val agendas: AgendaSeeding,
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

    /** Seeds what is missing; the two meetings' ids, or null when the demo password is not set. */
    fun seed(): Seeded? {
        if (demoPassword.isBlank()) return null
        val ids = ActorContext.asSystem("demo-meetings") { transactions.inTenant(DemoTenant.ID) { people() } }
        val risk = forumId(ids, DemoForumSeeder.RISK)
        val credit = forumId(ids, DemoForumSeeder.CREDIT)
        val today = LocalDate.now(clock.withZone(ZONE))
        val riskMeeting = asUser(ids.getValue(SECRETARY)) { meetings.find(risk, RISK_TITLE) } ?: riskReview(ids, risk, today)
        val creditMeeting = asUser(ids.getValue(SECRETARY)) { meetings.find(credit, CREDIT_TITLE) } ?: creditMeeting(ids, credit, today)
        log.info("Demo meetings seeded")
        return Seeded(riskMeeting, creditMeeting)
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
                    "Large exposures and concentration limits",
                    SubmissionPurpose.DECISION,
                    minutes = 20,
                    restrictedTo = listOf(ids.getValue(BOARD_MEMBER)),
                ),
            )
        agendas.paper(meeting, exposures, "large-exposures-q3.pdf", pdf("Large exposures, third quarter", "Restricted to the chair."))
        val incidents = agendas.item(meeting, SeedItem(CONSENT, "Operational risk incidents, third quarter"))
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
    private fun creditProposal(ids: Map<String, UUID>): UUID {
        val author = ids.getValue(AUTHOR)
        asUser(author) { memoAccess.find(author, CREDIT_SUBJECT) }?.let { return it }
        // The representative type exists (DemoWorkflowSeeder); an administrator looks it up.
        val type = asUser(ids.getValue(ADMIN)) { memos.fixedType(DemoWorkflowSeeder.CREDIT_PROPOSAL, DemoWorkflowSeeder.AUTHORITY_KEY) }
        val memo =
            asUser(author) {
                val id =
                    memos.draft(
                        CREDIT_SUBJECT,
                        typeId = type,
                        metadata =
                            mapOf(
                                "customer" to "Padma Spinning Mills Ltd",
                                "amount" to mapOf("value" to CREDIT_AMOUNT, "currency" to "BDT"),
                                "purpose" to "term-loan",
                                "tenor" to CREDIT_TENOR,
                            ),
                    )
                memos.document(
                    id,
                    "padma-spinning-term-loan.docx",
                    DemoDocx.of(
                        CREDIT_SUBJECT,
                        listOf(
                            "Term loan of BDT 15 million over 60 months for a new spinning line.",
                            "Security: a first charge on the plant and machinery, and the directors' personal guarantees.",
                        ),
                    ),
                )
                memos.submit(id, "For the committee's decision.")
                id
            }
        asUser(ids.getValue(HEAD_CREDIT)) { memos.act(memo, MemoSeeding.APPROVE, "Recommended to the credit committee.") }
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

    /** The two demo meetings. */
    data class Seeded(
        val riskReview: UUID,
        val creditMeeting: UUID,
    )

    companion object {
        const val RISK_TITLE = "Quarterly risk review"
        const val CREDIT_TITLE = "Credit approvals"
        const val CREDIT_SUBJECT = "Term loan for Padma Spinning Mills"
        const val CONTINUITY = "Business continuity test results"
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

        /** [from], or the first Monday to Thursday after it: no weekend meetings, whether the weekend is Fri–Sat or Sat–Sun. */
        fun weekday(from: LocalDate): LocalDate =
            generateSequence(from) { it.plusDays(1) }.first { it.dayOfWeek in DayOfWeek.MONDAY..DayOfWeek.THURSDAY }

        private fun pdf(
            title: String,
            line: String,
        ) = DemoPdf.of(title, listOf(line, "Demo paper for the Board Risk Committee. Synthetic content."))
    }
}
