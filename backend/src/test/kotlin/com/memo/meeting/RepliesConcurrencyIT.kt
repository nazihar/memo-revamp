package com.memo.meeting

import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.identity.UserAccount
import com.memo.meeting.internal.rsvp.Replies
import com.memo.meeting.internal.rsvp.RsvpAnswer
import com.memo.meeting.internal.rsvp.RsvpHow
import com.memo.meeting.internal.rsvp.RsvpInput
import com.memo.system.Idempotency
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Replies at once (test plan E05 T3 `RepliesConcurrencyIT`): a member and a secretary changing one answer from the same
 * version keep the first and the second gets 412; the same idempotency key twice writes one answer and one history row.
 */
class RepliesConcurrencyIT : InvitationTestSupport() {
    @Autowired lateinit var replies: Replies

    @Autowired lateinit var idempotency: Idempotency

    override fun label() = "rsvpconc"

    @Test
    fun `a member and a secretary answering from one version keep one, and the other gets 412`() {
        val meeting = scheduled()
        repeat(ROUNDS) { round ->
            val version = if (round == 0) null else asUser(director) { replies.mine(meeting.id) }.version
            val outcomes =
                parallel(
                    { call(director) { replies.answer(meeting.id, version, RsvpInput(RsvpAnswer.IN_PERSON)) } },
                    {
                        call(secretary) {
                            replies.record(meeting.id, director.id.value, version, RsvpInput(RsvpAnswer.APOLOGIES, how = RsvpHow.PHONE))
                        }
                    },
                )
            assertThat(outcomes.count { it == null }).describedAs("round $round").isEqualTo(1)
            if (version != null) {
                assertThat(outcomes.filterNotNull().single()).isInstanceOf(ConcurrentModificationException::class.java)
            }
            AppRoleJdbc.inTransaction(tenant.id) { c ->
                assertThat(c.long("select version from meeting_rsvp where meeting_id = ?", meeting.id)).isEqualTo(round.toLong())
                assertThat(c.long("select count(*) from meeting_rsvp_event where meeting_id = ?", meeting.id)).isEqualTo(round + 1L)
            }
        }
    }

    @Test
    fun `the same idempotency key twice is one answer`() {
        val meeting = scheduled()
        val key = UUID.randomUUID().toString()
        repeat(2) {
            asUser(director2) {
                idempotency.once("meeting.rsvp.answer", key, "same") {
                    replies.answer(meeting.id, null, RsvpInput(RsvpAnswer.REMOTE))
                    "ok"
                }
            }
        }
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(c.long("select count(*) from meeting_rsvp_event where meeting_id = ?", meeting.id)).isEqualTo(1)
        }
    }

    private fun <T> call(
        account: UserAccount,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(account.id.value), null)) {
            TenantContext.call(tenant.id) { transactions.inTenant(tenant.id, block) }
        }

    /** Runs the blocks at once; each outcome is null on success or the exception it threw. */
    private fun parallel(vararg blocks: () -> Any?): List<Throwable?> {
        val pool = Executors.newFixedThreadPool(blocks.size)
        val go = CountDownLatch(1)
        try {
            val futures =
                blocks.map { block ->
                    pool.submit<Throwable?> {
                        go.await()
                        runCatching { block() }.exceptionOrNull()
                    }
                }
            go.countDown()
            return futures.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val WAIT_SECONDS = 60L
        const val ROUNDS = 3
    }
}
