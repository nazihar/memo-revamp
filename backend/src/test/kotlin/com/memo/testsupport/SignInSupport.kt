@file:Suppress("MatchingDeclarationName") // helpers around TestTenant; the file is named for what it supports

package com.memo.testsupport

import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.identity.auth.AuthSettings
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
    if (!mfaRequired) transactions.inTenant(tenant.id) { settings.put(AuthSettings.MFA_REQUIRED, "false") }
    return tenant
}

/** Generated per run: test passwords never live in code or fixtures. */
fun aPassword(): String = "pw-" + Base64.getUrlEncoder().encodeToString(ByteArray(18).also { SecureRandom().nextBytes(it) })

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

fun MockMvcTester.login(
    tenant: TestTenant,
    email: String,
    password: String,
    session: Cookie? = null,
): MvcTestResult =
    post()
        .uri("/api/v1/auth/login")
        .with(onHost(tenant.slug))
        .with(sendingCsrf(csrfToken(tenant), *listOfNotNull(session).toTypedArray()))
        .contentType(MediaType.APPLICATION_JSON)
        .content("""{"email":${json(email)},"password":${json(password)}}""")
        .exchange()

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
