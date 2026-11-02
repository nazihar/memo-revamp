@file:Suppress("MatchingDeclarationName") // helpers around TestTenant; the file is named for what it supports

package com.memo.testsupport

import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.identity.auth.AuthSettings
import com.memo.identity.password.BreachedPasswords
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** A tenant and the host its requests use (`<slug>.localhost`). */
data class TestTenant(
    val id: TenantId,
    val slug: String,
)

/** A fresh tenant; [mfaRequired] false lets non-admin users finish sign-in with a password alone. */
fun TenantRegistry.aTestTenant(
    transactions: TenantTransactions,
    settings: TenantSettings,
    label: String = "t",
    mfaRequired: Boolean = true,
): TestTenant {
    val slug = "$label-${UUID.randomUUID().toString().take(8)}"
    val tenant = TestTenant(create(slug = slug, name = "Test tenant $slug").id, slug)
    if (!mfaRequired) transactions.inTenant(tenant.id) { settings.put(AuthSettings.MFA_REQUIRED, AuthSettings.MFA_ADMINS_ONLY) }
    return tenant
}

/**
 * Generated per run: test passwords never live in code or fixtures. The breached-password filter flags about 1 in 1,000
 * random strings by design (ADR-0023); a test password must never be one of them, or the suite fails at random.
 */
fun aPassword(): String =
    generateSequence { "pw-" + Base64.getUrlEncoder().encodeToString(ByteArray(18).also { SecureRandom().nextBytes(it) }) }
        .first { !BREACHED.isBreached(it) }

private val BREACHED = BreachedPasswords("")

/** An ACTIVE user with [password]. */
fun UserAccounts.anActiveUser(
    transactions: TenantTransactions,
    tenant: TestTenant,
    password: String,
    tenantAdmin: Boolean = false,
): UserAccount =
    transactions.inTenant(tenant.id) {
        val user = create("user-${UUID.randomUUID().toString().take(8)}@bank.example", "Test User", tenantAdmin)
        setPassword(user.id, password)
    }

const val SESSION_COOKIE = "MEMO_SESSION"

/**
 * A real CSRF token, fetched as the SPA does (`GET /auth/csrf` sets the `XSRF-TOKEN` cookie). Not Spring Security's
 * `csrf()` post-processor: it swaps in a session-backed token store, which session attributes reject (strings only).
 */
fun MockMvcTester.csrfToken(tenant: TestTenant): Cookie =
    requireNotNull(
        get()
            .uri("/api/v1/auth/csrf")
            .with(onHost(tenant.slug))
            .exchange()
            .response
            .getCookie("XSRF-TOKEN"),
    ) { "no CSRF cookie" }

/** Sends [token] as cookie and `X-XSRF-TOKEN` header, plus [others] (the session cookie). */
fun sendingCsrf(
    token: Cookie,
    vararg others: Cookie,
): RequestPostProcessor =
    RequestPostProcessor { request ->
        val existing: List<Cookie> = request.cookies?.toList() ?: emptyList()
        request.setCookies(*(existing + token + others).toTypedArray())
        request.addHeader("X-XSRF-TOKEN", token.value)
        request
    }

/**
 * A random client address per sign-in (TEST-NET-2/3 ranges): test classes share the database, so a shared 127.0.0.1
 * would exhaust the per-IP limit across classes (ADR-0025). Rate-limit tests pass a fixed [clientIp].
 */
fun aClientIp(): String = "198.51.${RANDOM.nextInt(IP_OCTETS)}.${RANDOM.nextInt(IP_OCTETS)}"

private val RANDOM = SecureRandom()
private const val IP_OCTETS = 256

fun fromIp(ip: String): RequestPostProcessor =
    RequestPostProcessor { request ->
        request.remoteAddr = ip
        request
    }

fun MockMvcTester.login(
    tenant: TestTenant,
    email: String,
    password: String,
    session: Cookie? = null,
    clientIp: String = aClientIp(),
    userAgent: String = "memo-tests",
): MvcTestResult =
    post()
        .uri("/api/v1/auth/login")
        .with(onHost(tenant.slug))
        .with(fromIp(clientIp))
        .header("User-Agent", userAgent)
        .with(sendingCsrf(csrfToken(tenant), *listOfNotNull(session).toTypedArray()))
        .contentType(MediaType.APPLICATION_JSON)
        .content("""{"email":${json(email)},"password":${json(password)}}""")
        .exchange()

/** A mutating JSON request on the tenant's host with a real CSRF token and the [session] cookie. */
fun MockMvcTester.postJson(
    tenant: TestTenant,
    uri: String,
    session: Cookie?,
    json: String = "{}",
): MvcTestResult =
    post()
        .uri(uri)
        .with(onHost(tenant.slug))
        .with(fromIp(aClientIp()))
        .with(sendingCsrf(csrfToken(tenant), *listOfNotNull(session).toTypedArray()))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json)
        .exchange()

/** The new session cookie when the response rotated the session id, else [previous]. */
fun MvcTestResult.sessionCookie(previous: Cookie? = null): Cookie? = response.getCookie(SESSION_COOKIE) ?: previous

/** Signs in and returns the session cookie. */
fun MockMvcTester.signIn(
    tenant: TestTenant,
    email: String,
    password: String,
): Cookie {
    val result = login(tenant, email, password)
    assertThat(result).hasStatus(HttpStatus.OK)
    return requireNotNull(result.response.getCookie(SESSION_COOKIE)) { "no session cookie" }
}

private fun json(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** The session id inside a `MEMO_SESSION` cookie (Spring Session Base64-encodes it). */
fun sessionIdOf(cookie: Cookie): String = String(Base64.getDecoder().decode(cookie.value))

/** Moves every factor of the session back to the year 2000, as if the step-up window had long passed. */
fun ageSessionFactors(cookie: Cookie) {
    AppRoleJdbc.connect { c ->
        c
            .prepareStatement(
                """
                update identity_http_session_attributes a
                set attribute_bytes = convert_to(
                    jsonb_set(p.j, '{factors}', (
                        select jsonb_agg(jsonb_set(f, '{issuedAt}', '"2000-01-01T00:00:00Z"'))
                        from jsonb_array_elements(p.j -> 'factors') f))::text, 'UTF8')
                from (select session_primary_id, convert_from(attribute_bytes, 'UTF8')::jsonb j
                      from identity_http_session_attributes where attribute_name = 'memo.principal') p
                where a.attribute_name = 'memo.principal' and a.session_primary_id = p.session_primary_id
                  and a.session_primary_id = (select primary_id from identity_http_session where session_id = ?)
                """,
            ).use {
                it.setString(1, sessionIdOf(cookie))
                check(it.executeUpdate() == 1) { "no such session" }
            }
    }
}

/** Any mutating request (PUT, DELETE, …) as [postJson] sends it. */
fun MockMvcTester.sendJson(
    method: org.springframework.http.HttpMethod,
    tenant: TestTenant,
    uri: String,
    session: Cookie?,
    json: String = "{}",
    headers: Map<String, String> = emptyMap(),
): MvcTestResult =
    method(method)
        .uri(uri)
        .with(onHost(tenant.slug))
        .with(fromIp(aClientIp()))
        .headers { h -> headers.forEach { (name, value) -> h.add(name, value) } }
        .with(sendingCsrf(csrfToken(tenant), *listOfNotNull(session).toTypedArray()))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json)
        .exchange()
