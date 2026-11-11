package com.memo.docedit

import com.memo.docedit.internal.BadSignatureException
import com.memo.docedit.internal.DocumentKeys
import com.memo.docedit.internal.Hs256
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/** HS256 tokens, document keys and signed links of the editor integration (E02 test plan, T2; threat model S1, E1). */
class SigningTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-11T10:00:00Z"), ZoneOffset.UTC)
    private val json = JsonMapper()
    private val jwt = Hs256("s".repeat(40), json, clock)
    private val keys = DocumentKeys("d".repeat(40), clock)

    private fun encode(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    @Test
    fun `a signed token verifies, and another secret, another algorithm, a changed payload or an old exp do not`() {
        val token = jwt.sign(mapOf("key" to "k1", "status" to 2))
        assertThat(jwt.verify(token)).containsEntry("key", "k1")

        assertThatThrownBy { Hs256("t".repeat(40), json, clock).verify(token) }.isInstanceOf(BadSignatureException::class.java)
        val (_, body, signature) = token.split('.')
        assertThatThrownBy { jwt.verify("${encode("""{"alg":"none"}""")}.$body.") }.hasMessageContaining("algorithm")
        assertThatThrownBy { jwt.verify("${encode("""{"alg":"HS512"}""")}.$body.$signature") }.hasMessageContaining("algorithm")
        val changed = encode("""{"key":"k2","status":2}""")
        assertThatThrownBy { jwt.verify("${token.substringBefore('.')}.$changed.$signature") }.hasMessageContaining("signature")
        val expired = jwt.sign(mapOf("exp" to clock.instant().epochSecond - 1))
        assertThatThrownBy { jwt.verify(expired) }.hasMessageContaining("expired")
        assertThatThrownBy { jwt.verify("not-a-token") }.isInstanceOf(BadSignatureException::class.java)
    }

    @Test
    fun `document keys follow the editor's rules, differ per version and tenant, and links expire`() {
        val tenant = UUID.randomUUID()
        val file = UUID.randomUUID()
        val v1 = keys.documentKey(tenant, file, UUID.randomUUID())
        val v2 = keys.documentKey(tenant, file, UUID.randomUUID())
        assertThat(v1).matches("^m[A-Z2-7]{40}$").isNotEqualTo(v2)
        assertThat(keys.documentKey(UUID.randomUUID(), file, UUID.fromString(UUID(0, 1).toString()))).isNotEqualTo(v1)

        val session = UUID.randomUUID()
        val later = clock.instant().epochSecond + 300
        val signature = keys.linkSignature(tenant, session, "content", later)
        keys.verifyLink(tenant, session, "content", later, signature)
        assertThatThrownBy {
            keys.verifyLink(
                tenant,
                session,
                "callback",
                later,
                signature,
            )
        }.isInstanceOf(BadSignatureException::class.java)
        assertThatThrownBy { keys.verifyLink(tenant, UUID.randomUUID(), "content", later, signature) }
            .isInstanceOf(BadSignatureException::class.java)
        val past = clock.instant().epochSecond - 1
        assertThatThrownBy { keys.verifyLink(tenant, session, "content", past, keys.linkSignature(tenant, session, "content", past)) }
            .hasMessageContaining("expired")
    }
}
