package com.memo.meeting

import com.memo.common.authz.ForbiddenException
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.web.PreconditionRequiredException
import com.memo.common.web.RefusedException
import com.memo.forum.ForumRole
import com.memo.forum.internal.EndReason
import com.memo.forum.internal.ForumMemberStore
import com.memo.forum.internal.MemberEdit
import com.memo.identity.UserAccount
import com.memo.meeting.internal.MeetingNotFoundException
import com.memo.meeting.internal.MeetingReads
import com.memo.meeting.internal.MeetingRejectedException
import com.memo.meeting.internal.MeetingRelation
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.invite.InvitationMessageKind
import com.memo.meeting.internal.invite.InvitationState
import com.memo.meeting.internal.rsvp.Replies
import com.memo.meeting.internal.rsvp.ReplyCapacity
import com.memo.meeting.internal.rsvp.RsvpAnswer
import com.memo.meeting.internal.rsvp.RsvpHow
import com.memo.meeting.internal.rsvp.RsvpInput
import com.memo.meeting.internal.rsvp.RsvpRules
import com.memo.meeting.internal.rsvp.RsvpStore
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.Audit
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.query
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Replies to meeting invitations (test plan E05 T3 `RepliesIT`, ADR-0063, ADR-0064, design E05 §1.4, §1.5): answering for
 * oneself and changing the answer (If-Match), the history; an answer stale after a new time and only then; an answer
 * recorded on someone's behalf with each `how`, the person told; counts for the audience, details for officers;
 * apologies bringing the seat's alternate (audience row, invitation, notices) and releasing them; the refusals; the
 * alternate released by a roster change; "My meetings", the meeting and the invitations panel with the answers; audit.
 */
class RepliesIT : InvitationTestSupport() {
    @Autowired lateinit var replies: Replies

    @Autowired lateinit var rsvpStore: RsvpStore

    @Autowired lateinit var reads: MeetingReads

    @Autowired lateinit var memberStore: ForumMemberStore

    lateinit var alternate: UserAccount

    override fun label() = "replies"

    @Test
    fun `a member answers, changes the answer with If-Match, and every answer is in the history and the audit`() {
        val meeting = scheduled()
        val first = answer(director, meeting, RsvpInput(RsvpAnswer.REMOTE, note = "I will join after 11:00"))
        assertThat(first.answer).isEqualTo(RsvpAnswer.REMOTE)
        assertThat(first.capacity).isEqualTo(ReplyCapacity.MEMBER)
        assertThat(first.offered).containsExactly(RsvpAnswer.IN_PERSON, RsvpAnswer.REMOTE, RsvpAnswer.APOLOGIES)
        assertThat(first.note).isEqualTo("I will join after 11:00")
        assertThat(first.how).isEqualTo(RsvpHow.IN_APP)
        assertThat(first.stale).isFalse()
        assertThat(first.version).isZero()
        assertThat(first.replyBy).describedAs("the type's default: two working days before").isBefore(meeting.date)

        // A change needs the version: without it 428, with an old one 412.
        assertThatThrownBy { answer(director, meeting, RsvpInput(RsvpAnswer.IN_PERSON)) }
            .isInstanceOf(PreconditionRequiredException::class.java)
        assertThatThrownBy { answer(director, meeting, RsvpInput(RsvpAnswer.IN_PERSON), version = 7) }
            .isInstanceOf(ConcurrentModificationException::class.java)
        val second = answer(director, meeting, RsvpInput(RsvpAnswer.IN_PERSON), version = 0)
        assertThat(second.answer to second.version).isEqualTo(RsvpAnswer.IN_PERSON to 1L)
        assertThat(second.note).isNull()

        val history = transactions.readInTenant(tenant.id) { rsvpStore.events(meeting.id, director.id.value) }
        assertThat(history.map { it.answer }).containsExactly(RsvpAnswer.REMOTE, RsvpAnswer.IN_PERSON)
        assertThat(history.first().noteSha256).isEqualTo(RsvpRules.sha256("I will join after 11:00"))
        assertThat(history.map { it.how }.toSet()).containsExactly(RsvpHow.IN_APP)
        val audit = Audit.assertAudited(tenant.id, "meeting.rsvp.answered", meeting.id.toString(), director.id.value.toString())
        assertThat(audit.canonical)
            .contains("\"answer\":\"IN_PERSON\"")
            .contains("\"stale\":false")
            .doesNotContain("I will join after 11:00")
        // The secretaries are not told of a yes far from the meeting (less noise).
        assertThat(items(secretary, "MEETING_RSVP_CHANGED")).isEmpty()
    }

    @Test
    fun `only the mode's answers, only while scheduled, only for the audience who answers`() {
        val meeting = scheduled()
        val online =
            asUser(secretary) {
                lifecycle.edit(
                    meeting.id,
                    meeting.version,
                    place().copy(place = place().place.copy(mode = MeetingMode.ONLINE, location = null)),
                )
            }
        assertThat(rejected { answer(director, online, RsvpInput(RsvpAnswer.IN_PERSON)) }).contains("MODE_NOT_OFFERED")
        assertThat(rejected { answer(director, online, RsvpInput(RsvpAnswer.REMOTE, alternateAttends = true)) })
            .contains("ALTERNATE_WITH_APOLOGIES")
        assertThat(rejected { answer(director, online, RsvpInput(RsvpAnswer.APOLOGIES, note = "x".repeat(301))) }).contains("LENGTH")

        // forum.read and someone outside see it or not, but neither answers it; a guest does.
        assertThatThrownBy { answer(reader, online, RsvpInput(RsvpAnswer.REMOTE)) }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { answer(user, online, RsvpInput(RsvpAnswer.REMOTE)) }.isInstanceOf(MeetingNotFoundException::class.java)
        asUser(secretary) { guests.add(online.id, user.id.value, "Chief Risk Officer") }
        assertThat(answer(user, online, RsvpInput(RsvpAnswer.REMOTE)).capacity).isEqualTo(ReplyCapacity.GUEST)

        // From the start, attendance takes over.
        val current = meeting(online.id)
        val started = asUser(secretary) { lifecycle.start(current.id, current.version) }
        assertThat(refused { answer(director, started, RsvpInput(RsvpAnswer.REMOTE)) }).isEqualTo("WRONG_STATE")
        assertThat(asUser(director) { replies.mine(started.id) }.canAnswer).isFalse()
    }

    @Test
    fun `a new time makes answers stale, a new place does not, and answering again clears it`() {
        val meeting = scheduled()
        answer(director, meeting, RsvpInput(RsvpAnswer.IN_PERSON))
        val moved = asUser(secretary) { lifecycle.edit(meeting.id, meeting.version, place(location = "Annex")) }
        assertThat(asUser(director) { replies.mine(meeting.id) }.stale).isFalse()

        val later = asUser(secretary) { lifecycle.reschedule(moved.id, moved.version, times(today.plusDays(21)), "Moved") }
        val mine = asUser(director) { replies.mine(later.id) }
        assertThat(mine.stale).isTrue()
        assertThat(mine.forStartsAt).isEqualTo(meeting.startsAt)
        val counts = asUser(secretary) { replies.list(later.id) }
        assertThat(counts.members.stale to counts.members.inPerson).describedAs("stale counts as no yes").isEqualTo(1 to 0)
        assertThat(asUser(director) { reads.mine(null) }.single { it.id == later.id }.rsvp?.stale).isTrue()

        val again = answer(director, later, RsvpInput(RsvpAnswer.IN_PERSON), version = mine.version)
        assertThat(again.stale).isFalse()
        val audit = Audit.assertAudited(tenant.id, "meeting.rsvp.answered", later.id.toString())
        assertThat(audit.canonical).contains("\"stale\":true")
    }

    @Test
    fun `a secretary records an answer given by phone, and the member is told in the app and by email`() {
        val meeting = scheduled()
        assertThat(rejected { record(secretary, meeting, director, RsvpInput(RsvpAnswer.APOLOGIES)) }).contains("HOW_REQUIRED")
        assertThat(rejected { record(secretary, meeting, reader, RsvpInput(RsvpAnswer.APOLOGIES, how = RsvpHow.PHONE)) })
            .contains("NOT_INVITABLE")
        assertThat(rejected { record(secretary, meeting, secretary, RsvpInput(RsvpAnswer.APOLOGIES, how = RsvpHow.PHONE)) })
            .describedAs("a secretary answers for themselves on the meeting page")
            .contains("NOT_INVITABLE")
        // The chair reads every answer but records none; a member records for nobody.
        assertThatThrownBy { record(chair, meeting, director, RsvpInput(RsvpAnswer.APOLOGIES, how = RsvpHow.PHONE)) }
            .isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { record(director2, meeting, director, RsvpInput(RsvpAnswer.APOLOGIES, how = RsvpHow.PHONE)) }
            .isInstanceOf(ForbiddenException::class.java)

        val recorded =
            record(
                secretary,
                meeting,
                director,
                RsvpInput(RsvpAnswer.APOLOGIES, note = "Travelling", how = RsvpHow.PHONE, howNote = "called the office"),
            )
        assertThat(recorded.answer to recorded.how).isEqualTo(RsvpAnswer.APOLOGIES to RsvpHow.PHONE)
        assertThat(recorded.howNote).isEqualTo("called the office")
        assertThat(recorded.recordedBy?.userId).isEqualTo(secretary.id.value)
        assertThat(items(director, "MEETING_RSVP_RECORDED").single()).contains("APOLOGIES").contains("PHONE")
        assertThat(outbox(director)).contains("MEETING_RSVP_RECORDED")
        // Apologies are told to the other secretaries; this secretary recorded them.
        assertThat(items(secretary, "MEETING_RSVP_CHANGED")).isEmpty()
        val audit = Audit.assertAudited(tenant.id, "meeting.rsvp.answered", meeting.id.toString(), secretary.id.value.toString())
        assertThat(audit.canonical).contains("\"onBehalfBy\":\"${secretary.id.value}\"").contains("\"how\":\"PHONE\"")

        // Each way of telling; the member then answers themselves with the version.
        listOf(RsvpHow.EMAIL, RsvpHow.IN_PERSON, RsvpHow.OTHER).forEachIndexed { index, how ->
            assertThat(
                record(secretary, meeting, director, RsvpInput(RsvpAnswer.REMOTE, how = how), version = index + 0L).how,
            ).isEqualTo(how)
        }
        val mine = asUser(director) { replies.mine(meeting.id) }
        assertThat(mine.recordedBy?.userId).isEqualTo(secretary.id.value)
        assertThat(answer(director, meeting, RsvpInput(RsvpAnswer.IN_PERSON), version = mine.version).recordedBy).isNull()
    }

    @Test
    fun `officers see every answer and note, the audience sees counts only`() {
        val meeting = scheduled()
        answer(director, meeting, RsvpInput(RsvpAnswer.REMOTE, note = "From Singapore"))
        answer(chair, meeting, RsvpInput(RsvpAnswer.IN_PERSON))
        answer(director2, meeting, RsvpInput(RsvpAnswer.APOLOGIES))

        val ofMember = asUser(director) { replies.list(meeting.id) }
        assertThat(ofMember.people).isNull()
        assertThat(ofMember.canRecord).isFalse()
        assertThat(ofMember.members.total to (ofMember.members.inPerson + ofMember.members.remote)).isEqualTo(4 to 2)
        assertThat(ofMember.members.apologies to ofMember.members.noAnswer).isEqualTo(1 to 1)

        val ofSecretary = asUser(secretary) { replies.list(meeting.id) }
        assertThat(ofSecretary.canRecord).isTrue()
        val people = checkNotNull(ofSecretary.people).associateBy { it.userId }
        assertThat(people.getValue(director.id.value).note).isEqualTo("From Singapore")
        assertThat(people.getValue(secretary.id.value).answer).isNull()
        assertThat(asUser(chair) { replies.list(meeting.id) }.people).hasSize(4)
        assertThat(asUser(chair) { replies.list(meeting.id) }.canRecord).isFalse()
        assertThatThrownBy { asUser(reader) { replies.list(meeting.id) } }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { asUser(user) { replies.list(meeting.id) } }.isInstanceOf(MeetingNotFoundException::class.java)

        // The secretaries are told of the apologies, in the app.
        assertThat(items(secretary, "MEETING_RSVP_CHANGED").single()).contains("APOLOGIES")

        // The invitations panel shows the answers; the meeting and "My meetings" carry the viewer's own.
        reconcile(meeting.id)
        val panel = asUser(secretary) { panel.panel(meeting.id) }.people.associateBy { it.userId }
        assertThat(panel.getValue(director.id.value).reply?.answer).isEqualTo(RsvpAnswer.REMOTE)
        assertThat(panel.getValue(secretary.id.value).reply?.answer).isNull()
        val detail = asUser(director) { reads.get(meeting.id) }
        assertThat(detail.rsvp?.answer to detail.invitation).isEqualTo(RsvpAnswer.REMOTE to null)
        val ofOfficer = asUser(secretary) { reads.get(meeting.id) }
        assertThat(ofOfficer.rsvp?.answer).describedAs("the secretary's own seat, no answer yet").isNull()
        assertThat(ofOfficer.invitation?.sentAt).isNotNull()
        assertThat(asUser(director2) { reads.mine(null) }.single { it.id == meeting.id }.rsvp?.answer).isEqualTo(RsvpAnswer.APOLOGIES)
        assertThat(asUser(reader) { reads.get(meeting.id) }.rsvp).isNull()
    }

    @Test
    fun `apologies with the alternate bring them in, invited and told, and a new answer releases them`() {
        withAlternate()
        val meeting = scheduled()
        reconcile(meeting.id)
        val before = messages(meeting.id).size

        val apologies = answer(director, meeting, RsvpInput(RsvpAnswer.APOLOGIES, alternateAttends = true))
        assertThat(apologies.alternate?.userId).isEqualTo(alternate.id.value)
        assertThat(apologies.alternateStandingIn).isTrue()
        assertThat(audienceOf(meeting, alternate)).containsExactly("ALTERNATE:${director.id.value}")
        assertThat(items(alternate, "MEETING_ALTERNATE_ASKED").single()).contains(director.displayName)
        assertThat(outbox(alternate)).contains("MEETING_ALTERNATE_ASKED")
        assertThat(items(secretary, "MEETING_RSVP_CHANGED").single()).contains("APOLOGIES")
        Audit.assertAudited(tenant.id, "meeting.alternate.asked", meeting.id.toString(), director.id.value.toString())

        // The alternate is invited by the reconciler, sees the meeting as ALTERNATE and answers for themselves.
        reconcile(meeting.id)
        assertThat(messages(meeting.id).drop(before).map { it.userId to it.kind })
            .containsExactly(alternate.id.value to InvitationMessageKind.INVITATION)
        assertThat(asUser(alternate) { reads.get(meeting.id) }.relation).isEqualTo(MeetingRelation.ALTERNATE)
        val ofAlternate = answer(alternate, meeting, RsvpInput(RsvpAnswer.IN_PERSON))
        assertThat(ofAlternate.capacity).isEqualTo(ReplyCapacity.ALTERNATE)
        assertThat(ofAlternate.standingInFor?.userId).isEqualTo(director.id.value)
        assertThat(ofAlternate.alternate).isNull()
        val people = checkNotNull(asUser(secretary) { replies.list(meeting.id) }.people).associateBy { it.userId }
        assertThat(people.getValue(director.id.value).alternate?.userId).isEqualTo(alternate.id.value)
        assertThat(people.getValue(alternate.id.value).standingInFor?.userId).isEqualTo(director.id.value)
        // An alternate asks no alternate of their own.
        assertThat(refused { answer(alternate, meeting, RsvpInput(RsvpAnswer.APOLOGIES, alternateAttends = true), version = 0) })
            .isEqualTo("ALTERNATE_NONE")

        // The member comes after all: the alternate is released, uninvited and told.
        answer(director, meeting, RsvpInput(RsvpAnswer.IN_PERSON), version = apologies.version)
        assertThat(audienceOf(meeting, alternate)).isEmpty()
        assertThat(items(alternate, "MEETING_ALTERNATE_RELEASED").single()).contains(director.displayName)
        val released = Audit.assertAudited(tenant.id, "meeting.alternate.released", meeting.id.toString())
        assertThat(released.canonical).contains("ANSWER")
        reconcile(meeting.id)
        assertThat(messages(meeting.id).last().let { it.userId to it.kind }).isEqualTo(alternate.id.value to InvitationMessageKind.UNINVITE)
        assertThatThrownBy { asUser(alternate) { replies.mine(meeting.id) } }.isInstanceOf(MeetingNotFoundException::class.java)
    }

    @Test
    fun `no alternate, an alternate who is a member, and an alternate already standing in are refused`() {
        val meeting = scheduled()
        assertThat(refused { answer(director2, meeting, RsvpInput(RsvpAnswer.APOLOGIES, alternateAttends = true)) })
            .isEqualTo("ALTERNATE_NONE")
        assertThat(rsvp(meeting, director2)).describedAs("nothing written").isNull()

        // director's alternate is director2, a member of the committee.
        alternateFor(directorSeat.id, director2)
        val current = meeting(meeting.id)
        assertThat(refused { answer(director, current, RsvpInput(RsvpAnswer.APOLOGIES, alternateAttends = true)) })
            .isEqualTo("ALTERNATE_IS_MEMBER")

        // The same alternate for two members: the second asks while the first's stands in.
        val third = accounts.anActiveUser(transactions, tenant, aPassword())
        val thirdSeat = seat(third, ForumRole.MEMBER)
        withAlternate()
        alternateFor(thirdSeat.id, alternate)
        val later = meeting(meeting.id)
        answer(director, later, RsvpInput(RsvpAnswer.APOLOGIES, alternateAttends = true))
        assertThat(refused { answer(third, later, RsvpInput(RsvpAnswer.APOLOGIES, alternateAttends = true)) })
            .isEqualTo("ALTERNATE_BUSY")
        assertThat(audienceOf(later, alternate)).containsExactly("ALTERNATE:${director.id.value}")
    }

    @Test
    fun `the alternate is released when the member's term ends before the meeting`() {
        withAlternate()
        val meeting = scheduled()
        answer(director, meeting, RsvpInput(RsvpAnswer.APOLOGIES, alternateAttends = true))
        assertThat(audienceOf(meeting, alternate)).hasSize(1)

        val seatNow = transactions.readInTenant(tenant.id) { checkNotNull(memberStore.member(directorSeat.id)) }
        asUser(admin) { members.end(brc, directorSeat.id, seatNow.version, today.plusDays(1), EndReason.RESIGNED, null) }
        assertThat(audienceOf(meeting, alternate)).isEmpty()
        val released = Audit.assertAudited(tenant.id, "meeting.alternate.released", meeting.id.toString())
        assertThat(released.canonical).contains("ROSTER")
        assertThat(items(alternate, "MEETING_ALTERNATE_RELEASED")).hasSize(1)
    }

    @Test
    fun `a member who cannot sign in is on the invitations panel without an invitation, and the secretary records their answer`() {
        // An account never activated (as the demo's Chairman and staff seats are): never invited, but a member who answers.
        val offline = transactions.inTenant(tenant.id) { accounts.create("offline-${UUID.randomUUID()}@bank.example", "Offline Member") }
        seat(offline, ForumRole.MEMBER)
        val meeting = scheduled()
        reconcile(meeting.id)
        val before = asUser(secretary) { panel.panel(meeting.id) }.people.single { it.userId == offline.id.value }
        assertThat(Triple(before.invitable, before.state, before.reply?.capacity))
            .isEqualTo(Triple(false, InvitationState.NOT_INVITED, ReplyCapacity.MEMBER))
        assertThat(before.reply?.answer).isNull()

        record(secretary, meeting, offline, RsvpInput(RsvpAnswer.IN_PERSON, how = RsvpHow.PHONE, howNote = "Called"))
        val after = asUser(secretary) { panel.panel(meeting.id) }.people.single { it.userId == offline.id.value }
        assertThat(after.reply?.answer to after.reply?.how).isEqualTo(RsvpAnswer.IN_PERSON to RsvpHow.PHONE)
        assertThat(after.state).isEqualTo(InvitationState.NOT_INVITED)
        assertThat(messages(meeting.id).map { it.userId }).doesNotContain(offline.id.value)
    }

    /** director's seat gets [alternate] (a user created here) as its alternate. */
    private fun withAlternate() {
        if (!::alternate.isInitialized) alternate = accounts.anActiveUser(transactions, tenant, aPassword())
        alternateFor(directorSeat.id, alternate)
    }

    private fun alternateFor(
        seatId: UUID,
        account: UserAccount,
    ) {
        val seat = transactions.readInTenant(tenant.id) { checkNotNull(memberStore.member(seatId)) }
        asUser(admin) {
            members.edit(brc, seat.id, seat.version, MemberEdit(seat.voting, seat.attributes, account.id.value, seat.termEndsOn, null))
        }
    }

    private fun answer(
        account: UserAccount,
        meeting: MeetingRow,
        input: RsvpInput,
        version: Long? = null,
    ) = asUser(account) { replies.answer(meeting.id, version, input) }

    private fun record(
        account: UserAccount,
        meeting: MeetingRow,
        whom: UserAccount,
        input: RsvpInput,
        version: Long? = null,
    ) = asUser(account) { replies.record(meeting.id, whom.id.value, version, input) }

    private fun rsvp(
        meeting: MeetingRow,
        account: UserAccount,
    ) = transactions.readInTenant(tenant.id) { rsvpStore.find(meeting.id, account.id.value) }

    /** [account]'s current audience rows in [meeting] as "SOURCE[:forUserId]". */
    private fun audienceOf(
        meeting: MeetingRow,
        account: UserAccount,
    ): List<String> =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.query(
                "select source, for_user_id from meeting_audience where meeting_id = ? and user_id = ? and removed_at is null",
                meeting.id,
                account.id.value,
            ) { listOfNotNull(it.getString(1), it.getString(2)).joinToString(":") }
        }

    /** The templates of the mails queued to [account]. */
    private fun outbox(account: UserAccount): List<String> =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.query("select template from notification_mail_outbox where to_address = ?", account.email) { it.getString(1) }
        }

    private fun refused(block: () -> Unit): String =
        runCatching(block).exceptionOrNull().let { (it as? RefusedException)?.reason ?: throw AssertionError("not refused", it) }

    private fun rejected(block: () -> Unit): List<String> =
        runCatching(block).exceptionOrNull().let {
            (it as? MeetingRejectedException)?.problems?.map { p -> p.code } ?: throw AssertionError("not rejected", it)
        }
}
