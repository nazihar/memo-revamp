package com.memo.demo

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.MfaSeeding
import com.memo.identity.UserAccounts
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The shared demo tenant, defined as code (AGENTS.md §4 "Test data and fixtures"). It grows with every phase: org tree and
 * users (1b), memo types and workflows (2/3), forums (4). Fixed ids keep seeding idempotent.
 */
object DemoTenant {
    val ID = TenantId(UUID.fromString("01900000-0000-7000-8000-000000000001"))
    const val SLUG = "demo"
    const val NAME = "Demo Organization"

    val SETTINGS =
        linkedMapOf(
            "default.locale" to "en",
            "default.timezone" to "UTC",
            // Placeholder for the org tree until Phase 1b models units and positions.
            "org.placeholder" to "Head Office",
        )

    /** Demo accounts. Their password comes from `MEMO_DEMO_PASSWORD` (generated into the git-ignored `.env`), never code. */
    val USERS =
        listOf(
            DemoUser("admin@demo.example", "Demo Admin", tenantAdmin = true),
            DemoUser("user@demo.example", "Demo User", tenantAdmin = false),
        )
}

data class DemoUser(
    val email: String,
    val displayName: String,
    val tenantAdmin: Boolean,
)

/** Creates or refreshes the demo tenant. Idempotent. Refuses to run under the `prod` profile. */
@Component
class DemoTenantSeeder(
    private val registry: TenantRegistry,
    private val settings: TenantSettings,
    private val transactions: TenantTransactions,
    private val environment: Environment,
    private val accounts: UserAccounts,
    @param:Value("\${memo.demo.password:}") private val demoPassword: String,
    private val mfa: MfaSeeding,
    @param:Value("\${memo.demo.totp-secret:}") private val demoTotpSecret: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun seed() {
        check(!environment.acceptsProfiles(Profiles.of("prod"))) { "The demo tenant must never be seeded in production" }
        ActorContext.asSystem("demo-seeder") {
            registry.ensure(DemoTenant.ID, DemoTenant.SLUG, DemoTenant.NAME)
            transactions.inTenant(DemoTenant.ID) {
                DemoTenant.SETTINGS.forEach { (key, value) ->
                    if (settings.get(key)?.value != value) settings.put(key, value)
                }
                DemoTenant.USERS.forEach { seedUser(it) }
            }
        }
        log.info("Demo tenant '{}' seeded", DemoTenant.SLUG)
    }

    private fun seedUser(user: DemoUser) {
        val account = accounts.findByEmail(user.email) ?: accounts.create(user.email, user.displayName, user.tenantAdmin)
        if (demoPassword.isBlank()) {
            // Names the variable, never its value.
            // nosemgrep: semgrep.memo-no-secrets-in-logs
            log.warn("MEMO_DEMO_PASSWORD is not set: demo user {} cannot sign in", user.email)
        } else if (!accounts.passwordMatches(account.id, demoPassword)) {
            accounts.setPassword(account.id, demoPassword)
        }
        // Base32, so Playwright and people can compute codes for the demo users (MFA is required by default).
        if (demoTotpSecret.isNotBlank()) mfa.ensureTotp(account.id, demoTotpSecret)
    }
}

/** Seeds on startup when `memo.demo.seed=true` (local dev via `pnpm dev`, the dev environment). */
@Component
@ConditionalOnProperty("memo.demo.seed", havingValue = "true")
class DemoTenantSeedOnStartup(
    private val seeder: DemoTenantSeeder,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) = seeder.seed()
}
