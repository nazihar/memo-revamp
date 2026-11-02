package com.memo.testsupport

import com.memo.common.tenant.TenantTransactions
import com.memo.identity.MfaSeeding
import com.memo.identity.UserAccounts
import com.memo.identity.mfa.Totp
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import jakarta.servlet.http.Cookie
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.yaml.snakeyaml.Yaml
import java.time.Instant

/** One cell of the authorisation matrix (TESTING_STRATEGY §3.4): what [subject] gets from [method] [path]. */
data class MatrixCell(
    val epic: String,
    val method: String,
    val path: String,
    val subject: String,
    /** `allow` (passes authorisation, rules.yaml) or an exact status. */
    val expected: String,
    /** Sample request body and headers (rules.yaml); `{random}` stands for a fresh UUID. */
    val body: Map<String, Any?>? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    val endpoint: String get() = "$method $path"

    override fun toString() = "$epic $endpoint as $subject → $expected"
}

/** The machine-readable matrix files in `src/test/resources/authz` (`rules.yaml` plus one file per epic). */
object AuthzMatrix {
    private val yaml = Yaml()

    @Suppress("UNCHECKED_CAST")
    private fun read(resource: org.springframework.core.io.Resource): Map<String, Any?> =
        resource.inputStream.use { yaml.load<Map<String, Any?>>(it) }

    private val resources = PathMatchingResourcePatternResolver().getResources("classpath:authz/*.yaml").toList()

    @Suppress("UNCHECKED_CAST")
    private val rulesFile: Map<String, Any?> = read(resources.single { it.filename == "rules.yaml" })

    @Suppress("UNCHECKED_CAST")
    val subjects: List<String> = rulesFile["subjects"] as List<String>

    @Suppress("UNCHECKED_CAST")
    private val rules: Map<String, Map<String, Any>> = rulesFile["rules"] as Map<String, Map<String, Any>>

    @Suppress("UNCHECKED_CAST")
    val cells: List<MatrixCell> by lazy {
        resources.filter { it.filename != "rules.yaml" }.flatMap { resource ->
            val file = read(resource)
            val epic = file["epic"] as String
            (file["endpoints"] as List<Map<String, Any?>>).flatMap { entry ->
                val endpoint = entry["endpoint"] as String
                val (method, path) = endpoint.split(" ", limit = 2)
                val rule = requireNotNull(rules[entry["rule"]]) { "$endpoint: unknown rule ${entry["rule"]}" }
                val overrides = entry["expect"] as Map<String, Any>? ?: emptyMap()
                require(overrides.keys.all { it in subjects }) { "$endpoint: unknown subject in $overrides" }
                subjects.map { subject ->
                    val expected = requireNotNull(overrides[subject] ?: rule[subject]) { "$endpoint: no expectation for $subject" }
                    MatrixCell(
                        epic,
                        method,
                        path,
                        subject,
                        expected.toString(),
                        entry["body"] as Map<String, Any?>?,
                        (entry["headers"] as Map<String, Any?>?).orEmpty().mapValues { it.value.toString() },
                    )
                }
            }
        }
    }

    /** Every endpoint listed in some epic file. */
    val endpoints: Set<String> by lazy { cells.map { it.endpoint }.toSet() }
}

/**
 * Builds a fresh session for each matrix subject (rules.yaml). Every cell signs in a new user, so cells cannot affect
 * each other (an allowed logout or session revocation only ends that cell's session).
 */
class AuthzSubjects(
    private val mvc: MockMvcTester,
    registry: TenantRegistry,
    private val settings: TenantSettings,
    private val transactions: TenantTransactions,
    private val accounts: UserAccounts,
    private val mfaSeeding: MfaSeeding,
) {
    private val password = aPassword()

    /** MFA only for admins, so `user` completes its session with the password. */
    val tenant: TestTenant = registry.aTestTenant(transactions, settings, "matrix", mfaRequired = false)

    private val otherTenant: TestTenant = registry.aTestTenant(transactions, settings, "matrix-other", mfaRequired = false)

    fun session(subject: String): Cookie? =
        when (subject) {
            "anonymous" -> null
            "partial" -> adminPartial(tenant).first
            "user" -> mvc.signIn(tenant, accounts.anActiveUser(transactions, tenant, password).email, password)
            "tenantAdmin" -> adminComplete(tenant)
            "tenantAdminStale" -> adminComplete(tenant).also { ageSessionFactors(it) }
            "otherTenantAdmin" -> adminComplete(otherTenant)
            else -> error("unknown subject $subject")
        }

    private fun adminPartial(on: TestTenant): Pair<Cookie, String> {
        val admin = accounts.anActiveUser(transactions, on, password, tenantAdmin = true)
        val secret = Totp.base32(Totp.newSecret())
        transactions.inTenant(on.id) { mfaSeeding.ensureTotp(admin.id, secret) }
        return requireNotNull(mvc.login(on, admin.email, password).sessionCookie()) to secret
    }

    private fun adminComplete(on: TestTenant): Cookie {
        val (partial, secret) = adminPartial(on)
        val code = Totp.code(Totp.decodeBase32(secret), Totp.step(Instant.now()))
        return requireNotNull(mvc.postJson(on, "/api/v1/auth/mfa", partial, """{"code":"$code"}""").sessionCookie(partial))
    }
}
