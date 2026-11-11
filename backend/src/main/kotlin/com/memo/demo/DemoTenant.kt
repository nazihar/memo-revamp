package com.memo.demo

import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.BuiltInRole
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.MfaSeeding
import com.memo.identity.UserAccounts
import com.memo.identity.operator.OperatorMfa
import com.memo.org.OrgSeeding
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
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
 * The shared demo tenant, defined as code (AGENTS.md §4 "Test data and fixtures"). It grows with every phase: org tree and
 * users (1b), memo types and workflows (2/3), forums (4). Fixed ids keep seeding idempotent.
 */
object DemoTenant {
    val ID = TenantId(UUID.fromString("01900000-0000-7000-8000-000000000001"))
    const val SLUG = "demo"
    const val NAME = "Demo Organization"

    /** The demo platform operator (ADR-0031), on the operator console's host; same password and TOTP secret. */
    const val OPERATOR_EMAIL = "operator@demo.example"
    const val OPERATOR_NAME = "Demo Operator"

    val SETTINGS =
        linkedMapOf(
            "locale.default" to "en",
            "default.timezone" to "UTC",
        )

    /** Demo accounts. Their password comes from `MEMO_DEMO_PASSWORD` (generated into the git-ignored `.env`), never code. */
    val USERS =
        listOf(
            DemoUser("admin@demo.example", "Demo Admin", BuiltInRole.TENANT_ADMIN),
            DemoUser("orgadmin@demo.example", "Demo Org Admin", BuiltInRole.ORG_ADMIN),
            DemoUser("branchadmin@demo.example", "Demo Branch Admin", BuiltInRole.ORG_ADMIN, scopeUnit = "REG-N"),
            DemoUser("auditor@demo.example", "Demo Auditor", BuiltInRole.AUDITOR),
            DemoUser("user@demo.example", "Demo User", null),
            // Approvers of the demo memos (design E01 §15): two department heads and a director.
            DemoUser("head.credit@demo.example", "Demo Head of Credit", null),
            DemoUser("head.finance@demo.example", "Demo Head of Finance", null),
            DemoUser("director@demo.example", "Demo Director", null),
        )
}

/** A demo account with its role, tenant-wide or for the subtree of the unit with code [scopeUnit]. */
data class DemoUser(
    val email: String,
    val displayName: String,
    val role: BuiltInRole?,
    val scopeUnit: String? = null,
) {
    val tenantAdmin: Boolean get() = role == BuiltInRole.TENANT_ADMIN
}

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
    private val access: AccessAdministration,
    private val org: OrgSeeding,
    private val operators: OperatorMfa,
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
                val demoAccounts = DemoTenant.USERS.associate { it.email to seedUser(it) }
                seedOrg(demoAccounts)
                DemoTenant.USERS.filter { it.role != null && !it.tenantAdmin }.forEach { user ->
                    access.grantBuiltIn(demoAccounts.getValue(user.email), user.role!!, user.scopeUnit?.let { DemoOrg.unitId(it) })
                }
            }
        }
        if (demoPassword.isNotBlank() && demoTotpSecret.isNotBlank()) {
            ActorContext.asSystem("demo-seeder") {
                operators.seed(DemoTenant.OPERATOR_EMAIL, DemoTenant.OPERATOR_NAME, demoPassword, demoTotpSecret)
            }
        }
        log.info("Demo tenant '{}' seeded", DemoTenant.SLUG)
    }

    /** Units, positions, reporting lines, and people in most seats: demo accounts in theirs, staff (INVITED) in the rest. */
    private fun seedOrg(demoAccounts: Map<String, UUID>) {
        org.legalEntity(DemoOrg.ENTITY, DemoOrg.ENTITY_NAME, DemoOrg.START)
        DemoOrg.UNITS.forEach { org.unit(it.id, DemoOrg.ENTITY, it.parent?.let(DemoOrg::unitId), it.code, it.name, DemoOrg.START) }
        DemoOrg.POSITIONS.forEach { org.position(it.id, DemoOrg.unitId(it.unit), it.title, it.head, DemoOrg.START) }
        DemoOrg.REPORTING_LINES.forEach { (position, to) -> org.reportingLine(position, to, DemoOrg.START) }
        DemoOrg.SEATS.forEach { (email, position) -> org.assignment(position, demoAccounts.getValue(email), DemoOrg.START) }
        val openSeats = DemoOrg.POSITIONS.map { it.id }.filter { it !in DemoOrg.SEATS.values && it !in DemoOrg.VACANT }
        DemoOrg.STAFF.forEach { name ->
            val email = DemoOrg.staffEmail(name)
            accounts.findByEmail(email) ?: accounts.create(email, name)
        }
        DemoOrg.STAFF.dropLast(DemoOrg.UNSEATED).zip(openSeats).forEach { (name, position) ->
            org.assignment(position, requireNotNull(accounts.findByEmail(DemoOrg.staffEmail(name))).id.value, DemoOrg.START)
        }
    }

    private fun seedUser(user: DemoUser): UUID {
        val account = accounts.findByEmail(user.email) ?: accounts.create(user.email, user.displayName, user.tenantAdmin)
        if (user.tenantAdmin) access.grantBuiltIn(account.id.value, BuiltInRole.TENANT_ADMIN)
        if (demoPassword.isBlank()) {
            // Names the variable, never its value.
            // nosemgrep: semgrep.memo-no-secrets-in-logs
            log.warn("MEMO_DEMO_PASSWORD is not set: demo user {} cannot sign in", user.email)
        } else if (!accounts.passwordMatches(account.id, demoPassword)) {
            accounts.setPassword(account.id, demoPassword)
        }
        // Base32, so Playwright and people can compute codes for the demo users (MFA is required by default).
        if (demoTotpSecret.isNotBlank()) {
            check(BASE32_SECRET.matches(demoTotpSecret)) { "MEMO_DEMO_TOTP_SECRET must be 32 Base32 characters (A-Z, 2-7)" }
            mfa.ensureTotp(account.id, demoTotpSecret)
        }
        return account.id.value
    }

    private companion object {
        /** 160 bits, unpadded, as `scripts/dev.mjs` and `gen-secrets.sh` generate it. */
        val BASE32_SECRET = Regex("^[A-Z2-7]{32}$")
    }
}

/** Seeds on startup when `memo.demo.seed=true` (local dev via `pnpm dev`, the dev environment). */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 1)
@ConditionalOnProperty("memo.demo.seed", havingValue = "true")
class DemoTenantSeedOnStartup(
    private val seeder: DemoTenantSeeder,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) = seeder.seed()
}
