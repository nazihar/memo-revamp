package com.memo.meeting

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents

/**
 * The published `Recusals` API and `DeclarationChanged` (test plan E04, T2; design E04 §2), for E05's quorum and E06's
 * decisions and minutes: who is recused from an item (its parent's declarations included), per meeting, and every
 * declaration with its ruling; one event per change, with the tenant, saying whether the person is now recused.
 */
@RecordApplicationEvents
class RecusalsApiIT : RecusalFixture() {
    @Autowired lateinit var recusals: Recusals

    @Autowired lateinit var events: ApplicationEvents

    @Test
    fun `Recusals answers who is recused from what, and each change is told once`() {
        val meeting = meeting()
        val item = addItem(meeting, TITLE)
        val sub = subItem(meeting, item, "Collateral valuation")
        val other = addItem(meeting, "Liquidity report")
        publish(meeting)
        events.clear()

        val leaving = declare(d1, meeting, item)
        val asking = declare(d2, meeting, sub, DeclarationIntent.ASK_CHAIR)
        val taking = rule(chair, meeting, declare(observer, meeting, other, DeclarationIntent.ASK_CHAIR), RulingOutcome.TAKE_PART)

        inTenant {
            assertThat(recusals.isRecused(d1.id, item)).isTrue()
            assertThat(recusals.isRecused(d1.id, sub)).isTrue()
            assertThat(recusals.isRecused(d2.id, item)).isFalse()
            assertThat(recusals.recusedFrom(sub)).containsExactlyInAnyOrder(d1.id, d2.id)
            assertThat(recusals.recusedFrom(other)).isEmpty()
            assertThat(recusals.ofMeeting(meeting.id)).isEqualTo(mapOf(item to setOf(d1.id), sub to setOf(d1.id, d2.id)))
            val all = recusals.declarationsFor(meeting.id)
            assertThat(all.map { it.id }).containsExactly(leaving.id, asking.id, taking.id)
            val ruled = all.single { it.id == taking.id }
            assertThat(ruled.state to ruled.ruling?.outcome).isEqualTo(DeclarationState.TAKING_PART to RulingOutcome.TAKE_PART)
            assertThat(ruled.ruling?.by).isEqualTo(chair.id)
            assertThat(all.single { it.id == leaving.id }.nature).isEqualTo(NATURE)
        }

        val told = events.stream(DeclarationChanged::class.java).toList()
        assertThat(told.map { Triple(it.itemId, it.userId, it.recused) }).containsExactly(
            Triple(item, d1.id, true),
            Triple(sub, d2.id, true),
            Triple(other, observer.id, true),
            Triple(other, observer.id, false),
        )
        assertThat(told).allMatch { it.tenantId == tenant.id.value && it.meetingId == meeting.id }

        // A withdrawal ends the recusal and is told once more.
        events.clear()
        asUser(d1.account) { declarations.withdraw(meeting.id, leaving.id, leaving.version, "Entered in error") }
        inTenant { assertThat(recusals.recusedFrom(item)).isEmpty() }
        assertThat(events.stream(DeclarationChanged::class.java).toList().map { it.userId to it.recused })
            .containsExactly(d1.id to false)
    }

    private fun inTenant(block: () -> Unit) = transactions.inTenant(tenant.id) { block() }
}
