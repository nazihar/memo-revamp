package com.memo.minutes

import com.memo.common.web.RefusedException
import com.memo.forum.MemoResumes
import com.memo.forum.OutcomeKind
import com.memo.forum.SubmissionPlacements
import com.memo.forum.SubmissionStatus
import com.memo.minutes.internal.DecisionInput
import com.memo.minutes.internal.DeferTarget
import com.memo.minutes.internal.ReleaseTrigger
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.Audit
import com.memo.testsupport.OwnerJdbc
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Resolution numbers and the way back to the memo (test plan E06 T4 `DecisionReleaseIT`; ADR-0065, design E06 §1.3,
 * §1.4): numbers given at the meeting's end in agenda order and at once after it; each outcome's effect on the submission
 * and the waiting memo; AT_MEETING_END and ON_MINUTES_APPROVAL; voids recorded, never reused; an idempotent rerun; one
 * failing decision holding no other back and reported after the last try; the outcome fixed once released and the memo
 * told of a corrected wording.
 */
class DecisionReleaseIT : ReleaseFixture() {
    @Autowired lateinit var placements: SubmissionPlacements

    @Test
    fun `numbers are given at the meeting's end in agenda order, and the memos move on with them`() {
        val r = running("Term loan, Delta Textiles", "Facility renewal, Acme", "Working capital, Mill")
        val (first, second, third) = r.items
        // Recorded out of agenda order: the third item first.
        decide(r, third.item, APPROVED)
        decide(r, first.item, APPROVED_WITH_CONDITIONS)
        decide(r, second.item, APPROVED)
        assertThat(r.items.map { numberOf(it.item) }).describedAs("no number during the meeting").containsOnlyNulls()

        val pass = held(r)
        val year = year(r)
        assertThat(r.items.map { numberOf(it.item) }).containsExactly("BRC/$year/001", "BRC/$year/002", "BRC/$year/003")
        assertThat(pass.released).hasSize(3)
        assertThat(pass.failed).isEmpty()
        r.items.forEach { assertThat(submission(it.submission).status).isEqualTo(SubmissionStatus.DECIDED) }
        // The memos move on to their final approver.
        r.items.forEach { assertThat(get(it.memo.toString(), colleague.session).json().absent("myTask")).isFalse() }
        // The published reads.
        val published = checkNotNull(transactions.readInTenant(tenant.id) { decisionsApi.ofItem(first.item) })
        assertThat(published.state to published.resolutionNumber).isEqualTo(DecisionState.RELEASED to "BRC/$year/001")
        val numbered = Audit.assertAudited(tenant.id, "minutes.resolution.numbered", r.meeting.id.toString(), "minutes-release")
        assertThat(numbered.canonical).contains("BRC/$year/003").doesNotContain("RESOLVED")
        val released = Audit.assertAudited(tenant.id, "minutes.decision.released", r.meeting.id.toString(), secretary.id.toString())
        assertThat(released.canonical).contains("HELD", "APPROVED")

        // The note sheet's committee entry carries the number, with a link to the resolution.
        val entry = committeeEntry(first.memo)
        assertThat(entry["committee"].asString()).isEqualTo("Board Risk Committee")
        assertThat(entry["committeeOutcome"].asString()).isEqualTo("APPROVED_WITH_CONDITIONS")
        assertThat(entry["resolution"].asString()).isEqualTo("BRC/$year/001")
        assertThat(entry["resolutionId"].asString()).isEqualTo(published.resolutionId.toString())
        assertThat(entry["text"].asString()).isEqualTo(
            "Approved with conditions. Resolution BRC/$year/001: It was RESOLVED that the term loan of BDT 60 million be approved. " +
                "Conditions: 1. Security perfected before disbursement.",
        )

        // A second run (a retry, a duplicate trigger) changes nothing.
        val again = job(r.meeting.id, ReleaseTrigger.HELD)
        assertThat(again.numbered + again.released).isEmpty()
        assertThat(r.items.map { numberOf(it.item) }).containsExactly("BRC/$year/001", "BRC/$year/002", "BRC/$year/003")
    }

    @Test
    fun `deferred and referred items send their submissions on, and their memos keep waiting`() {
        val other = otherCommittee()
        val r = running("Deferred proposal", "Referred proposal")
        val (deferred, referred) = r.items
        val target =
            r.meeting.startsAt
                .atZone(r.meeting.zone)
                .toLocalDate()
                .plusDays(30)
        decide(r, deferred.item, DecisionInput(OutcomeKind.DEFERRED, deferTo = DeferTarget(date = target)))
        decide(r, referred.item, DecisionInput(OutcomeKind.REFERRED, referToForumId = other, referReason = "An audit matter"))

        val pass = held(r)
        assertThat(pass.numbered).isEmpty()
        assertThat(pass.released).hasSize(2)
        val back = submission(deferred.submission)
        assertThat(Triple(back.status, back.targetDate, back.meetingId)).isEqualTo(Triple(SubmissionStatus.DEFERRED, target, null))
        assertThat(submission(referred.submission).status).isEqualTo(SubmissionStatus.REFERRED)
        val linked =
            AppRoleJdbc.inTransaction(tenant.id) { c ->
                c.long(
                    "select count(*) from forum_submission where previous_id = ? and forum_id = ? and status = 'QUEUED'",
                    referred.submission,
                    other,
                )
            }
        assertThat(linked).isEqualTo(1)
        // Neither memo moved on.
        r.items.forEach { assertThat(get(it.memo.toString(), colleague.session).json().absent("myTask")).isTrue() }
        assertThat(decision(deferred.item).decision.releasedKind).isEqualTo(OutcomeKind.DEFERRED)
    }

    @Test
    fun `after the end a decision is numbered and released at once, its outcome is then fixed, and a new wording tells the memo`() {
        val r = running("Term loan, Delta Textiles", "Facility renewal, Acme")
        val (first, second) = r.items
        decide(r, first.item, APPROVED)
        // The second item was taken during the meeting (everyone present), and recorded after its end.
        val at = Instant.now().truncatedTo(ChronoUnit.MICROS)
        held(r)
        val year = year(r)
        decide(r, second.item, APPROVED_WITH_CONDITIONS.copy(decidedAt = at))
        assertThat(numberOf(second.item)).isEqualTo("BRC/$year/002")
        assertThat(decision(second.item).decision.releasedAt).isNotNull()
        assertThat(submission(second.submission).status).isEqualTo(SubmissionStatus.DECIDED)
        Audit.assertAudited(tenant.id, "minutes.decision.released", r.meeting.id.toString(), secretary.id.toString())

        // The memo has moved on: the outcome is fixed.
        assertThatThrownBy { decide(r, second.item, DecisionInput(OutcomeKind.REJECTED, text = "Rejected.", reason = "Wrong outcome")) }
            .isInstanceOfSatisfying(RefusedException::class.java) { assertThat(it.reason).isEqualTo("RELEASED") }
        // The wording is not: the memo's note sheet says so.
        val reworded =
            APPROVED_WITH_CONDITIONS.copy(
                conditions = listOf("Security perfected before disbursement.", "Quarterly stock reports."),
                reason = "Condition agreed with the chair after the meeting",
            )
        decide(r, second.item, reworded)
        assertThat(numberOf(second.item)).describedAs("the number is kept").isEqualTo("BRC/$year/002")
        val notes = read("/api/v1/memos/${second.memo}/notes", initiator.session).json()["entries"]
        val note = notes.items().single { it["actionKind"]?.asString() == "FORUM_DECISION_CORRECTED" }
        assertThat(note["kind"].asString() to note["canCorrect"].asBoolean()).isEqualTo("COMMENT" to false)
        assertThat(note["text"].asString())
            .startsWith("The committee's decision was corrected on ")
            .contains("Resolution BRC/$year/002", "2. Quarterly stock reports.")
        Audit.assertAudited(tenant.id, "memo.note.posted", second.memo.toString(), secretary.id.toString())
    }

    @Test
    fun `when memos move on at the minutes' approval, a correction before then voids its number and a new one is given`() {
        memosResume(MemoResumes.ON_MINUTES_APPROVAL)
        val r = running("Item five", "Item seven", "Item nine", "Item ten")
        val (five, seven, nine) = r.items
        val ten = r.items[3]
        listOf(five, seven, nine).forEach { decide(r, it.item, APPROVED) }
        // Item ten is taken now and recorded after the end.
        val at = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val pass = held(r)
        val year = year(r)
        assertThat(pass.released).describedAs("nothing moves on at the end").isEmpty()
        assertThat(listOf(five, seven, nine).map { numberOf(it.item) }).containsExactly("BRC/$year/001", "BRC/$year/002", "BRC/$year/003")
        r.items.forEach { assertThat(submission(it.submission).status).isEqualTo(SubmissionStatus.ACCEPTED) }

        // Item seven corrected to NOTED: its number is void, with the reason, and never given again.
        val reason = "Recorded as noted on the chair's review"
        val noted = decide(r, seven.item, NOTED.copy(reason = reason))
        assertThat(numberOf(seven.item)).isNull()
        val numbers = transactions.readInTenant(tenant.id) { resolutionsApi.ofDecision(noted.decision.id) }
        assertThat(numbers.map { it.number to it.state }).containsExactly("BRC/$year/002" to ResolutionState.VOID)
        assertThat(numbers.single().voidReason).isEqualTo(reason)
        Audit.assertAudited(tenant.id, "minutes.resolution.voided", r.meeting.id.toString(), secretary.id.toString())
        decide(r, ten.item, APPROVED.copy(decidedAt = at))
        assertThat(numberOf(ten.item)).isEqualTo("BRC/$year/004")
        // Corrected back into a resolution, it takes a new number.
        decide(r, seven.item, APPROVED.copy(reason = "The chair confirmed the approval"))
        assertThat(numberOf(seven.item)).isEqualTo("BRC/$year/005")
        val check = checkNotNull(transactions.readInTenant(tenant.id) { resolutionsApi.check(brc, "$year") })
        assertThat(Triple(check.inForce, check.void, check.lastNumber)).isEqualTo(Triple(4, 1, 5L))
        assertThat(check.complete).isTrue()

        // The minutes approved: every decision moves on, and the memos with them.
        val approvedPass = approved(r.meeting.id)
        assertThat(approvedPass.released).hasSize(4)
        r.items.forEach { assertThat(submission(it.submission).status).isEqualTo(SubmissionStatus.DECIDED) }
        assertThat(committeeEntry(seven.memo)["resolution"].asString()).isEqualTo("BRC/$year/005")
    }

    @Test
    fun `a decision that fails to reach its memo holds no other back, is retried, and is reported after the last try`() {
        val r = running("Term loan, Delta Textiles", "Facility renewal, Acme")
        val (first, second) = r.items
        decide(r, first.item, APPROVED)
        decide(r, second.item, APPROVED)
        // As the schema owner (a test of failure only): the second submission names a step its memo does not wait at.
        OwnerJdbc.connect { c ->
            c.autoCommit = false
            c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                it.setString(1, tenant.id.value.toString())
                it.execute()
            }
            c.prepareStatement("update forum_submission set step_id = ? where id = ?").use {
                it.setObject(1, UUID.randomUUID())
                it.setObject(2, second.submission)
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
            c.commit()
        }
        val pass = held(r)
        assertThat(pass.released).containsExactly(decision(first.item).decision.id)
        assertThat(pass.failed.keys).containsExactly(decision(second.item).decision.id)
        // The failed release rolled back alone: the number stays, the submission waits, the decision is not released.
        assertThat(numberOf(second.item)).isNotNull()
        assertThat(submission(second.submission).status).isEqualTo(SubmissionStatus.ACCEPTED)
        assertThat(decision(second.item).decision.releasedAt).isNull()
        // After the last try the job reports it.
        minutesReleaseGaveUp(r, pass)
        val reported = Audit.assertAudited(tenant.id, "minutes.decision.release_failed", r.meeting.id.toString(), "minutes-release")
        assertThat(reported.canonical).contains(decision(second.item).decision.id.toString())
    }

    private fun minutesReleaseGaveUp(
        r: Running,
        pass: com.memo.minutes.internal.ReleasePass,
    ) = com.memo.common.tenant.TenantContext.call(tenant.id) {
        com.memo.common.context.ActorContext
            .asSystem("minutes-release") { minutesRelease.gaveUp(r.meeting.id, pass) }
    }

    private fun submission(id: UUID) = checkNotNull(asUser(secretary.account) { placements.find(id) })

    private fun year(r: Running): Int =
        r.meeting.startsAt
            .atZone(r.meeting.zone)
            .year

    /** The memo's note-sheet entry of the committee's decision. */
    private fun committeeEntry(memo: UUID) =
        read("/api/v1/memos/$memo/notes", initiator.session).json()["entries"].items().single {
            it["actionKind"]?.asString() ==
                "FORUM_DECIDED"
        }
}
