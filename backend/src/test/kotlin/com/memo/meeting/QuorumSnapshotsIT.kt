package com.memo.meeting

import com.memo.common.authz.ForbiddenException
import com.memo.common.web.RefusedException
import com.memo.forum.ForumRole
import com.memo.forum.MeetingPolicy
import com.memo.forum.QuorumOverride
import com.memo.forum.UnmetKind
import com.memo.forum.internal.ForumTypeLifecycle
import com.memo.identity.UserAccount
import com.memo.meeting.internal.Declarations
import com.memo.meeting.internal.DeclareInput
import com.memo.meeting.internal.ItemStore
import com.memo.meeting.internal.MeetingRejectedException
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.quorum.QuorumReads
import com.memo.meeting.internal.rsvp.RsvpAnswer
import com.memo.meeting.internal.rsvp.RsvpInput
import com.memo.testsupport.Audit
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

/**
 * Per-item quorum and the snapshots taken with decisions (test plan E05 T5 `QuorumSnapshotsIT`, ADR-0064, design E05 §1.7):
 * recusals (and a recused member's alternate) and people stepped out leave an item's count; `take` inside the caller's
 * transaction stores exactly what `GET …/quorum?itemId=&at=` shows; `NOT_QUORATE` with the conditions not met; the
 * override only with the type's policy, by a secretary not recused, with a reason, stored as to be ratified, audited and
 * told; `drifted` after a correction; a recused officer sees the counts only.
 */
class QuorumSnapshotsIT : AttendanceTestSupport() {
    @Autowired lateinit var snapshots: QuorumSnapshots

    @Autowired lateinit var declarations: Declarations

    @Autowired lateinit var typeVersions: ForumTypeLifecycle

    override fun label() = "snapshots"

    @Test
    fun `an item with two recused members is not quorate, nothing is stored, and the live view agrees with a snapshot`() {
        val (meeting, items) = started { declare(listOf(director2, others[0]), it, "Credit proposal") }
        val credit = items.getValue("Credit proposal")
        val appetite = items.getValue("Risk appetite")
        arrive(meeting, listOf(chair, director, director2, others[0], others[1]))
        assertThat(asUser(secretary) { attendanceReads.quorum(meeting.id, null) }.let { it.quorate to it.present }).isEqualTo(true to 5)

        val item = asUser(secretary) { attendanceReads.quorum(meeting.id, null, credit) }
        assertThat(item.itemId to (item.quorate to item.present)).isEqualTo(credit to (false to 3))
        val recusedSeats = checkNotNull(item.seats).filter { it.recused }.map { it.holder.userId }
        assertThat(recusedSeats).containsExactlyInAnyOrder(director2.id.value, others[0].id.value)

        // recusedCount false: 409 NOT_QUORATE with "3 of 4 needed", and nothing stored.
        val refused = refusal { snapshots.take(meeting.id, credit) }
        assertThat(refused).isInstanceOf(NotQuorateException::class.java)
        assertThat(refused.message).contains("3 of 4 needed")
        assertThat((refused as NotQuorateException).result.unmet.map { it.kind }).containsExactly(UnmetKind.COUNT)
        assertThat(refused.details).containsEntry("needed", 4).containsEntry("present", 3)
        // The type does not allow an override.
        assertThat(refusal { snapshots.take(meeting.id, credit, overrideReason = "Urgent") }.reason).isEqualTo("OVERRIDE_NOT_ALLOWED")
        assertThat(asUser(secretary) { snapshots.ofMeeting(meeting.id) }).isEmpty()

        // Inside the caller's transaction: a decision that fails takes its snapshot with it.
        runCatching {
            asUser(secretary) {
                snapshots.take(meeting.id, appetite)
                error("the decision failed")
            }
        }
        assertThat(asUser(secretary) { snapshots.ofMeeting(meeting.id) }).isEmpty()

        // Live equals recorded: the snapshot of "Risk appetite" at t is what the live view says at t.
        val taken = asUser(secretary) { snapshots.take(meeting.id, appetite) }
        val live = asUser(secretary) { attendanceReads.quorum(meeting.id, taken.at, appetite) }
        assertThat(listOf(live.quorate, live.basis, live.needed, live.present))
            .containsExactly(taken.quorate, taken.result.basis, taken.result.needed, taken.result.present)
        assertThat(live.unmet).isEqualTo(taken.result.unmet)
        val stored = checkNotNull(asUser(secretary) { snapshots.find(taken.id) })
        assertThat(stored).isEqualTo(taken)
        assertThat(stored.inputs.evaluate()).describedAs("the stored inputs re-evaluate to the stored result").isEqualTo(stored.result)
        assertThat(stored.inputs.sha256()).isEqualTo(stored.inputsSha256)
        assertThat(stored.inputs.presence.map { it.userId })
            .containsExactlyInAnyOrder(*listOf(chair, director, director2, others[0], others[1]).map { it.id.value }.toTypedArray())
        assertThat(stored.override to stored.ratificationRequired).isEqualTo(null to false)
        val audit = Audit.assertAudited(tenant.id, "meeting.quorum.snapshot_taken", meeting.id.toString(), secretary.id.value.toString())
        assertThat(audit.canonical).contains(taken.id.toString()).contains(taken.inputsSha256).contains("\"quorate\":true")

        // Over HTTP: the officers and forum.read; the members are 403.
        assertThat(asUser(chair) { quorumReads.snapshot(meeting.id, taken.id) }.quorum.present).isEqualTo(5)
        assertThat(asUser(reader) { quorumReads.snapshot(meeting.id, taken.id) }.seats).hasSize(8)
        assertThat(runCatching { asUser(director) { quorumReads.snapshot(meeting.id, taken.id) } }.exceptionOrNull())
            .isInstanceOf(ForbiddenException::class.java)
        assertThat(runCatching { asUser(secretary) { quorumReads.snapshot(meeting.id, UUID.randomUUID()) } }.exceptionOrNull())
            .hasMessageContaining("snapshot not found")
    }

    @Test
    fun `a recused member's alternate does not stand in for the item, and a member stepped out leaves its count`() {
        val (meeting, items) =
            started { m ->
                answer(director, m, RsvpInput(RsvpAnswer.APOLOGIES, alternateAttends = true))
                declare(listOf(director), m, "Credit proposal")
            }
        val credit = items.getValue("Credit proposal")
        arrive(meeting, listOf(chair, alternate, director2, others[0], others[1]))
        val whole = asUser(secretary) { attendanceReads.quorum(meeting.id, null) }
        assertThat(whole.present).describedAs("the alternate fills the director's seat for the meeting").isEqualTo(5)

        val item = asUser(secretary) { attendanceReads.quorum(meeting.id, null, credit) }
        assertThat(item.quorate to item.present).isEqualTo(true to 4)
        val seat = checkNotNull(item.seats).single { it.holder.userId == director.id.value }
        assertThat(Triple(seat.filledBy, seat.recused, seat.byAlternate)).isEqualTo(Triple(null, true, false))

        record(meeting, others[1], AttendanceEventKind.STEPPED_OUT) { it.copy(itemId = credit) }
        val out = asUser(secretary) { attendanceReads.quorum(meeting.id, null, credit) }
        assertThat(out.quorate to out.present).isEqualTo(false to 3)
        assertThat(checkNotNull(out.seats).single { it.holder.userId == others[1].id.value }.steppedOut).isTrue()
        assertThat(asUser(secretary) { attendanceReads.quorum(meeting.id, null) }.present).isEqualTo(5)

        // The alternate, recused with the member, sees the numbers only.
        assertThat(asUser(alternate) { attendanceReads.quorum(meeting.id, null, credit) }.let { it.present to it.seats })
            .isEqualTo(3 to null)
        // An item of another meeting is 404.
        assertThat(runCatching { asUser(secretary) { attendanceReads.quorum(meeting.id, null, UUID.randomUUID()) } }.exceptionOrNull())
            .hasMessageContaining("item not found")
    }

    @Test
    fun `the type's override records a decision without a quorum, to be ratified, by a secretary not recused, and tells`() {
        allowOverrides()
        val secretary2 = accounts.anActiveUser(transactions, tenant, aPassword())
        seat(secretary2, ForumRole.SECRETARY)
        val (meeting, items) =
            started { m ->
                // The second secretary holds an interest in the item too.
                declare(listOf(director2, others[0], secretary2), m, "Credit proposal")
            }
        val credit = items.getValue("Credit proposal")
        arrive(meeting, listOf(chair, director, director2, others[0], others[1]))

        assertThat(refusal { snapshots.take(meeting.id, credit) }.reason).isEqualTo("NOT_QUORATE")
        assertThat(rejected { snapshots.take(meeting.id, credit, overrideReason = "   ") }).containsExactly("REASON_REQUIRED")
        assertThat(rejected { snapshots.take(meeting.id, credit, overrideReason = "x".repeat(501)) }).containsExactly("LENGTH")
        val recused = runCatching { asUser(secretary2) { snapshots.take(meeting.id, credit, overrideReason = "Urgent") } }.exceptionOrNull()
        assertThat((recused as ForbiddenException).reason).isEqualTo("RECUSED")

        val taken = asUser(secretary) { snapshots.take(meeting.id, credit, overrideReason = "The facility lapses tomorrow") }
        assertThat(Triple(taken.quorate, taken.ratificationRequired, taken.override?.by)).isEqualTo(Triple(false, true, secretary.id.value))
        assertThat(taken.override?.reason).isEqualTo("The facility lapses tomorrow")
        assertThat(taken.result.present to taken.result.needed).isEqualTo(3 to 4)
        assertThat(checkNotNull(asUser(secretary) { snapshots.find(taken.id) }).inputs.sha256()).isEqualTo(taken.inputsSha256)
        val audit = Audit.assertAudited(tenant.id, "meeting.quorum.overridden", meeting.id.toString(), secretary.id.value.toString())
        assertThat(audit.canonical).contains(taken.id.toString()).contains("reasonSha256").doesNotContain("lapses tomorrow")
        assertThat(items(chair, "MEETING_QUORUM_OVERRIDDEN").single()).contains("\"present\": \"3\"").doesNotContain("lapses")
        assertThat(items(secretary2, "MEETING_QUORUM_OVERRIDDEN")).hasSize(1)
        assertThat(items(secretary, "MEETING_QUORUM_OVERRIDDEN")).describedAs("not the actor").isEmpty()
        assertThat(items(director, "MEETING_QUORUM_OVERRIDDEN")).describedAs("members are not told").isEmpty()

        // A recused officer reads the counts only, and no reason; forum.read reads no reason either.
        val ofRecused = asUser(secretary2) { quorumReads.snapshot(meeting.id, taken.id) }
        assertThat(ofRecused.seats to ofRecused.override?.reason).isEqualTo(null to null)
        assertThat(ofRecused.ratificationRequired).isTrue()
        assertThat(asUser(reader) { quorumReads.snapshot(meeting.id, taken.id) }.override?.reason).isNull()
        assertThat(asUser(chair) { quorumReads.snapshot(meeting.id, taken.id) }.override?.reason).isEqualTo("The facility lapses tomorrow")
        // A quorate meeting ignores an override: nothing to ratify.
        val appetite = asUser(secretary) { snapshots.take(meeting.id, items.getValue("Risk appetite"), overrideReason = "Not needed") }
        assertThat(appetite.quorate to appetite.ratificationRequired).isEqualTo(true to false)
    }

    @Test
    fun `a correction after the meeting never changes a snapshot, and drifted says the register changed`() {
        val (meeting, items) = started()
        val appetite = items.getValue("Risk appetite")
        val arrived = arrive(meeting, listOf(chair, director, director2, others[0], others[1]))
        val taken = asUser(secretary) { snapshots.take(meeting.id, appetite) }
        assertThat(asUser(secretary) { snapshots.drifted(taken.id) }?.drifted).isFalse()

        // The meeting ends; others[1] never came: the arrival is voided.
        val ended = meeting(meeting.id)
        asUser(secretary) { lifecycle.end(ended.id, ended.version) }
        val wrong = arrived.single { it.row.userId == others[1].id.value }
        asUser(secretary) {
            attendance.void(meeting.id, wrong.event.id, versionOf(meeting, others[1]), "Recorded by mistake: was not there")
        }
        val drift = checkNotNull(asUser(secretary) { snapshots.drifted(taken.id) })
        assertThat(drift.drifted to drift.now.present).isEqualTo(true to 4)
        assertThat(checkNotNull(asUser(secretary) { snapshots.find(taken.id) })).isEqualTo(taken)
        assertThat(asUser(chair) { quorumReads.snapshot(meeting.id, taken.id) }.drifted).isTrue()

        // A corrected decision takes a new snapshot at the decision's time, once held.
        val again = asUser(secretary) { snapshots.take(meeting.id, appetite, at = taken.at) }
        assertThat(again.result.present to again.at).isEqualTo(4 to taken.at)
        assertThat(
            rejected { snapshots.take(meeting.id, appetite, at = Instant.now().plusSeconds(600)) },
        ).containsExactly("TIME_OUT_OF_RANGE")
        assertThat(asUser(secretary) { snapshots.ofMeeting(meeting.id) }.map { it.id }).containsExactly(taken.id, again.id)
    }

    @Test
    fun `only a running or held meeting's quorum is recorded, by its secretaries`() {
        val upcoming = scheduled()
        assertThat(refusal { snapshots.take(upcoming.id) }.reason).isEqualTo("WRONG_STATE")
        val (meeting, _) = started()
        assertThat(
            runCatching { asUser(chair) { snapshots.take(meeting.id) } }.exceptionOrNull(),
        ).isInstanceOf(ForbiddenException::class.java)
        assertThat(runCatching { asUser(user) { snapshots.take(meeting.id) } }.exceptionOrNull()).hasMessageContaining("not found")
        assertThat(rejected { snapshots.take(meeting.id, UUID.randomUUID()) }).containsExactly("ITEM_NOT_ON_AGENDA")
    }

    @Autowired lateinit var quorumReads: QuorumReads

    @Autowired lateinit var itemStore: ItemStore

    /** Each of [people] declares an interest in [title] of [meeting]. */
    private fun declare(
        people: List<UserAccount>,
        meeting: MeetingRow,
        title: String,
    ) {
        val item = itemOf(meeting, title)
        people.forEach { asUser(it) { declarations.declare(meeting.id, item, DeclareInput(NATURE)) } }
    }

    private fun itemOf(
        meeting: MeetingRow,
        title: String,
    ): UUID = transactions.readInTenant(tenant.id) { itemStore.live(meeting.id) }.single { it.title == title }.id

    /** Publishes version 2 of the committee's type with `quorumOverride` WITH_REASON (reviewed by a second administrator). */
    private fun allowOverrides() {
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        val draft = asUser(admin) { typeVersions.newDraft(typeId) }
        val saved =
            asUser(admin) {
                typeVersions.replace(
                    typeId,
                    2,
                    draft.revision,
                    draft.settings.copy(meetings = MeetingPolicy(quorumOverride = QuorumOverride.WITH_REASON)),
                )
            }
        asUser(admin) { typeVersions.submit(typeId, 2, saved.revision) }
        asUser(checker) { typeVersions.approve(typeId, 2, null) }
    }

    private fun refusal(block: () -> Any): RefusedException =
        runCatching { asUser(secretary, block) }.exceptionOrNull() as? RefusedException ?: throw AssertionError("not refused")

    private fun rejected(block: () -> Any): List<String> =
        runCatching { asUser(secretary, block) }.exceptionOrNull().let {
            (it as? MeetingRejectedException)?.problems?.map { p -> p.code } ?: throw AssertionError("not rejected", it)
        }

    private companion object {
        const val NATURE = "A family member is a director of the borrower"
    }
}
