package com.memo.meeting

import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.meeting.internal.DeclareInput
import com.memo.meeting.internal.OneOpenException
import com.memo.meeting.internal.RulingInput
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.Person
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Declarations at once (test plan E04, T1; threat model T2): two declarations by one person on one item leave one
 * (`ONE_OPEN`, decided by the unique index); two rulings from one version keep the first and the second gets 412.
 */
class DeclarationConcurrencyIT : DeclarationFixture() {
    @Test
    fun `two declarations by one person on one item at once leave one, and the other is ONE_OPEN`() {
        val meeting = meeting()
        val item = addItem(meeting, TITLE)
        publish(meeting)

        repeat(ROUNDS) { round ->
            val person = person()
            asUser(secretary.account) { guests.add(meeting.id, person.id, null) }
            val outcomes =
                parallel(
                    { call(person) { declarations.declare(meeting.id, item, DeclareInput("Interest $round a")) } },
                    { call(person) { declarations.declare(meeting.id, item, DeclareInput("Interest $round b")) } },
                )
            assertThat(outcomes.count { it == null }).isEqualTo(1)
            assertThat(outcomes.filterNotNull()).allSatisfy { assertThat(it).isInstanceOf(OneOpenException::class.java) }
            AppRoleJdbc.inTransaction(tenant.id) { c ->
                assertThat(c.long("select count(*) from meeting_declaration where user_id = ?", person.id)).isEqualTo(1)
                assertThat(
                    c.long(
                        "select count(*) from meeting_declaration_event e join meeting_declaration d on d.id = e.declaration_id " +
                            "where d.user_id = ?",
                        person.id,
                    ),
                ).isEqualTo(1)
            }
        }
    }

    @Test
    fun `two rulings from one version keep the first, and the second gets 412`() {
        val meeting = meeting()
        val item = addItem(meeting, TITLE)
        publish(meeting)
        val asked = declare(director, meeting, item, intent = DeclarationIntent.ASK_CHAIR)

        val outcomes =
            parallel(
                { call(chair) { rulings.rule(meeting.id, asked.id, asked.version, RulingInput(RulingOutcome.TAKE_PART)) } },
                { call(chair) { rulings.rule(meeting.id, asked.id, asked.version, RulingInput(RulingOutcome.LEAVE)) } },
            )

        assertThat(outcomes.count { it == null }).isEqualTo(1)
        assertThat(outcomes.filterNotNull().single()).isInstanceOf(ConcurrentModificationException::class.java)
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(c.long("select version from meeting_declaration where id = ?", asked.id)).isEqualTo(1)
            assertThat(c.long("select count(*) from meeting_declaration_event where declaration_id = ? and kind = 'RULED'", asked.id))
                .isEqualTo(1)
        }
    }

    /** Runs [block] as [person] in its own tenant transaction. */
    private fun <T> call(
        person: Person,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(person.id), null)) {
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
        const val ROUNDS = 4
    }
}
