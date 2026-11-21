package com.memo.meeting

import com.memo.meeting.internal.attendance.QuorumLevel
import com.memo.testsupport.Audit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The live meeting-level quorum (test plan E05 T4 `LiveQuorumIT`, ADR-0064, design E05 §1.7): the result after each event;
 * reached, lost and restored logged once per flip with the item in progress, audited, and lost and restored told in the
 * app to the chair and the secretaries only; the quorum at an earlier instant from the events.
 */
class LiveQuorumIT : AttendanceTestSupport() {
    override fun label() = "livequorum"

    @Test
    fun `two departures during an item lose the quorum, a return restores it, each flip logged, audited and told once`() {
        val (meeting, items) = started()
        val appetite = items.getValue("Risk appetite")
        arrive(meeting, listOf(chair, director) + others.take(3))
        val beforeLoss = Instant.now()
        asUser(secretary) { attendance.progress(meeting.id, appetite) }

        record(meeting, others[1], AttendanceEventKind.LEFT)
        assertThat(asUser(secretary) { attendanceReads.quorum(meeting.id, null) }.let { it.quorate to it.present }).isEqualTo(true to 4)
        // The meeting reached its quorum before any item; "Risk appetite" reached it as it became the item in progress (T5).
        assertThat(asUser(secretary) { attendanceReads.changes(meeting.id) }.map { "${it.level}:${it.kind}:${it.itemId}" })
            .containsExactly("MEETING:REACHED:null", "ITEM:REACHED:$appetite")

        record(meeting, others[2], AttendanceEventKind.LEFT)
        val lost = asUser(secretary) { attendanceReads.quorum(meeting.id, null) }
        assertThat(lost.quorate to lost.present).isEqualTo(false to 3)
        val audit = Audit.assertAudited(tenant.id, "meeting.quorum.lost", meeting.id.toString(), secretary.id.value.toString())
        assertThat(audit.canonical).contains(appetite.toString()).contains("\"present\":3").contains("\"needed\":4")
        assertThat(items(chair, "MEETING_QUORUM_LOST").single()).contains("\"present\": \"3\"").contains("\"needed\": \"4\"")
        assertThat(items(secretary, "MEETING_QUORUM_LOST")).describedAs("the secretary recorded it").isEmpty()
        assertThat(items(director, "MEETING_QUORUM_LOST")).describedAs("members are not told").isEmpty()

        // Still not quorate after another departure: no second "lost".
        record(meeting, director, AttendanceEventKind.LEFT)
        val back = record(meeting, director, AttendanceEventKind.ARRIVED, AttendanceMode.REMOTE)
        assertThat(back.row.standing.state).isEqualTo(AttendanceState.PRESENT)
        record(meeting, others[2], AttendanceEventKind.ARRIVED, AttendanceMode.IN_PERSON)
        Audit.assertAudited(tenant.id, "meeting.quorum.restored", meeting.id.toString())
        assertThat(items(chair, "MEETING_QUORUM_RESTORED")).hasSize(1)

        val all = asUser(chair) { attendanceReads.changes(meeting.id) }
        // The item in progress flipped with the meeting, logged and audited at its level, but not told.
        assertThat(all.filter { it.level == QuorumLevel.ITEM }.map { it.kind })
            .containsExactly(QuorumChangeKind.REACHED, QuorumChangeKind.LOST, QuorumChangeKind.RESTORED)
        assertThat(all.filter { it.level == QuorumLevel.ITEM }.map { it.itemId }.distinct()).containsExactly(appetite)
        val changes = all.filter { it.level == QuorumLevel.MEETING }
        assertThat(changes.map { it.kind }).containsExactly(QuorumChangeKind.REACHED, QuorumChangeKind.LOST, QuorumChangeKind.RESTORED)
        assertThat(changes[1].itemId).isEqualTo(appetite)
        assertThat(changes[1].unmet.map { it.kind.name }).containsExactly("COUNT")
        assertThat(transactions.readInTenant(tenant.id) { register.of(meeting.id) }?.quorumChanges?.map { it.kind })
            .containsExactly(QuorumChangeKind.REACHED, QuorumChangeKind.LOST, QuorumChangeKind.RESTORED)

        // Voiding a departure is a change too: the quorum follows.
        val leaving = record(meeting, others[0], AttendanceEventKind.LEFT)
        record(meeting, chair, AttendanceEventKind.LEFT)
        assertThat(asUser(secretary) { attendanceReads.quorum(meeting.id, null) }.quorate).isFalse()
        asUser(secretary) { attendance.void(meeting.id, leaving.event.id, leaving.row.version, "Stepped into the corridor only") }
        assertThat(
            asUser(secretary) { attendanceReads.quorum(meeting.id, null) }.quorate,
        ).describedAs("chair gone: the role is unmet").isFalse()

        // The quorum at an earlier instant is read from the events.
        val then = asUser(secretary) { attendanceReads.quorum(meeting.id, beforeLoss) }
        assertThat(then.quorate to then.present).isEqualTo(true to 5)
        assertThat(then.at).isEqualTo(beforeLoss)
    }
}
