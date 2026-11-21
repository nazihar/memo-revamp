package com.memo.minutes

import com.memo.common.web.RefusedException
import com.memo.forum.OutcomeKind
import com.memo.meeting.DeclarationHow
import com.memo.meeting.DeclarationIntent
import com.memo.meeting.internal.DeclareInput
import com.memo.meeting.internal.MeetingRejectedException
import com.memo.minutes.internal.DecisionInput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * E04's declarations on a decided item (test plan E06 T3 `DecidedItemsPortIT`; design E04 §1.1, E06 §2): through the
 * meeting module's `DecidedItems` port, which the minutes module implements, a decided item's declarations change only by
 * a secretary's correction (with a reason), even while the meeting is still in progress; an undecided item's as before.
 */
class DecidedItemsPortIT : DecisionFixture() {
    override fun label() = "decided"

    @Test
    fun `once an item is decided its declarations change only through the secretary's correction`() {
        val d = deciding()
        arrive(d.meeting, listOf(chair, director, director2, others[0], others[1]))
        record(d, d.appetite, DecisionInput(OutcomeKind.NOTED))
        assertThat(transactions.readInTenant(tenant.id) { decisionStore.decided(d.appetite) }).isTrue()

        // The member declares on the undecided item as before; on the decided one: FROZEN.
        asUser(director) { declarations.declare(d.meeting.id, d.credit, DeclareInput("My brother is a director")) }
        val frozen =
            runCatching { asUser(director) { declarations.declare(d.meeting.id, d.appetite, DeclareInput("My brother is a director")) } }
        assertThat((frozen.exceptionOrNull() as RefusedException).reason).isEqualTo("FROZEN")

        // The secretary records one made in the room, as a correction with its reason.
        val onBehalf =
            DeclareInput("Holds shares in the bank's insurer", DeclarationIntent.LEAVE, director2.id.value, DeclarationHow.AT_MEETING)
        val noReason = runCatching { asUser(secretary) { declarations.declare(d.meeting.id, d.appetite, onBehalf) } }.exceptionOrNull()
        assertThat((noReason as MeetingRejectedException).problems.map { it.code }).containsExactly("REASON_REQUIRED")
        val recorded = asUser(secretary) { declarations.declare(d.meeting.id, d.appetite, onBehalf.copy(reason = "Declared in the room")) }
        assertThat(recorded.correction).isTrue()

        // The declarant cannot change their intent on the decided item; the secretary withdraws one entered in error.
        val intent =
            runCatching {
                asUser(
                    director2,
                ) { declarations.changeIntent(d.meeting.id, recorded.id, recorded.version, DeclarationIntent.ASK_CHAIR) }
            }
        assertThat((intent.exceptionOrNull() as RefusedException).reason).isEqualTo("FROZEN")
        val withdrawn = asUser(secretary) { declarations.withdraw(d.meeting.id, recorded.id, recorded.version, "Entered in error") }
        assertThat(withdrawn.state).isEqualTo(com.memo.meeting.DeclarationState.WITHDRAWN)
    }
}
