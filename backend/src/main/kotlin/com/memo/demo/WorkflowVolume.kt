package com.memo.demo

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.ratelimit.RateLimitedException
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.SecuritySettings
import com.memo.identity.UserAccounts
import com.memo.memo.MemoSeeding
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.workflow.WorkflowSeeding
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Synthetic volume V2 (phase overview E05, TESTING_STRATEGY §7): a tenant `volume` with [APPROVERS] approvers and
 * [INITIATORS] initiators, a routed memo type whose definition has an SLA (due, reminders, escalation), and [instances]
 * memos in review, each with its timers, spread evenly over the approvers' inboxes. Built through the real services, so
 * every row is what production writes. Deterministic names, synthetic data only; refuses under the `prod` profile and
 * does nothing if the tenant exists.
 */
@Component
class WorkflowVolume(
    private val registry: TenantRegistry,
    private val settings: TenantSettings,
    private val accounts: UserAccounts,
    private val workflows: WorkflowSeeding,
    private val memos: MemoSeeding,
    private val transactions: TenantTransactions,
    private val environment: Environment,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun generate(
        instances: Int,
        password: String,
    ): TenantId? {
        check(!environment.acceptsProfiles(Profiles.of("prod"))) { "Synthetic volumes must never be generated in production" }
        require(instances in 1..MAX_INSTANCES) { "instances must be 1..$MAX_INSTANCES" }
        // Start-up binds no actor, and creating a tenant is audited: the generator acts as the system until its users exist.
        val tenant =
            ActorContext.asSystem("volume-v2") {
                if (registry.findBySlug(SLUG) != null) null else registry.create(slug = SLUG, name = "Synthetic volume V2").id
            } ?: return null.also { log.warn("Volume V2: the tenant exists, nothing generated (a failed run leaves it part-built)") }
        val people =
            TenantContext.call(tenant) {
                ActorContext.asSystem("volume-v2") {
                    transactions.inTenant(tenant) {
                        // Password sign-in only, so the load test needs no authenticator.
                        settings.put(SecuritySettings.MFA_REQUIRED, SecuritySettings.MFA_ADMINS_ONLY)
                        val admin = user("admin", password, tenantAdmin = true)
                        val checker = user("checker", password, tenantAdmin = true)
                        Users(
                            admin,
                            checker,
                            (1..APPROVERS).map { user("approver$it", password) },
                            (1..INITIATORS).map { user("initiator$it", password) },
                        )
                    }
                }
            }
        asUser(tenant, people.admin) { workflows.submit(KEY, "Volume review", DEFINITION) }
        asUser(tenant, people.checker) { workflows.approve(KEY) }
        val type = asUser(tenant, people.admin) { memos.fixedType("Volume review", KEY, routed = true) }
        val document = DemoDocx.of("Volume memo", listOf("Synthetic memo for the inbox load test."))
        for (n in 0 until instances) {
            val initiator = people.initiators[n % INITIATORS]
            asUser(tenant, initiator) {
                val memo = memos.draft("Volume memo ${n + 1}", typeId = type)
                withinRateLimit { memos.document(memo, "memo.docx", document) }
                memos.route(memo, listOf(people.approvers[n % APPROVERS]))
                memos.submit(memo)
            }
            if ((n + 1) % PROGRESS == 0) log.info("Volume V2: {} of {} memos in review", n + 1, instances)
        }
        log.info("Volume V2 ready: {} memos in review", instances)
        return tenant
    }

    private fun user(
        name: String,
        password: String,
        tenantAdmin: Boolean = false,
    ): UUID {
        val account = accounts.create("$name@volume.example", name.replaceFirstChar(Char::uppercase), tenantAdmin)
        accounts.setPassword(account.id, password)
        return account.id.value
    }

    /**
     * Uploads are limited per person (UPLOAD_PER_USER), and the generator outpaces any person: it waits as a client
     * would after a 429, then tries again. A refused upload writes nothing.
     */
    private fun <T> withinRateLimit(block: () -> T): T {
        var result = runCatching(block)
        while (true) {
            val limited = result.exceptionOrNull() as? RateLimitedException ?: return result.getOrThrow()
            Thread.sleep(limited.retryAfter.toMillis().coerceAtLeast(MIN_WAIT_MS))
            result = runCatching(block)
        }
    }

    private fun <T> asUser(
        tenant: TenantId,
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { TenantContext.call(tenant, block) }

    private data class Users(
        val admin: UUID,
        val checker: UUID,
        val approvers: List<UUID>,
        val initiators: List<UUID>,
    )

    companion object {
        const val SLUG = "volume"
        const val KEY = "volume-review"
        const val APPROVERS = 50
        const val INITIATORS = 50
        const val MAX_INSTANCES = 20_000
        private const val PROGRESS = 500
        private const val MIN_WAIT_MS = 100L

        /** One route step with an SLA: due in two working days, reminders before and after, escalation to the next level. */
        private val DEFINITION =
            """
            {"schemaVersion":2,"start":"route","steps":[
              {"id":"route","type":"route","name":"Approval",
               "sla":{"due":"P2D","remind":{"before":"PT4H","after":["P1D"]},"escalate":{"after":"P2D","action":"nextLevel"}},
               "next":"end"}]}
            """.trimIndent()
    }
}

/** Generates volume V2 at start-up when `memo.volume.v2` is on (load environments only; never under `prod`). */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty("memo.volume.v2", havingValue = "true")
class WorkflowVolumeOnStartup(
    private val volume: WorkflowVolume,
    @param:Value("\${memo.volume.instances:10000}") private val instances: Int,
    @param:Value("\${memo.demo.password:}") private val password: String,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        check(password.isNotBlank()) { "Volume V2 needs MEMO_DEMO_PASSWORD for its synthetic users" }
        volume.generate(instances, password)
    }
}
