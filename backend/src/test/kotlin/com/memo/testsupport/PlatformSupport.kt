package com.memo.testsupport

import com.memo.identity.mfa.Totp
import com.memo.identity.operator.OperatorMfa
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.time.Instant
import java.util.UUID

/** The operator console's host in tests: `admin.<base domain>` (ADR-0031). */
const val PLATFORM_HOST = "admin.localhost"

const val OPERATOR_COOKIE = "MEMO_OPERATOR"

fun onPlatformHost(): RequestPostProcessor =
    RequestPostProcessor { request ->
        request.serverName = PLATFORM_HOST
        request
    }

/** An active operator with password and TOTP; [secret] in Base32 so tests compute codes. */
data class TestOperator(
    val id: UUID,
    val email: String,
    val password: String,
    val secret: String,
) {
    /** The TOTP code [stepsAhead] steps from now (each step works once per operator; ±1 step is accepted). */
    fun code(stepsAhead: Long = 0): String = Totp.code(Totp.decodeBase32(secret), Totp.step(Instant.now()) + stepsAhead)
}

fun OperatorMfa.anOperator(): TestOperator {
    val email = "op-${UUID.randomUUID().toString().take(8)}@memo.example"
    val password = aPassword()
    val secret = Totp.base32(Totp.newSecret())
    val operator = seed(email, "Test Operator", password, secret)
    return TestOperator(operator.id, email, password, secret)
}

/** A real CSRF cookie from the console's own endpoint. */
fun MockMvcTester.platformCsrf(): Cookie =
    requireNotNull(
        get()
            .uri("/api/v1/platform/auth/csrf")
            .with(onPlatformHost())
            .exchange()
            .response
            .getCookie("XSRF-TOKEN"),
    ) { "no CSRF cookie on the operator host" }

/** A JSON request on the operator host with a real CSRF token and the [session] cookie (any name). */
fun MockMvcTester.platformSend(
    method: HttpMethod,
    uri: String,
    session: Cookie?,
    json: String = "{}",
    headers: Map<String, String> = emptyMap(),
): MvcTestResult =
    method(method)
        .uri(uri)
        .with(onPlatformHost())
        .with(fromIp(aClientIp()))
        .headers { h -> headers.forEach { (name, value) -> h.add(name, value) } }
        .with(sendingCsrf(platformCsrf(), *listOfNotNull(session).toTypedArray()))
        .contentType(MediaType.APPLICATION_JSON)
        .content(json)
        .exchange()

fun MockMvcTester.platformPost(
    uri: String,
    session: Cookie?,
    json: String = "{}",
): MvcTestResult = platformSend(HttpMethod.POST, uri, session, json)

fun MockMvcTester.platformGet(
    uri: String,
    session: Cookie?,
): MvcTestResult =
    get()
        .uri(uri)
        .with(onPlatformHost())
        .apply { session?.let { cookie(it) } }
        .exchange()

/** The new operator session cookie when the response rotated the id, else [previous]. */
fun MvcTestResult.operatorCookie(previous: Cookie? = null): Cookie? = response.getCookie(OPERATOR_COOKIE) ?: previous

/** Password step only. */
fun MockMvcTester.operatorPartial(operator: TestOperator): Cookie {
    val result =
        platformPost("/api/v1/platform/auth/login", null, """{"email":"${operator.email}","password":"${operator.password}"}""")
    assertThat(result).hasStatus(HttpStatus.OK)
    return requireNotNull(result.operatorCookie()) { "no operator cookie" }
}

/** Password and TOTP: a complete operator session. */
fun MockMvcTester.operatorSession(operator: TestOperator): Cookie {
    val partial = operatorPartial(operator)
    val result = platformPost("/api/v1/platform/auth/mfa", partial, """{"code":"${operator.code()}"}""")
    assertThat(result).hasStatus(HttpStatus.OK)
    return requireNotNull(result.operatorCookie(partial))
}
