package com.memo.demo

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccounts
import com.memo.memo.MemoSeeding
import com.memo.workflow.DoaSeedGroup
import com.memo.workflow.DoaSeedRule
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
 * credit reviews within a working day, then the classification decides between the director alone and a credit committee (director and head
 * of finance, all of them), and the author is told. Written by the demo admin, approved by the org admin (maker-checker),
 * and chosen by the memo type of the same name, whose memos have no route. Runs once: nothing happens if the
 * definition exists, except that a seeded version 1 from before E05 gets the deadlines (SLAs) as a version 2, the same
 * way (E05 T8).
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
        publish(ids, KEY, NAME, definition(ids))
        asUser(ids.getValue(ADMIN)) { memos.fixedType(NAME, KEY) }
        seedAuthority(ids)
        // Phase 3 E01 T8: representative bank memo types with fields and numbering (no pilot bank yet, Phase 3 Q2); credit
        // proposals run under the authority, so their amount picks the DoA band.
        val types =
            TenantContext.call(DemoTenant.ID) {
                memos.representativeTypes(ids.getValue(ADMIN), ids.getValue(REVIEWER), mapOf(CREDIT_PROPOSAL_CODE to AUTHORITY_KEY))
            }
        if (types.published > 0) log.info("Demo memo types seeded: {}", types.published)
        seedDoa(ids, types.ids[CREDIT_PROPOSAL_CODE])
    }

    /** [body] as [key], written by the demo admin and approved by the org admin; or as version 2 if version 1 lacks SLAs. */
    private fun publish(
        ids: Map<String, UUID>,
        key: String,
        name: String,
        body: String,
    ) {
        val author = ids.getValue(ADMIN)
        val reviewer = ids.getValue(REVIEWER)
        if (!asUser(author) { workflows.exists(key) }) {
            asUser(author) { workflows.submit(key, name, body) }
            asUser(reviewer) { workflows.approve(key) }
            log.info("Demo workflow {} seeded", key)
        } else {
            asUser(author) { workflows.revise(key, body) { !it.contains("\"sla\"") } }?.let { number ->
                asUser(reviewer) { workflows.approve(key, number) }
                log.info("Demo workflow {} given its deadlines", key)
            }
        }
    }

    /**
     * E04 T10: "Credit under authority": the credit desk (a queue of the head of credit and the director; one claims,
     * the first decision completes it), then the delegation of authority, and the author is told.
     * E05 T8: the desk has four working hours (a reminder an hour before), the authority two working days (a reminder a
     * day after the due time).
     */
    private fun seedAuthority(ids: Map<String, UUID>) {
        val author = ids.getValue(ADMIN)
        asUser(author) { workflows.queue(DESK, "Credit desk", listOf(ids.getValue(HEAD_CREDIT), ids.getValue(DIRECTOR))) }
        publish(ids, AUTHORITY_KEY, AUTHORITY_NAME, authority())
        asUser(author) { memos.fixedType(AUTHORITY_NAME, AUTHORITY_KEY) }
    }

    /**
     * The delegation of authority, maker-checked like the definitions. E04 T10: every memo asks the managing director,
     * then the chief financial officer (by their positions). Phase 3 E01 T8 puts amount bands for credit proposals
     * ahead of it: BDT 5 million or more asks both, less asks the chief financial officer only. A tenant seeded before
     * the bands gets them in a new version taking effect today, unless a version is being written or reviewed.
     */
    private fun seedDoa(
        ids: Map<String, UUID>,
        creditProposal: UUID?,
    ) {
        val author = ids.getValue(ADMIN)
        val director = DoaSeedGroup(1, listOf(DemoOrg.unitByCode("CEO").headPosition))
        val finance = DoaSeedGroup(1, listOf(DemoOrg.unitByCode("FIN").headPosition))
        val bands =
            creditProposal
                ?.let { credit ->
                    listOf(
                        DoaSeedRule(LARGE_CREDIT, "memo", listOf(director, finance), credit, "item.amount >= $CREDIT_BAND"),
                        DoaSeedRule(SMALL_CREDIT, "memo", listOf(finance), credit),
                    )
                }.orEmpty()
        val inForce = asUser(author) { workflows.doaRulesInForce() }
        val rules =
            when {
                asUser(author) { workflows.doaOpen() } -> {
                    emptyList()
                }

                inForce == null && !asUser(author) { workflows.doaSeeded() } -> {
                    bands + DoaSeedRule(EVERY_MEMO, "memo", listOf(director, finance))
                }

                inForce != null && LARGE_CREDIT !in inForce -> {
                    bands
                }

                else -> {
                    emptyList()
                }
            }
        if (rules.isEmpty()) return
        val number = asUser(author) { workflows.submitDoa(rules) }
        asUser(ids.getValue(REVIEWER)) { workflows.approveDoa(number) }
        log.info("Demo delegation of authority seeded: version {}", number)
    }

    private fun authority(): String =
        """
        {"schemaVersion":2,"start":"desk","steps":[
          {"id":"desk","type":"review","name":"Credit desk","assignee":{"kind":"queue","queue":"$DESK"},
           "actions":["RECOMMEND","RETURN"],"sla":{"due":"PT4H","remind":{"before":"PT1H"}},"next":"authority"},
          {"id":"authority","type":"doa","name":"Delegation of authority",
           "sla":{"due":"P2D","remind":{"after":["P1D"]}},"next":"tell"},
          {"id":"tell","type":"notify","name":"Tell the author","recipients":[{"kind":"initiator"}],
           "message":"creditDecided","next":"end"}
        ]}
        """.trimIndent()

    private fun definition(ids: Map<String, UUID>): String {
        fun person(email: String) = """{"kind":"person","userId":"${ids.getValue(email)}"}"""
        return """
            {"schemaVersion":2,"attributes":[{"name":"classificationRank","type":"int"}],"start":"review","steps":[
              {"id":"review","type":"review","name":"Credit review","assignee":${person(HEAD_CREDIT)},
               "actions":["RECOMMEND","RETURN","FORWARD","ASK","REFER"],
               "sla":{"due":"P1D","remind":{"before":"PT2H"}},"next":"size"},
              {"id":"size","type":"branch","name":"By classification",
               "branches":[{"when":"item.classificationRank >= 2","next":"committee"}],"otherwise":"director"},
              {"id":"committee","type":"parallel","name":"Credit committee","mode":"all",
               "assignees":[${person(DIRECTOR)},${person(HEAD_FINANCE)}],
               "sla":{"due":"P2D","remind":{"after":["P1D"]}},"next":"tell"},
              {"id":"director","type":"approve","name":"Director","assignee":${person(DIRECTOR)},
               "actions":["APPROVE","RETURN","REJECT","SEND_BACK","DELEGATE"],
               "sla":{"due":"P2D","remind":{"before":"PT4H"},"escalate":{"after":"P1D","action":"notify"}},"next":"tell"},
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
        const val AUTHORITY_KEY = "credit-authority"
        const val AUTHORITY_NAME = "Credit under authority"
        const val CREDIT_PROPOSAL = "Credit proposal"
        const val CREDIT_PROPOSAL_CODE = "CP"
        const val LARGE_CREDIT = "Credit proposals of BDT 5 million or more"
        const val SMALL_CREDIT = "Credit proposals under BDT 5 million"
        const val CREDIT_BAND = "5000000.0"
        const val EVERY_MEMO = "Credit memos"
        const val DESK = "credit-desk"
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
