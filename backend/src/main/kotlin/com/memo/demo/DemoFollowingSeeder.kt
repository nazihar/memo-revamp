package com.memo.demo

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccounts
import com.memo.memo.MemoWatchSeeding
import com.memo.search.SearchSeeding
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Phase 3 E05 in the demo tenant: Demo User follows the memos they started (seeded before following existed, so
 * without automatic watches) and has a saved search, so the Watching tab and the Saved searches page show something.
 * The heads' team views need nothing more: the demo heads hold head seats (DemoOrg). Fills gaps only; runs after the
 * demo memos.
 */
@Component
class DemoFollowingSeeder(
    private val watches: MemoWatchSeeding,
    private val searches: SearchSeeding,
    private val accounts: UserAccounts,
    private val transactions: TenantTransactions,
    @param:Value("\${memo.demo.password:}") private val demoPassword: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun seed() {
        if (demoPassword.isBlank()) return
        val author =
            ActorContext.asSystem("demo-following") {
                transactions.inTenant(DemoTenant.ID) { accounts.findByEmail(AUTHOR)?.id?.value }
            } ?: return
        val followed = asUser(author) { watches.followStarted(author) }
        val saved = asUser(author) { searches.saveOnce(SAVED_NAME, SAVED_WORDS) }
        if (followed > 0 || saved) log.info("Demo following seeded: {} memos followed, saved search added: {}", followed, saved)
    }

    private fun <T> asUser(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { TenantContext.call(DemoTenant.ID, block) }

    private companion object {
        const val AUTHOR = "user@demo.example"
        const val SAVED_NAME = "Lease and rent"
        const val SAVED_WORDS = "lease"
    }
}
