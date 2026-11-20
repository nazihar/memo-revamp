package com.memo.meeting.internal.quorum

import com.memo.forum.ForumSeat
import com.memo.forum.QuorumPresence
import com.memo.forum.QuorumSeat
import java.util.UUID

/** A person in the room at an instant: online or not, and the item they stepped out of, if any. */
data class PersonPresence(
    val remote: Boolean = false,
    val steppedOutOf: UUID? = null,
)

/** A roster seat present at an instant (design E05 §1.7): who fills it ([userId], the holder or the alternate) and how. */
data class SeatPresence(
    val memberId: UUID,
    /** The seat's holder on the meeting's date. */
    val holderId: UUID,
    /** The person counted for the seat. */
    val userId: UUID,
    val remote: Boolean,
    val byAlternate: Boolean,
    /** The holder is recused from the item evaluated (E04); only a holder is ever present recused. */
    val recused: Boolean,
) {
    fun quorum() = QuorumPresence(remote = remote, byAlternate = byAlternate, recused = recused)
}

/**
 * Who fills which seat at an instant (ADR-0064, design E05 §1.7), pure: for the meeting ([item] null) or for one item.
 *  - A seat's holder fills it when present and not stepped out of the item; recused from the item, they fill it as
 *    recused (the rule's `recusedCount` decides).
 *  - Otherwise the alternate standing in for the seat fills it when present, not stepped out, not counted for anything
 *    else, holding no seat themselves, and neither they nor the holder is recused from the item (an alternate shares the
 *    member's restrictions, art. 26(2)).
 *  - A person fills at most one seat: a holder of several fills the first in [ORDER], an alternate one seat.
 * Seats nobody fills are absent and left out.
 */
object PresenceBuilder {
    fun build(
        roster: List<ForumSeat>,
        /** The alternate standing in, by seat (forum membership id). */
        standIns: Map<UUID, UUID>,
        /** The people present at the instant. */
        present: Map<UUID, PersonPresence>,
        item: UUID? = null,
        /** The people recused from [item]. */
        recused: Set<UUID> = emptySet(),
    ): List<SeatPresence> {
        val seats = roster.sortedWith(ORDER)
        val holders = roster.map { it.userId }.toSet()
        val inRoom = present.filterValues { item == null || it.steppedOutOf != item }

        fun here(user: UUID): PersonPresence? = inRoom[user]
        val restricted = if (item == null) emptySet() else recused
        val filled = holders(seats, inRoom, restricted)
        val counted = filled.values.map { it.userId }.toMutableSet()
        seats.forEach { seat ->
            val alternate = standIns[seat.memberId] ?: return@forEach
            val standing = here(alternate) ?: return@forEach
            val open = seat.memberId !in filled && here(seat.userId) == null
            if (open && mayStandIn(seat, alternate, holders + counted, restricted) && counted.add(alternate)) {
                filled[seat.memberId] = SeatPresence(seat.memberId, seat.userId, alternate, standing.remote, true, false)
            }
        }
        return filled.values.toList()
    }

    /** The seats their holders fill, each holder once, by seat; recused when in [restricted]. */
    private fun holders(
        seats: List<ForumSeat>,
        inRoom: Map<UUID, PersonPresence>,
        restricted: Set<UUID>,
    ): LinkedHashMap<UUID, SeatPresence> {
        val counted = mutableSetOf<UUID>()
        val filled = linkedMapOf<UUID, SeatPresence>()
        seats.forEach { seat ->
            val holder = inRoom[seat.userId]
            if (holder != null && counted.add(seat.userId)) {
                filled[seat.memberId] =
                    SeatPresence(seat.memberId, seat.userId, seat.userId, holder.remote, false, seat.userId in restricted)
            }
        }
        return filled
    }

    /**
     * Whether [alternate] may fill [seat]: they hold no seat and count for nothing else ([taken]), and neither they nor the
     * seat's holder is recused from the item ([restricted]).
     */
    private fun mayStandIn(
        seat: ForumSeat,
        alternate: UUID,
        taken: Set<UUID>,
        restricted: Set<UUID>,
    ): Boolean = alternate !in taken && seat.userId !in restricted && alternate !in restricted

    /** The attendance [com.memo.forum.Quorum.evaluate] takes. */
    fun attendance(seats: Collection<SeatPresence>): Map<UUID, QuorumPresence> = seats.associate { it.memberId to it.quorum() }

    /** The roster as [com.memo.forum.Quorum.evaluate] sees it. */
    fun seats(roster: Collection<ForumSeat>): List<QuorumSeat> = roster.map { QuorumSeat(it.memberId, it.role, it.voting, it.attributes) }

    /** A holder of several seats fills the one that counts most: countable, then voting, then by role and id. */
    private val ORDER =
        compareBy<ForumSeat>({ !it.role.countable }, { !it.voting }, { it.role.ordinal }, { it.memberId })
}
