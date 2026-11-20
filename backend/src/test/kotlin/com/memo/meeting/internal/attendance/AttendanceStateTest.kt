package com.memo.meeting.internal.attendance

import com.memo.common.web.RefusedException
import com.memo.meeting.AttendanceEventKind
import com.memo.meeting.AttendanceEventKind.ABSENT
import com.memo.meeting.AttendanceEventKind.APOLOGIES
import com.memo.meeting.AttendanceEventKind.ARRIVED
import com.memo.meeting.AttendanceEventKind.LEFT
import com.memo.meeting.AttendanceEventKind.MODE_CHANGED
import com.memo.meeting.AttendanceEventKind.STEPPED_BACK
import com.memo.meeting.AttendanceEventKind.STEPPED_OUT
import com.memo.meeting.AttendanceEventKind.VOIDED
import com.memo.meeting.AttendanceMode
import com.memo.meeting.AttendanceSource
import com.memo.meeting.AttendanceState
import com.memo.meeting.MeetingKind
import com.memo.meeting.MeetingMode
import com.memo.meeting.MeetingStatus
import com.memo.meeting.internal.MeetingRow
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * The attendance register's state machine (test plan E05 T4 `AttendanceStateTest`, design E05 §1.6): every allowed and
 * forbidden event for each person state and each meeting status, voiding, the HELD correction window and FROZEN.
 */
class AttendanceStateTest {
    private val start: Instant = Instant.parse("2026-12-12T04:00:00Z")
    private val item: UUID = UUID(1, 5)
    private var n = 0L

    private fun event(
        kind: AttendanceEventKind,
        minutes: Long = n,
        mode: AttendanceMode? = if (kind == ARRIVED || kind == MODE_CHANGED) AttendanceMode.IN_PERSON else null,
        itemId: UUID? = if (kind == STEPPED_OUT || kind == STEPPED_BACK) item else null,
        voids: UUID? = null,
    ): AttendanceEventRow {
        n++
        return AttendanceEventRow(
            id = UUID(0, n),
            meetingId = UUID(9, 9),
            userId = UUID(7, 7),
            kind = kind,
            mode = mode,
            at = start.plus(Duration.ofMinutes(minutes)),
            itemId = itemId,
            voidsEventId = voids,
            reason = if (kind == VOIDED) "wrong" else null,
            note = null,
            source = AttendanceSource.RECORDED,
            correction = false,
            recordedBy = null,
            recordedAt = start.plus(Duration.ofMinutes(n)),
        )
    }

    /** A person in each state, by the events that put them there. */
    private fun reach(state: AttendanceState): List<AttendanceEventRow> =
        when (state) {
            AttendanceState.EXPECTED -> emptyList()
            AttendanceState.PRESENT -> listOf(event(ARRIVED))
            AttendanceState.LEFT -> listOf(event(ARRIVED), event(LEFT))
            AttendanceState.APOLOGIES -> listOf(event(APOLOGIES))
            AttendanceState.ABSENT -> listOf(event(ABSENT))
        }

    /** The allowed (kind, state) pairs and the state after (design E05 §1.6's diagram). */
    private val allowed: Map<Pair<AttendanceEventKind, AttendanceState>, AttendanceState> =
        mapOf(
            (APOLOGIES to AttendanceState.EXPECTED) to AttendanceState.APOLOGIES,
            (ABSENT to AttendanceState.EXPECTED) to AttendanceState.ABSENT,
            (ARRIVED to AttendanceState.EXPECTED) to AttendanceState.PRESENT,
            (ARRIVED to AttendanceState.APOLOGIES) to AttendanceState.PRESENT,
            (ARRIVED to AttendanceState.LEFT) to AttendanceState.PRESENT,
            (MODE_CHANGED to AttendanceState.PRESENT) to AttendanceState.PRESENT,
            (LEFT to AttendanceState.PRESENT) to AttendanceState.LEFT,
            (STEPPED_OUT to AttendanceState.PRESENT) to AttendanceState.PRESENT,
        )

    @Test
    fun `every allowed event leads where the diagram says, every other is WRONG_STATE`() {
        val kinds = AttendanceEventKind.entries - VOIDED - STEPPED_BACK
        kinds.forEach { kind ->
            AttendanceState.entries.forEach { state ->
                val before = reach(state)
                val mode = if (kind == MODE_CHANGED) AttendanceMode.REMOTE else null
                val next = event(kind, mode = mode ?: if (kind == ARRIVED) AttendanceMode.IN_PERSON else null)
                val expected = allowed[kind to state]
                if (expected != null) {
                    assertThat(AttendanceRules.replay(before + next).state).describedAs("$kind from $state").isEqualTo(expected)
                } else {
                    assertThat(reason { AttendanceRules.replay(before + next) }).describedAs("$kind from $state").isEqualTo("WRONG_STATE")
                }
            }
        }
    }

    @Test
    fun `stepping out is once at a time, back only into the item stepped out of, and leaving clears it`() {
        val arrived = event(ARRIVED)
        val out = event(STEPPED_OUT)
        assertThat(AttendanceRules.replay(listOf(arrived, out)).steppedOutOf).isEqualTo(item)
        assertThat(reason { AttendanceRules.replay(listOf(arrived, out, event(STEPPED_OUT))) }).isEqualTo("WRONG_STATE")
        assertThat(reason { AttendanceRules.replay(listOf(arrived, event(STEPPED_BACK))) }).isEqualTo("WRONG_STATE")
        assertThat(
            reason { AttendanceRules.replay(listOf(arrived, out, event(STEPPED_BACK, itemId = UUID(1, 6)))) },
        ).isEqualTo("WRONG_STATE")
        val back = AttendanceRules.replay(listOf(arrived, out, event(STEPPED_BACK)))
        assertThat(back.steppedOutOf).isNull()
        assertThat(back.steppedOutItems).containsExactly(item)
        assertThat(AttendanceRules.replay(listOf(arrived, out, event(LEFT))).steppedOutOf).isNull()
        // The same mode again is no change.
        assertThat(reason { AttendanceRules.replay(listOf(arrived, event(MODE_CHANGED, mode = AttendanceMode.IN_PERSON))) })
            .isEqualTo("WRONG_STATE")
    }

    @Test
    fun `arrivals and departures keep the first arrival, the last departure and their items`() {
        val first = event(ARRIVED, minutes = 5, itemId = UUID(1, 1))
        val left = event(LEFT, minutes = 50, itemId = UUID(1, 6))
        val back = event(ARRIVED, minutes = 70, mode = AttendanceMode.REMOTE, itemId = UUID(1, 8))
        val standing = AttendanceRules.replay(listOf(back, left, first))
        assertThat(standing.state to standing.mode).isEqualTo(AttendanceState.PRESENT to AttendanceMode.REMOTE)
        assertThat(standing.firstArrivedAt).isEqualTo(first.at)
        assertThat(standing.arrivedDuringItem).isEqualTo(UUID(1, 1))
        assertThat(standing.lastLeftAt to standing.leftDuringItem).isEqualTo(left.at to UUID(1, 6))
        // Replayed up to an instant: the state then.
        assertThat(AttendanceRules.replay(listOf(first, left, back), until = start.plus(Duration.ofMinutes(60))).state)
            .isEqualTo(AttendanceState.LEFT)
        assertThat(AttendanceRules.replay(listOf(first, left, back), until = start).state).isEqualTo(AttendanceState.EXPECTED)
    }

    @Test
    fun `a void re-derives the state without the event, a sequence it would break is refused, and voids are final`() {
        val arrived = event(ARRIVED)
        val left = event(LEFT)
        val voidLeft = event(VOIDED, voids = left.id)
        assertThat(AttendanceRules.replay(listOf(arrived, left, voidLeft)).state).isEqualTo(AttendanceState.PRESENT)
        // Voiding the arrival would leave a departure of someone never present.
        val voidArrival = event(VOIDED, voids = arrived.id)
        assertThat(reason { AttendanceRules.replay(listOf(arrived, left, voidArrival)) }).isEqualTo("WRONG_STATE")
        assertThat(reason { AttendanceRules.voidable(voidLeft, listOf(arrived, left, voidLeft)) }).isEqualTo("WRONG_STATE")
        assertThat(reason { AttendanceRules.voidable(left, listOf(arrived, left, voidLeft)) }).isEqualTo("WRONG_STATE")
        AttendanceRules.voidable(arrived, listOf(arrived, left, voidLeft))
        // An insertion before a later event must keep the sequence valid too.
        val late = event(LEFT, minutes = -1)
        assertThat(reason { AttendanceRules.replay(listOf(arrived, late)) }).isEqualTo("WRONG_STATE")
    }

    @Test
    fun `the meeting's status decides what is recorded, and after the meeting only corrections`() {
        AttendanceEventKind.entries.forEach { kind ->
            assertThat(reason { AttendanceRules.window(kind, MeetingStatus.DRAFT) }).isEqualTo("WRONG_STATE")
            listOf(MeetingStatus.MINUTES_APPROVED, MeetingStatus.CLOSED, MeetingStatus.CANCELLED).forEach { status ->
                assertThat(reason { AttendanceRules.window(kind, status) }).describedAs("$kind $status").isEqualTo("FROZEN")
            }
            assertThat(AttendanceRules.window(kind, MeetingStatus.IN_PROGRESS)).describedAs("$kind in progress: no correction").isFalse()
            if (kind == APOLOGIES || kind == VOIDED) {
                assertThat(AttendanceRules.window(kind, MeetingStatus.SCHEDULED)).isFalse()
            } else {
                assertThat(reason { AttendanceRules.window(kind, MeetingStatus.SCHEDULED) }).describedAs("$kind").isEqualTo("WRONG_STATE")
            }
            if (kind in setOf(ARRIVED, LEFT, ABSENT, APOLOGIES, VOIDED)) {
                assertThat(AttendanceRules.window(kind, MeetingStatus.HELD)).describedAs("$kind held: a correction").isTrue()
            } else {
                assertThat(reason { AttendanceRules.window(kind, MeetingStatus.HELD) }).describedAs("$kind").isEqualTo("WRONG_STATE")
            }
        }
    }

    @Test
    fun `times lie within the meeting's window and never in the future, and late is after the first item`() {
        val meeting = meeting()
        val now = start.plus(Duration.ofHours(1))
        assertThat(AttendanceRules.time(ARRIVED, start.minus(Duration.ofMinutes(59)), meeting, now)).isNull()
        assertThat(AttendanceRules.time(ARRIVED, start.minus(Duration.ofMinutes(61)), meeting, now)?.code).isEqualTo("TIME_OUT_OF_RANGE")
        assertThat(AttendanceRules.time(LEFT, now.plus(Duration.ofMinutes(5)), meeting, now)?.code).isEqualTo("TIME_OUT_OF_RANGE")
        val muchLater = meeting.endsAt.plus(Duration.ofHours(7))
        assertThat(AttendanceRules.time(ARRIVED, meeting.endsAt.plus(Duration.ofHours(5)), meeting, muchLater)).isNull()
        assertThat(AttendanceRules.time(ARRIVED, meeting.endsAt.plus(Duration.ofMinutes(361)), meeting, muchLater)).isNotNull()
        // Apologies carry no meeting time: recorded days before, they are fine.
        assertThat(AttendanceRules.time(APOLOGIES, start.minus(Duration.ofDays(3)), meeting, start.minus(Duration.ofDays(3)))).isNull()

        val noItem = AttendanceRules.lateAfter(meeting, emptyList())
        assertThat(noItem).isEqualTo(start.plus(Duration.ofMinutes(10)))
        val firstItem = start.plus(Duration.ofMinutes(3))
        val progress = listOf(ItemProgressRow(UUID(2, 1), meeting.id, item, firstItem, null))
        assertThat(AttendanceRules.lateAfter(meeting, progress)).isEqualTo(firstItem)
        assertThat(AttendanceRules.joinedLate(Standing(firstArrivedAt = firstItem.plusSeconds(1)), firstItem)).isTrue()
        assertThat(AttendanceRules.joinedLate(Standing(firstArrivedAt = firstItem), firstItem)).isFalse()
        assertThat(AttendanceRules.joinedLate(Standing(), firstItem)).isFalse()
    }

    private fun meeting() =
        MeetingRow(
            id = UUID(9, 9),
            forumId = UUID(9, 1),
            typeVersion = 1,
            kind = MeetingKind.ORDINARY,
            title = null,
            startsAt = start,
            endsAt = start.plus(Duration.ofHours(3)),
            zone = ZoneId.of("Asia/Dhaka"),
            mode = MeetingMode.HYBRID,
            location = null,
            joinUrl = null,
            joinDetails = null,
            classificationId = UUID(9, 2),
            number = null,
            numberYear = null,
            label = null,
            status = MeetingStatus.IN_PROGRESS,
            statusReason = null,
            createdBy = null,
            createdAt = start,
            version = 0,
        )

    private fun reason(block: () -> Unit): String? =
        runCatching(block).exceptionOrNull().let { (it as? RefusedException)?.reason ?: throw AssertionError("not refused", it) }
}
