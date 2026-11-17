package com.memo.testsupport

import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.BuiltInRole
import com.memo.common.authz.Permission
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.MfaSeeding
import com.memo.identity.UserAccounts
import com.memo.identity.mfa.Totp
import com.memo.imports.internal.BatchSettingsInput
import com.memo.imports.internal.ImportBatches
import com.memo.imports.internal.ImportIntake
import com.memo.imports.internal.PathToRegister
import jakarta.servlet.http.Cookie
import org.springframework.stereotype.Component
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Instant
import java.util.UUID

/**
 * Legacy import set-up for tests (ADR-0055): holders of `memo.import`, batches and registered originals, so uploads of
 * the real `imports.source` and `imports.intake` purposes pass their owner checks.
 */
@Component
class ImportSupport(
    private val access: AccessAdministration,
    private val batches: ImportBatches,
    private val intake: ImportIntake,
    private val transactions: TenantTransactions,
    private val accounts: UserAccounts,
    private val mfaSeeding: MfaSeeding,
) {
    /** Gives [user] `memo.import` through a custom role (no built-in role has it). */
    fun grant(
        tenant: TestTenant,
        user: UUID,
    ) = ActorContext.asSystem("test-imports") {
        transactions.inTenant(tenant.id) { access.grantCustom(user, IMPORTER, setOf(Permission.MEMO_IMPORT)) }
    }

    /** A new batch created by [user] (who holds `memo.import`). */
    fun batch(
        tenant: TestTenant,
        user: UUID,
        name: String = "Legacy batch",
    ): UUID =
        asUser(tenant, user) {
            transactions.inTenant(tenant.id) {
                batches.create(BatchSettingsInput(name, "LEGACY-DMS", "Asia/Dhaka")).id
            }
        }

    /** Registers [paths] (path to size) in [batchId]; returns each path's intake id. */
    fun register(
        tenant: TestTenant,
        user: UUID,
        batchId: UUID,
        paths: Map<String, Long>,
    ): Map<String, UUID> =
        asUser(tenant, user) {
            transactions.inTenant(tenant.id) {
                intake.register(batchId, paths.map { (path, size) -> PathToRegister(path, size) }).associate { it.path to it.id }
            }
        }

    /** A newly registered original of [batchId]: the `owner` of an `imports.intake` upload. */
    fun intakeOwner(
        tenant: TestTenant,
        user: UUID,
        batchId: UUID,
        path: String = "archive/${UUID.randomUUID()}.bin",
    ): String = register(tenant, user, batchId, mapOf(path to 1L)).getValue(path).toString()

    /**
     * A new holder of `memo.import`, signed in with password and TOTP (the permission is privileged, so a second factor
     * is required, ADR-0024).
     */
    fun importer(
        mvc: MockMvcTester,
        tenant: TestTenant,
    ): Pair<UUID, Cookie> = withSecondFactor(mvc, tenant) { grant(tenant, it) }

    /** A new auditor (`audit.read`, privileged, so also signed in with TOTP), who may read import reports when cleared. */
    fun auditor(
        mvc: MockMvcTester,
        tenant: TestTenant,
    ): Pair<UUID, Cookie> =
        withSecondFactor(mvc, tenant) { user ->
            ActorContext.asSystem("test-imports") {
                transactions.inTenant(tenant.id) { access.grantBuiltIn(user, BuiltInRole.AUDITOR) }
            }
        }

    /** A new user given [grant], signed in with password and TOTP. */
    private fun withSecondFactor(
        mvc: MockMvcTester,
        tenant: TestTenant,
        grant: (UUID) -> Unit,
    ): Pair<UUID, Cookie> {
        val password = aPassword()
        val account = accounts.anActiveUser(transactions, tenant, password)
        grant(account.id.value)
        val secret = Totp.base32(Totp.newSecret())
        transactions.inTenant(tenant.id) { mfaSeeding.ensureTotp(account.id, secret) }
        val partial = requireNotNull(mvc.login(tenant, account.email, password).sessionCookie())
        val code = Totp.code(Totp.decodeBase32(secret), Totp.step(Instant.now()))
        val session = requireNotNull(mvc.postJson(tenant, "/api/v1/auth/mfa", partial, """{"code":"$code"}""").sessionCookie(partial))
        return account.id.value to session
    }

    private fun <T> asUser(
        tenant: TestTenant,
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { TenantContext.call(tenant.id, block) }

    companion object {
        const val IMPORTER = "Importer"
    }
}
