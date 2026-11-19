package com.memo.meeting

import com.memo.common.authz.ForbiddenException
import com.memo.common.context.ActorContext
import com.memo.common.web.RefusedException
import com.memo.forum.internal.EndReason
import com.memo.forum.internal.MemberInput
import com.memo.meeting.internal.MeetingNotFoundException
import com.memo.meeting.internal.MeetingRejectedException
import com.memo.meeting.internal.ical.InviteKind
import com.memo.meeting.internal.invite.InvitationMessageKind
import com.memo.meeting.internal.invite.InvitationState
import com.memo.notification.NotificationCategory
import com.memo.notification.NotificationChannel
import com.memo.notification.internal.NotificationPreferences
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.Audit
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.Instant

/**
 * Meeting invitations (test plan E05 T2 `InvitationsIT`, ADR-0063): the invitation at `schedule`, sent after the
 * debounce to every member and guest, each naming only its recipient; quick changes coalesced into one update; the
 * cancellation at once, with nothing after it; the audience followed (term ended, re-appointed, guests); drafts, started
 * meetings and changes of kind or classification send nothing; the panel, resend and its limits; "Add to my calendar";
 * invitation emails switched off; and the audit of each.
 */
class InvitationsIT : InvitationTestSupport() {
    @Autowired internal lateinit var preferences: NotificationPreferences

    @Test
    fun `scheduling invites every member after the debounce, one file per person naming only them, at sequence 0`() {
        val draft = asUser(secretary) { lifecycle.create(brc, place(), times(today.plusDays(20))) }
        assertThat(invitation(draft.id)).describedAs("a draft has no invitation").isNull()

        val meeting = asUser(secretary) { lifecycle.schedule(draft.id, draft.version) }
        val invitation = checkNotNull(invitation(meeting.id))
        assertThat(invitation.sequence).isZero()
        assertThat(invitation.dueAt).isAfter(Instant.now().plus(Duration.ofSeconds(100)))
        // Before the debounce ends, the job waits and sends nothing.
        assertThat(reconcile(meeting.id, Instant.now())).isPositive()
        assertThat(messages(meeting.id)).isEmpty()

        assertThat(reconcile(meeting.id)).isNull()
        val sent = messages(meeting.id)
        assertThat(sent.map { name(it.userId) }).containsExactlyInAnyOrder("secretary", "chair", "director", "director2")
        assertThat(sent.map { it.kind to it.sequence }.toSet()).containsExactly(InvitationMessageKind.INVITATION to 0)
        val uids =
            sent.map { message ->
                val (_, parsed) = file(checkNotNull(message.outboxId), InviteKind.REQUEST)
                val recipient = listOf(secretary, chair, director, director2).single { it.id.value == message.userId }
                assertThat(parsed.attendees).containsExactly(recipient.email)
                assertThat(parsed.sequence).isZero()
                assertThat(parsed.summary).isEqualTo("Board Risk Committee")
                assertThat(template(message.outboxId)).isEqualTo("MEETING_INVITATION")
                parsed.uid
            }
        assertThat(uids.toSet()).containsExactly(invitation.icalUid.toString())
        assertThat(items(director, "MEETING_INVITATION")).hasSize(1)
        assertThat(invitees(meeting.id).values.map { it.state.name }.toSet()).containsExactly("INVITED")
        val audit = Audit.assertAudited(tenant.id, "meeting.invitations.sent", meeting.id.toString())
        assertThat(audit.actorType).isEqualTo("SYSTEM")
        assertThat(audit.canonical).contains("INVITATION").contains("\"recipients\":4")

        // A second run, or a retry, finds nothing to send.
        assertThat(reconcile(meeting.id)).isNull()
        assertThat(messages(meeting.id)).hasSize(4)
        assertThat(checkNotNull(invitation(meeting.id)).dueAt).isNull()
    }

    @Test
    fun `three reschedules make one update at sequence 1, and cancelling sends the CANCEL at once with nothing after it`() {
        val meeting = scheduled()
        reconcile(meeting.id)
        var current = meeting(meeting.id)
        for (days in 21L..23L) {
            current = asUser(secretary) { lifecycle.reschedule(current.id, current.version, times(today.plusDays(days)), "Moved") }
        }
        assertThat(checkNotNull(invitation(meeting.id)).sequence).isEqualTo(1)
        reconcile(meeting.id)
        val updates = messages(meeting.id).drop(4)
        assertThat(updates.map { it.kind to it.sequence }.toSet()).containsExactly(InvitationMessageKind.UPDATE to 1)
        assertThat(updates).hasSize(4)
        val update = updates.single { it.userId == director.id.value }
        val (_, parsed) = file(update.outboxId!!, InviteKind.REQUEST)
        assertThat(parsed.start).isEqualTo(at(today.plusDays(23)).atZone(java.time.ZoneId.of("Asia/Dhaka")).toInstant())
        assertThat(params(update.outboxId)["changes"]).isEqualTo("The date or time changed.")
        assertThat(template(update.outboxId)).isEqualTo("MEETING_UPDATED")

        // A place change waits for the debounce; the cancellation overtakes it and goes at once.
        current = asUser(secretary) { lifecycle.edit(current.id, current.version, place(location = "Annex")) }
        asUser(secretary) { lifecycle.cancel(current.id, current.version, "CANARY-REASON the chair is away") }
        assertThat(reconcile(meeting.id, Instant.now())).isNull()
        val cancels = messages(meeting.id).filter { it.kind == InvitationMessageKind.CANCEL }
        assertThat(cancels.map { it.kind to it.sequence }.toSet()).containsExactly(InvitationMessageKind.CANCEL to 2)
        assertThat(cancels).hasSize(4)
        val (text, cancel) = file(cancels.first().outboxId!!, InviteKind.CANCEL)
        assertThat(cancel.status).isEqualTo("CANCELLED")
        assertThat(text).doesNotContain("CANARY")
        // The cancellation reaches each invitee once, with the file; nothing more is sent afterwards.
        assertThat(items(director, "MEETING_CANCELLED")).hasSize(1)
        assertThat(items(secretary, "MEETING_CANCELLED")).hasSize(1)
        assertThat(invitees(meeting.id).values.map { it.state.name }.toSet()).containsExactly("CANCELLED")
        assertThat(reconcile(meeting.id)).isNull()
        assertThat(messages(meeting.id)).hasSize(12)
        assertThat(Audit.entries(tenant.id).filter { it.action == "meeting.invitations.sent" }.map { it.canonical })
            .anyMatch { it.contains("\"kind\":\"CANCEL\"") && it.contains("\"method\":\"CANCEL\"") }
    }

    @Test
    fun `a cancellation before the first send tells everyone without a file`() {
        val meeting = scheduled()
        asUser(secretary) { lifecycle.cancel(meeting.id, meeting.version, "No quorum expected") }
        reconcile(meeting.id, Instant.now())
        assertThat(messages(meeting.id)).isEmpty()
        assertThat(items(director, "MEETING_CANCELLED")).hasSize(1)
    }

    @Test
    fun `the invitation follows the audience - a term ending uninvites, a re-appointment and a guest invite`() {
        val meeting = scheduled(today.plusDays(30))
        reconcile(meeting.id)
        val guest = accounts.anActiveUser(transactions, tenant, aPassword())

        // The director's term ends before the meeting: a CANCEL without STATUS, above what they hold.
        asUser(admin) { members.end(brc, directorSeat.id, directorSeat.version, today.plusDays(1), EndReason.RESIGNED, null) }
        assertThat(checkNotNull(invitation(meeting.id)).sequence).describedAs("an audience change keeps the sequence").isZero()
        reconcile(meeting.id)
        val uninvite = messages(meeting.id).drop(4).single()
        assertThat(name(uninvite.userId) to uninvite.kind).isEqualTo("director" to InvitationMessageKind.UNINVITE)
        assertThat(uninvite.sequence).isEqualTo(1)
        val (_, parsed) = file(uninvite.outboxId!!, InviteKind.UNINVITE)
        assertThat(parsed.status).isNull()
        assertThat(items(director, "MEETING_UNINVITED")).hasSize(1)

        // Re-appointed: invited again with a higher sequence than the uninvitation; a guest is an optional participant.
        asUser(admin) { members.add(brc, MemberInput(userId = director.id.value, startsOn = today.plusDays(2))) }
        asUser(secretary) { guests.add(meeting.id, guest.id.value, "Chief Financial Officer") }
        reconcile(meeting.id)
        val back = messages(meeting.id).drop(5)
        assertThat(back.map { (if (it.userId == guest.id.value) "guest" else name(it.userId)) to (it.kind to it.sequence) })
            .containsExactlyInAnyOrder(
                "director" to (InvitationMessageKind.INVITATION to 2),
                "guest" to (InvitationMessageKind.INVITATION to 0),
            )
        val guestFile = file(back.single { it.userId == guest.id.value }.outboxId!!, InviteKind.REQUEST).first
        assertThat(guestFile.replace("\r\n ", "")).contains("ROLE=OPT-PARTICIPANT")

        // A later update reaches the re-invited director above everything they hold.
        val current = meeting(meeting.id)
        asUser(secretary) { lifecycle.edit(current.id, current.version, place(link = "https://meet.example/new")) }
        reconcile(meeting.id)
        val update = messages(meeting.id).drop(7)
        assertThat(update.single { it.userId == director.id.value }.sequence).isEqualTo(3)
        assertThat(update.single { it.userId == director2.id.value }.sequence).isEqualTo(1)
        assertThat(update.map { it.changes.map { c -> c.name } }.toSet()).containsExactly(listOf("LINK"))

        // The guest removed: uninvited.
        asUser(secretary) { guests.remove(meeting.id, guest.id.value) }
        reconcile(meeting.id)
        assertThat(messages(meeting.id).last().let { it.userId to it.kind }).isEqualTo(guest.id.value to InvitationMessageKind.UNINVITE)
    }

    @Test
    fun `drafts, started meetings and changes of kind or classification send nothing`() {
        val meeting = scheduled()
        reconcile(meeting.id)
        val current = meeting(meeting.id)
        val edited =
            asUser(secretary) {
                lifecycle.edit(current.id, current.version, place().copy(kind = MeetingKind.SPECIAL))
            }
        assertThat(checkNotNull(invitation(meeting.id)).dueAt).isNull()
        assertThat(checkNotNull(invitation(meeting.id)).sequence).isZero()

        // A change waits for the debounce, but the meeting starts first: nothing more is sent once it has started.
        val moved = asUser(secretary) { lifecycle.edit(edited.id, edited.version, place(location = "Annex")) }
        asUser(secretary) { lifecycle.start(moved.id, moved.version) }
        reconcile(meeting.id)
        assertThat(messages(meeting.id)).hasSize(4)
        assertThat(checkNotNull(invitation(meeting.id)).dueAt).isNull()
    }

    @Test
    fun `officers read the panel, secretaries resend at most once per person in 10 minutes`() {
        val meeting = scheduled()
        val before = asUser(secretary) { panel.panel(meeting.id) }
        assertThat(before.created).isTrue()
        assertThat(before.pending).isTrue()
        assertThat(before.people.map { it.state }.toSet()).containsExactly(InvitationState.NOT_INVITED)
        assertThat(before.people.map { it.invitable }.toSet()).containsExactly(true)
        refused("WRONG_STATE") { asUser(secretary) { panel.resend(meeting.id, null) } }

        val sentAt = Instant.now().plus(Duration.ofMinutes(3))
        reconcile(meeting.id, sentAt)
        val after = asUser(chair) { panel.panel(meeting.id) }
        assertThat(after.canResend).isFalse()
        assertThat(after.pending).isFalse()
        assertThat(after.people).hasSize(4)
        val row = after.people.single { it.userId == director.id.value }
        assertThat(row.state).isEqualTo(InvitationState.INVITED)
        assertThat(row.lastSequence).isZero()
        assertThat(row.lastKind).isEqualTo(InvitationMessageKind.INVITATION)
        assertThat(row.delivery?.state?.name).isEqualTo("PENDING")
        assertThat(row.emailOff).isFalse()
        assertThat(asUser(secretary) { panel.panel(meeting.id) }.canResend).isTrue()

        // Who may see and resend.
        assertThatThrownBy { asUser(director) { panel.panel(meeting.id) } }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { asUser(reader) { panel.panel(meeting.id) } }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { asUser(user) { panel.panel(meeting.id) } }.isInstanceOf(MeetingNotFoundException::class.java)
        assertThatThrownBy { asUser(chair) { panel.resend(meeting.id, null) } }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { asUser(director) { panel.resend(meeting.id, null) } }.isInstanceOf(ForbiddenException::class.java)

        // Too soon, then allowed; only to people who hold the invitation.
        refused("RESEND_TOO_SOON") { asUser(secretary) { panel.resend(meeting.id, setOf(director.id.value), sentAt.plusSeconds(60)) } }
        val problem =
            runCatching { asUser(secretary) { panel.resend(meeting.id, setOf(user.id.value), sentAt.plusSeconds(700)) } }.exceptionOrNull()
        assertThat((problem as MeetingRejectedException).problems.map { "${it.field}:${it.code}" }).containsExactly("userIds:NOT_INVITED")
        asUser(secretary) { panel.resend(meeting.id, setOf(director.id.value), sentAt.plusSeconds(700)) }
        val resent = messages(meeting.id).last()
        assertThat(name(resent.userId) to (resent.kind to resent.sequence)).isEqualTo("director" to (InvitationMessageKind.RESEND to 0))
        assertThat(resent.queuedBy).isEqualTo(secretary.id.value)
        file(resent.outboxId!!, InviteKind.REQUEST)
        Audit.assertAudited(tenant.id, "meeting.invitations.resent", meeting.id.toString(), secretary.id.value.toString())
    }

    @Test
    fun `add to my calendar gives the audience the PUBLISH file, and nobody else`() {
        val draft = asUser(secretary) { lifecycle.create(brc, place(), times(today.plusDays(20))) }
        assertThatThrownBy { asUser(secretary) { panel.calendar(draft.id) } }.isInstanceOf(MeetingNotFoundException::class.java)
        val meeting = asUser(secretary) { lifecycle.schedule(draft.id, draft.version) }

        val bytes = asUser(director) { panel.calendar(meeting.id) }
        val parsed =
            com.memo.meeting.internal.ical.InviteChecks
                .valid(bytes, InviteKind.PUBLISH)
        assertThat(parsed.method).isEqualTo("PUBLISH")
        assertThat(parsed.attendees).isEmpty()
        assertThat(parsed.uid).isEqualTo(checkNotNull(invitation(meeting.id)).icalUid.toString())
        assertThat(asUser(secretary) { panel.calendar(meeting.id) }).isNotEmpty()
        assertThatThrownBy { asUser(reader) { panel.calendar(meeting.id) } }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { asUser(user) { panel.calendar(meeting.id) } }.isInstanceOf(MeetingNotFoundException::class.java)

        val current = meeting(meeting.id)
        asUser(secretary) { lifecycle.cancel(current.id, current.version, "Called off") }
        assertThatThrownBy { asUser(director) { panel.calendar(meeting.id) } }.isInstanceOf(MeetingNotFoundException::class.java)
    }

    @Test
    fun `a member with invitation emails switched off gets the in-app item only`() {
        transactions.inTenant(tenant.id) {
            ActorContext.asSystem("test") {
                preferences.set(director.id.value, NotificationCategory.CALENDAR, NotificationChannel.EMAIL, false)
            }
        }
        val meeting = scheduled()
        reconcile(meeting.id)
        val mine = messages(meeting.id).single { it.userId == director.id.value }
        assertThat(mine.outboxId).isNull()
        assertThat(mine.notificationId).isNotNull()
        assertThat(items(director, "MEETING_INVITATION")).hasSize(1)
        assertThat(asUser(secretary) { panel.panel(meeting.id) }.people.single { it.userId == director.id.value }.emailOff).isTrue()
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(
                c.long("select count(*) from notification_mail_outbox where to_address = ? and template like 'MEETING_%'", director.email),
            ).isZero()
        }
    }

    private fun refused(
        reason: String,
        block: () -> Any,
    ) = assertThatThrownBy { block() }.isInstanceOfSatisfying(RefusedException::class.java) { assertThat(it.reason).isEqualTo(reason) }
}
