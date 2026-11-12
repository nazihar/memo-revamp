package com.memo.demo

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccounts
import com.memo.memo.MemoSeeding
import com.memo.workflow.WorkflowSeeding
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The demo tenant's workflow (test plan E03 T11): "Credit facility", a definition that names every step: the head of
 * credit reviews, then the classification decides between the director alone and a credit committee (director and head
 * of finance, all of them), and the author is told. Written by the demo admin, approved by the org admin (maker-checker),
 * and chosen by the memo type of the same name, whose memos have no route. Runs once: nothing happens if the
 * definition exists.
 */
@Component
class DemoWorkflowSeeder(
    private val accounts: UserAccounts,
    private val workflows: WorkflowSeeding,
    private val memos: MemoSeeding,
    private val transactions: TenantTransactions,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun seed() {
        val ids = ActorContext.asSystem("demo-workflows") { transactions.inTenant(DemoTenant.ID) { people() } }
        val author = ids.getValue(ADMIN)
        if (asUser(author) { workflows.exists(KEY) }) return
        asUser(author) { workflows.submit(KEY, NAME, definition(ids)) }
        asUser(ids.getValue(REVIEWER)) { workflows.approve(KEY) }
        asUser(author) { memos.fixedType(NAME, KEY) }
        log.info("Demo workflow seeded")
    }

    private fun definition(ids: Map<String, UUID>): String {
        fun person(email: String) = """{"kind":"person","userId":"${ids.getValue(email)}"}"""
        return """
            {"schemaVersion":2,"attributes":[{"name":"classificationRank","type":"int"}],"start":"review","steps":[
              {"id":"review","type":"review","name":"Credit review","assignee":${person(HEAD_CREDIT)},
               "actions":["RECOMMEND","RETURN","FORWARD","ASK","REFER"],"next":"size"},
              {"id":"size","type":"branch","name":"By classification",
               "branches":[{"when":"item.classificationRank >= 2","next":"committee"}],"otherwise":"director"},
              {"id":"committee","type":"parallel","name":"Credit committee","mode":"all",
               "assignees":[${person(DIRECTOR)},${person(HEAD_FINANCE)}],"next":"tell"},
              {"id":"director","type":"approve","name":"Director","assignee":${person(DIRECTOR)},
               "actions":["APPROVE","RETURN","REJECT","SEND_BACK","DELEGATE"],"next":"tell"},
              {"id":"tell","type":"notify","name":"Tell the author","recipients":[{"kind":"initiator"}],
               "message":"creditDecided","next":"end"}
            ]}
            """.trimIndent()
    }

    private fun people(): Map<String, UUID> =
        listOf(ADMIN, REVIEWER, HEAD_CREDIT, HEAD_FINANCE, DIRECTOR).associateWith {
            checkNotNull(accounts.findByEmail(it)) { "demo user $it missing" }.id.value
        }

    private fun <T> asUser(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { TenantContext.call(DemoTenant.ID, block) }

    companion object {
        const val KEY = "credit-facility"
        const val NAME = "Credit facility"
        const val ADMIN = "admin@demo.example"
        const val REVIEWER = "orgadmin@demo.example"
        const val HEAD_CREDIT = "head.credit@demo.example"
        const val HEAD_FINANCE = "head.finance@demo.example"
        const val DIRECTOR = "director@demo.example"
    }
}

/** After the demo tenant and its users (which the definition names); it needs nothing else, so it runs at start-up. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty("memo.demo.seed", havingValue = "true")
class DemoWorkflowSeedOnStartup(
    private val seeder: DemoWorkflowSeeder,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    /** A demo that cannot be seeded never stops the app from starting. */
    @Suppress("TooGenericExceptionCaught")
    override fun run(args: ApplicationArguments) {
        try {
            seeder.seed()
        } catch (e: RuntimeException) {
            log.warn("Demo workflow not seeded", e)
        }
    }
}
