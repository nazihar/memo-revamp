package com.memo.meeting

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingStore
import com.memo.meeting.internal.attendance.Attendance
import com.memo.meeting.internal.attendance.AttendanceInput
import com.memo.meeting.internal.attendance.AttendanceStore
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A meeting held, for seeders (the demo tenant, Phase 4 E05 T10), through the same services as the API, as the officer
 * bound to the current unit of work: starting and ending it, the register's events and the item in progress at the times
 * they happened (a meeting recorded after the fact, so the live quorum logs its flips at those times), and a decision's
 * quorum snapshot. Never called from requests. Each call is its own tenant transaction. The seeder decides what already
 * exists ("seeders only fill gaps"); the meeting's status says whether it was held.
 */
@Service
class AttendanceSeeding(
    private val lifecycle: MeetingLifecycle,
    private val meetings: MeetingStore,
    private val attendance: Attendance,
    private val store: AttendanceStore,
    private val snapshots: QuorumSnapshots,
    private val transactions: TenantTransactions,
) {
    /** Starts the scheduled meeting: its register opens with the replies' apologies. */
    fun start(meetingId: UUID) {
        tx { lifecycle.start(meetingId, meeting(meetingId).version) }
    }

    /** The meeting's date: the day it starts, in its zone (Phase 4 E06 T12: decisions are recorded at times on that day). */
    fun date(meetingId: UUID): LocalDate = tx { meeting(meetingId).date }

    /** Ends the meeting in progress: it is held. */
    fun end(meetingId: UUID) {
        tx { lifecycle.end(meetingId, meeting(meetingId).version) }
    }

    /** Records [kind] for [userId] at [at] (the item in progress then, unless [itemId]), at their register row's version. */
    fun record(
        meetingId: UUID,
        userId: UUID,
        kind: AttendanceEventKind,
        at: Instant,
        mode: AttendanceMode? = null,
        itemId: UUID? = null,
    ) {
        tx {
            val version = store.row(meetingId, userId)?.version
            attendance.record(meetingId, version, AttendanceInput(userId, kind, mode = mode, at = at, itemId = itemId))
        }
    }

    /** "Now: item n" from [at]. */
    fun progress(
        meetingId: UUID,
        itemId: UUID,
        at: Instant,
    ) {
        tx { attendance.progress(meetingId, itemId, at) }
    }

    /** The quorum of [itemId] at [at], kept as a decision's snapshot (what E06 takes with each decision); its id. */
    fun snapshot(
        meetingId: UUID,
        itemId: UUID,
        at: Instant,
    ): UUID = tx { snapshots.take(meetingId, itemId, at).id }

    private fun meeting(id: UUID) = checkNotNull(meetings.find(id)) { "meeting $id" }

    private fun <T> tx(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}
