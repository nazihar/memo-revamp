package com.memo.meeting

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.MEETING
import com.memo.meeting.internal.ical.InviteRefusedException
import com.memo.meeting.internal.invite.InvitationSafetyNet
import com.memo.notification.MailState
import com.memo.testsupport.Audit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * When an invitation cannot be built (test plan E05 T2 `InvitationFailureIT`, ADR-0063): the run fails, sends nothing,
 * audits `meeting.invitation.validation_failed` without content, and keeps the send due; the safety net finds it once
 * overdue, and after the cause is fixed the next run sends. With no relay reachable the outbox rows stay pending, and the
 * panel shows them as not yet delivered.
 */
class InvitationFailureIT : InvitationTestSupport() {
    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var safetyNet: InvitationSafetyNet

    override fun label() = "invitefail"

    @Test
    fun `a refused file fails the run, is audited, and goes once the cause is fixed`() {
        val meeting = scheduled()
        // A link no calendar can use (the API refuses it; here it is written directly).
        joinUrl(meeting.id, "https://")

        assertThatThrownBy { reconcile(meeting.id) }.isInstanceOf(InviteRefusedException::class.java)
        assertThat(messages(meeting.id)).isEmpty()
        assertThat(checkNotNull(invitation(meeting.id)).dueAt).isNotNull()
        val audit = Audit.assertAudited(tenant.id, "meeting.invitation.validation_failed", meeting.id.toString())
        assertThat(audit.canonical).contains("not").doesNotContain("Board Room")

        // The safety net picks up a send overdue by 15 minutes (scheduling is off here, so it only reports it).
        val net = { at: Instant -> transactions.inTenant(tenant.id) { safetyNet.rescheduleOverdue(at) } }
        assertThat(net(Instant.now())).doesNotContain(meeting.id)
        assertThat(net(Instant.now().plus(Duration.ofMinutes(20)))).contains(meeting.id)

        joinUrl(meeting.id, "https://meet.example/abc")
        assertThat(reconcile(meeting.id)).isNull()
        assertThat(messages(meeting.id)).hasSize(4)
        assertThat(net(Instant.now().plus(Duration.ofMinutes(20)))).doesNotContain(meeting.id)

        // No relay in this test: the mails wait in the outbox, shown as not yet delivered.
        val people = asUser(secretary) { panel.panel(meeting.id) }.people
        assertThat(people.map { it.delivery?.state }.toSet()).containsExactly(MailState.PENDING)
    }

    private fun joinUrl(
        meetingId: UUID,
        url: String,
    ) {
        transactions.inTenant(tenant.id) {
            tenantDsl
                .current()
                .update(MEETING)
                .set(MEETING.JOIN_URL, url)
                .where(MEETING.ID.eq(meetingId))
                .execute()
        }
    }
}
