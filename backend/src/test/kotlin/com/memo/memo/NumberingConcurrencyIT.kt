package com.memo.memo

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.identity.UserAccount
import com.memo.memo.internal.MemoFields
import com.memo.memo.internal.MemoNumbers
import com.memo.memo.internal.MemoStore
import com.memo.memo.internal.MemoTypeFields
import com.memo.memo.internal.MemoTypeLifecycle
import com.memo.memo.internal.MemoTypeSettings
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.Memos
import com.memo.memo.internal.NumberMoment
import com.memo.memo.internal.NumberPart
import com.memo.memo.internal.NumberPartKind
import com.memo.memo.internal.NumberReset
import com.memo.memo.internal.Numbering
import com.memo.memo.internal.RoutePolicy
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Gapless numbering under concurrency (test plan E01, T4; TESTING_STRATEGY §12; ADR-0044): many transactions numbering
 * memos of one series at once get consecutive numbers with no gap and no repeat, and a transaction that rolls back after
 * taking a number gives it back.
 */
class NumberingConcurrencyIT : MemoIntegrationTest() {
    @Autowired lateinit var types: MemoTypes

    @Autowired lateinit var lifecycle: MemoTypeLifecycle

    @Autowired lateinit var memos: Memos

    @Autowired lateinit var store: MemoStore

    @Autowired lateinit var numbers: MemoNumbers

    @Test
    fun `parallel numbering gives consecutive numbers, and a rollback leaves no gap`() {
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        val type = aType(checker)
        val alice = person()
        val drafts = (1..PARALLEL + 2).map { asUser(alice.account) { memos.create(MemoFields("Memo $it", type, null)) }.id }

        val pool = Executors.newFixedThreadPool(THREADS)
        val go = CountDownLatch(1)
        val results =
            drafts.take(PARALLEL).map { id ->
                pool.submit<String> {
                    go.await()
                    number(alice.account, id)
                }
            }
        go.countDown()
        val numbersTaken = results.map { it.get(1, TimeUnit.MINUTES) }
        pool.shutdown()
        assertThat(numbersTaken.map { it.removePrefix("C-").toInt() }.sorted()).isEqualTo((1..PARALLEL).toList())

        // A transaction that takes a number and then fails gives the number back.
        runCatching { number(alice.account, drafts[PARALLEL]) { error("the business change failed") } }
        assertThat(number(alice.account, drafts[PARALLEL + 1])).isEqualTo("C-%04d".format(PARALLEL + 1))
        assertThat(asUser(alice.account) { store.find(drafts[PARALLEL]) }?.reference).isNull()
    }

    private fun number(
        user: UserAccount,
        id: UUID,
        after: () -> Unit = {},
    ): String =
        ActorContext.call(ActorScope(Actor.User(user.id.value), null)) {
            TenantContext.call(tenant.id) {
                transactions.inTenant(tenant.id) {
                    val memo = checkNotNull(store.find(id))
                    val version = checkNotNull(types.published(memo.typeId))
                    checkNotNull(numbers.assign(memo, version, NumberMoment.ON_SUBMIT)).also { after() }
                }
            }
        }

    private fun aType(checker: UserAccount): UUID {
        val id = asUser(admin) { types.create(MemoTypeFields("Circular", null, stepUpOnApprove = false, active = true)) }.id
        val settings =
            MemoTypeSettings(
                "Circular",
                "C",
                null,
                "sequential-approval",
                RoutePolicy.INITIATOR_DEFINED,
                stepUpOnApprove = false,
                numbering =
                    Numbering(
                        series = "circulars",
                        parts = listOf(NumberPart(NumberPartKind.TEXT, text = "C-"), NumberPart(NumberPartKind.SEQUENCE, digits = 4)),
                        reset = NumberReset.NEVER,
                    ),
            )
        val draft = asUser(admin) { lifecycle.newDraft(id, settings) }
        val submitted = asUser(admin) { lifecycle.submit(id, draft.number, draft.revision) }
        asUser(checker) { lifecycle.approve(id, submitted.number, null) }
        return id
    }

    private companion object {
        const val PARALLEL = 40
        const val THREADS = 16
    }
}
