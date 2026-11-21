package com.memo.meeting

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.meeting.internal.rsvp.Replies
import com.memo.meeting.internal.rsvp.RsvpAnswer
import com.memo.meeting.internal.rsvp.RsvpHow
import com.memo.meeting.internal.rsvp.RsvpInput
import com.memo.meeting.internal.rsvp.RsvpStore
import org.springframework.stereotype.Service
import java.util.UUID

/** "Will you attend?" as a seeder answers it (the replies' own answers are internal). */
enum class SeedReply(
    internal val answer: RsvpAnswer,
) {
    IN_PERSON(RsvpAnswer.IN_PERSON),
    ONLINE(RsvpAnswer.REMOTE),
    APOLOGIES(RsvpAnswer.APOLOGIES),
}

/**
 * Replies to invitations for seeders (the demo tenant, Phase 4 E05 T10), through the same services as the API, as the
 * user bound to the current unit of work: answering for oneself (apologies may bring the seat's alternate) and an officer
 * recording an answer given by phone. Never called from requests. Each call is its own tenant transaction. The seeder
 * decides what already exists ("seeders only fill gaps"): [answered] says whether a person has an answer, current or stale.
 */
@Service
class ReplySeeding(
    private val replies: Replies,
    private val store: RsvpStore,
    private val transactions: TenantTransactions,
) {
    /** Whether [userId] has answered [meetingId], for its current times or for earlier ones. */
    fun answered(
        meetingId: UUID,
        userId: UUID,
    ): Boolean = tx { store.find(meetingId, userId) != null }

    /** The bound person answers [reply]; with apologies, [alternateAttends] asks the seat's alternate to attend. */
    fun answer(
        meetingId: UUID,
        reply: SeedReply,
        alternateAttends: Boolean = false,
        note: String? = null,
    ) {
        tx { replies.answer(meetingId, null, RsvpInput(reply.answer, alternateAttends, note)) }
    }

    /** The bound officer records [userId]'s [reply], given by phone ([howNote]: e.g. when). */
    fun recordByPhone(
        meetingId: UUID,
        userId: UUID,
        reply: SeedReply,
        howNote: String,
    ) {
        tx { replies.record(meetingId, userId, null, RsvpInput(reply.answer, how = RsvpHow.PHONE, howNote = howNote)) }
    }

    private fun <T> tx(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}
