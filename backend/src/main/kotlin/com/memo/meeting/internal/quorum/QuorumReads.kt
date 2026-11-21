package com.memo.meeting.internal.quorum

import com.memo.common.authz.ForbiddenException
import com.memo.forum.ForumRole
import com.memo.forum.QuorumResult
import com.memo.forum.QuorumRule
import com.memo.forum.UnmetCondition
import com.memo.identity.UserSummaries
import com.memo.meeting.QuorumSnapshot
import com.memo.meeting.QuorumSnapshots
import com.memo.meeting.internal.MeetingGuard
import com.memo.meeting.internal.MeetingNotFoundException
import com.memo.meeting.internal.MeetingRelation
import com.memo.meeting.internal.actorOrNull
import com.memo.meeting.internal.attendance.AttendancePersonRef
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** A quorum's numbers: quorate or not, the basis, needed and present, and the conditions not met. */
data class QuorumNumbersResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val quorate: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val basis: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val needed: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val present: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val unmet: List<UnmetCondition>,
) {
    companion object {
        fun of(result: QuorumResult) = QuorumNumbersResponse(result.quorate, result.basis, result.needed, result.present, result.unmet)
    }
}

/** One published item's forecast (flow W): "Item 5: not quorate even then (3 of 4; 2 members have declared an interest)". */
data class ItemForecastResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val itemId: UUID,
    val number: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val quorum: QuorumNumbersResponse,
    /** Counting members recused from the item; null for an item the viewer is recused from. */
    val declared: Int?,
    /** The viewer is recused from the item: the counts only. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val viewerRecused: Boolean,
)

/** The forecast from the replies (design E05 §1.7, flow W): the meeting and every published item, for officers. */
data class QuorumForecastResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    /** "If everyone who said yes attends". */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meeting: QuorumNumbersResponse,
    /** The latest published revision's items, in agenda order. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val items: List<ItemForecastResponse>,
)

/** A seat of a snapshot: its holder, and who filled it then. */
data class SnapshotSeatResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val memberId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val holder: AttendancePersonRef,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val role: ForumRole,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val voting: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val attributes: List<String>,
    /** The holder or the alternate standing in; null when the seat was empty. */
    val filledBy: AttendancePersonRef?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val byAlternate: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val remote: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val recused: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val steppedOut: Boolean,
)

/** The override of a snapshot: who recorded the decision without a quorum, and (for officers not recused) why. */
data class SnapshotOverrideResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val by: AttendancePersonRef,
    /** Null for a reader, and for an officer recused from the item. */
    val reason: String?,
)

/** A recorded snapshot (design E05 §3): what a decision was taken with. */
data class QuorumSnapshotResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    val itemId: UUID?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val at: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val quorum: QuorumNumbersResponse,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val typeVersion: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val rule: QuorumRule,
    /** The seats, for officers and readers; null for an officer recused from the item (the counts only). */
    val seats: List<SnapshotSeatResponse>?,
    val override: SnapshotOverrideResponse?,
    /** "To be ratified at a quorate meeting". */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val ratificationRequired: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val inputsSha256: String,
    val takenBy: AttendancePersonRef?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val takenAt: Instant,
    /** The register was corrected after the snapshot: evaluated again now, the inputs differ. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val drifted: Boolean,
)

/**
 * Reading the forecast and the snapshots (design E05 §3, §4), inside a tenant transaction:
 *  - the forecast: the officers (secretaries, the chair, `forums.manage`); for an item the officer is recused from, the
 *    counts only;
 *  - a snapshot: the officers and `forum.read` (cleared for the meeting); seats with names, but the counts only for an
 *    officer recused from the item; the override's reason for officers not recused only (a reason is free text).
 * Everyone else who sees the meeting is 403; an invisible meeting or a snapshot of another meeting 404.
 */
@Component
class QuorumReads(
    private val guard: MeetingGuard,
    private val forecasts: QuorumForecast,
    private val snapshots: QuorumSnapshots,
    private val users: UserSummaries,
) {
    fun forecast(meetingId: UUID): QuorumForecastResponse {
        val (meeting, relation) = guard.requireVisible(meetingId)
        if (relation != MeetingRelation.OFFICER) throw ForbiddenException(null)
        val forecast = forecasts.of(meeting)
        val actor = actorOrNull()
        return QuorumForecastResponse(
            meetingId = meeting.id,
            meeting = QuorumNumbersResponse.of(forecast.result),
            items =
                forecast.items.map { item ->
                    val recused = actor in item.recused
                    ItemForecastResponse(
                        item.itemId,
                        item.number,
                        QuorumNumbersResponse.of(item.result),
                        item.declared.takeUnless { recused },
                        recused,
                    )
                },
        )
    }

    fun snapshot(
        meetingId: UUID,
        snapshotId: UUID,
    ): QuorumSnapshotResponse {
        val (meeting, relation) = guard.requireVisible(meetingId)
        if (relation != MeetingRelation.OFFICER && relation != MeetingRelation.READER) throw ForbiddenException(null)
        val snapshot = snapshots.find(snapshotId)?.takeIf { it.meetingId == meeting.id } ?: throw MeetingNotFoundException("snapshot")
        val actor = actorOrNull()
        val recused = actor in snapshot.inputs.recused
        val officer = relation == MeetingRelation.OFFICER
        val names = users.names(people(snapshot))

        fun ref(user: UUID) = AttendancePersonRef(user, names[user].orEmpty())
        return QuorumSnapshotResponse(
            id = snapshot.id,
            meetingId = snapshot.meetingId,
            itemId = snapshot.itemId,
            at = snapshot.at,
            quorum = QuorumNumbersResponse.of(snapshot.result),
            typeVersion = snapshot.inputs.typeVersion,
            rule = snapshot.inputs.rule,
            seats = if (officer && recused) null else seats(snapshot, ::ref),
            override = snapshot.override?.let { SnapshotOverrideResponse(ref(it.by), it.reason.takeIf { officer && !recused }) },
            ratificationRequired = snapshot.ratificationRequired,
            inputsSha256 = snapshot.inputsSha256,
            takenBy = snapshot.takenBy?.let(::ref),
            takenAt = snapshot.takenAt,
            drifted = snapshots.drifted(snapshot.id)?.drifted ?: false,
        )
    }

    private fun seats(
        snapshot: QuorumSnapshot,
        ref: (UUID) -> AttendancePersonRef,
    ): List<SnapshotSeatResponse> {
        val filled = snapshot.inputs.presence.associateBy { it.memberId }
        val out = snapshot.inputs.steppedOut.toSet()
        return snapshot.inputs.roster.map { seat ->
            val by = filled[seat.memberId]
            SnapshotSeatResponse(
                memberId = seat.memberId,
                holder = ref(seat.userId),
                role = seat.role,
                voting = seat.voting,
                attributes = seat.attributes,
                filledBy = by?.let { ref(it.userId) },
                byAlternate = by?.byAlternate ?: false,
                remote = by?.remote ?: false,
                recused = seat.userId in snapshot.inputs.recused,
                steppedOut = seat.userId in out,
            )
        }
    }

    private fun people(snapshot: QuorumSnapshot): Set<UUID> =
        snapshot.inputs.roster
            .map { it.userId }
            .toSet() + snapshot.inputs.presence.map { it.userId } + setOfNotNull(snapshot.takenBy, snapshot.override?.by)
}
