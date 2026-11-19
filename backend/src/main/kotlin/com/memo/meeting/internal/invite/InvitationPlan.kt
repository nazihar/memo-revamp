package com.memo.meeting.internal.invite

import com.memo.notification.CalendarMethod
import java.util.UUID

/** What a person holds of a meeting's invitation (design E05 §1.1). */
enum class InviteeState { INVITED, UNINVITED, CANCELLED }

/** Why a message is sent, with its iTIP method (design E05 §1.1, ADR-0063). */
enum class InvitationMessageKind(
    val method: CalendarMethod,
) {
    /** The first REQUEST a person gets, or the one that invites them again after an uninvitation. */
    INVITATION(CalendarMethod.REQUEST),

    /** The meeting changed (time, place, link or title): a REQUEST with a higher sequence. */
    UPDATE(CalendarMethod.REQUEST),

    /** The current version again, at the person's last sequence (RFC 5546 §3.2.2.2), asked for by a secretary. */
    RESEND(CalendarMethod.REQUEST),

    /** The person left the audience: CANCEL without STATUS (RFC 5546 §3.2.5). */
    UNINVITE(CalendarMethod.CANCEL),

    /** The meeting was called off: CANCEL with `STATUS:CANCELLED`. */
    CANCEL(CalendarMethod.CANCEL),
}

/** What changed in a meeting, as an update names it (design E05 §1.1); in the order the email lists them. */
enum class MeetingChangeKind { TIME, PLACE, LINK, TITLE }

/**
 * A person's copy of the invitation: [lastSequence] is the SEQUENCE of the last file they were sent (it never goes down),
 * [heldSequence] the invitation's sequence that file described.
 */
data class Holding(
    val userId: UUID,
    val state: InviteeState,
    val lastSequence: Int,
    val heldSequence: Int,
)

/** One message to send: to [userId], of [kind], with SEQUENCE [sequence]. */
data class PlannedMessage(
    val userId: UUID,
    val kind: InvitationMessageKind,
    val sequence: Int,
)

/**
 * The sequence rules of meeting invitations (ADR-0063, design E05 §1.1, §1.3), without I/O so that they can be tested as
 * properties (`InvitationSequenceProperties`):
 *  - a person's sequences never go down and rise with every message but a resend: a message after an earlier one carries
 *    `max(invitation sequence, their last + 1)`, so an uninvitation and a re-invitation never fall behind what they hold;
 *  - the invitation's sequence rises once per batch of changes ([afterChange]), so edits that wait together for the next
 *    send make one update;
 *  - a cancelled meeting sends only CANCEL, once, to everyone who holds the event, and nothing after.
 */
object InvitationPlan {
    /** The invitation's sequence after a change: raised only when the current one was already sent. */
    fun afterChange(
        sequence: Int,
        sentSequence: Int?,
    ): Int = if (sentSequence == sequence) sequence + 1 else sequence

    /**
     * The messages that bring every person in line with the meeting: [invitable] are the people who should hold the
     * event, [holdings] what each person ever sent holds; [cancelled] when the meeting was called off. Sorted by user.
     */
    fun reconcile(
        sequence: Int,
        cancelled: Boolean,
        invitable: Set<UUID>,
        holdings: Map<UUID, Holding>,
    ): List<PlannedMessage> {
        val messages =
            if (cancelled) {
                holdings.values.filter { it.state == InviteeState.INVITED }.map {
                    PlannedMessage(
                        it.userId,
                        InvitationMessageKind.CANCEL,
                        next(sequence, it),
                    )
                }
            } else {
                invitable.mapNotNull { request(sequence, it, holdings[it]) } +
                    holdings.values
                        .filter { it.state == InviteeState.INVITED && it.userId !in invitable }
                        .map { PlannedMessage(it.userId, InvitationMessageKind.UNINVITE, next(sequence, it)) }
            }
        return messages.sortedBy { it.userId }
    }

    /**
     * A secretary's resend to one INVITED person: the current version at their last sequence, or, when an update is still
     * waiting for them, that update now. Null when they do not hold the event.
     */
    fun resend(
        sequence: Int,
        holding: Holding?,
    ): PlannedMessage? =
        when {
            holding == null || holding.state != InviteeState.INVITED -> null
            holding.heldSequence < sequence -> PlannedMessage(holding.userId, InvitationMessageKind.UPDATE, next(sequence, holding))
            else -> PlannedMessage(holding.userId, InvitationMessageKind.RESEND, holding.lastSequence)
        }

    /** What [holding] becomes once [message] is sent for the invitation at [sequence]. */
    fun after(
        holding: Holding?,
        message: PlannedMessage,
        sequence: Int,
    ): Holding {
        val state =
            when (message.kind) {
                InvitationMessageKind.INVITATION, InvitationMessageKind.UPDATE, InvitationMessageKind.RESEND -> InviteeState.INVITED
                InvitationMessageKind.UNINVITE -> InviteeState.UNINVITED
                InvitationMessageKind.CANCEL -> InviteeState.CANCELLED
            }
        val held = if (message.kind == InvitationMessageKind.RESEND) holding?.heldSequence ?: sequence else sequence
        return Holding(message.userId, state, message.sequence, held)
    }

    private fun request(
        sequence: Int,
        userId: UUID,
        holding: Holding?,
    ): PlannedMessage? =
        when {
            holding == null -> PlannedMessage(userId, InvitationMessageKind.INVITATION, sequence)
            holding.state != InviteeState.INVITED -> PlannedMessage(userId, InvitationMessageKind.INVITATION, next(sequence, holding))
            holding.heldSequence < sequence -> PlannedMessage(userId, InvitationMessageKind.UPDATE, next(sequence, holding))
            else -> null
        }

    /** The sequence of a message to someone who already has a copy: never below the invitation's, always above theirs. */
    private fun next(
        sequence: Int,
        holding: Holding,
    ): Int = maxOf(sequence, holding.lastSequence + 1)
}
