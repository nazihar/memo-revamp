package com.memo.demo

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccounts
import com.memo.imports.ImportSeeding
import com.memo.imports.SeededImport
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Phase 3 E06 in the demo tenant (T10): the synthetic legacy sample ([SyntheticLegacy]) imported as a batch by the demo
 * admin, so the demo shows imported memos, their originals and a reconciliation report. Seeded once (a batch of its name
 * in any state is left alone). Runs after the demo memos (it links the demo leaver) and after the access seeder (which
 * gives the admin `memo.import` and the clearance the sample needs). Needs the files service, like the demo memos; the
 * `.doc` and `.xls` originals also need the Tika server, without which their row is rejected `FILE_NOT_RECEIVED`.
 *
 * The seeder drives the batch through the import services up to the start (dry run in this thread, mappings confirmed,
 * the start without a step-up, since there is no session); the `imports-run`, `imports-verify` and `imports-report` jobs
 * then import it, verify it and store its report, as for any batch. Seeding the memos directly would skip what the demo
 * is for (the batch, its problems and its report); waiting for every job here would hold the start-up thread for minutes.
 */
@Component
class DemoLegacySeeder(
    private val accounts: UserAccounts,
    private val transactions: TenantTransactions,
    private val imports: ImportSeeding,
    @param:Value("\${memo.demo.password:}") private val demoPassword: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun seed(): SeededImport? {
        val admin =
            if (demoPassword.isBlank()) {
                null
            } else {
                ActorContext.asSystem("demo-legacy") {
                    transactions.inTenant(DemoTenant.ID) { accounts.findByEmail(ADMIN)?.id?.value }
                }
            }
        return admin?.let(::seedAs)
    }

    private fun seedAs(admin: UUID): SeededImport {
        val seeded =
            ActorContext.call(ActorScope(Actor.User(admin), null)) {
                TenantContext.call(DemoTenant.ID) { imports.seedOnce(SyntheticLegacy.sample()) }
            }
        if (seeded.created) {
            log.info(
                "Demo legacy import seeded: batch {} {} ({} originals not taken)",
                seeded.batchId,
                seeded.state,
                seeded.originalsNotReceived,
            )
        }
        return seeded
    }

    companion object {
        const val ADMIN = "admin@demo.example"
    }
}
