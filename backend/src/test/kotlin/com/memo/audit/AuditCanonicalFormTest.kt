package com.memo.audit

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AuditCanonicalFormTest {
    private val tenant = UUID.fromString("01900000-0000-7000-8000-0000000000aa")

    private fun entry(
        before: Map<String, Any?>? = null,
        after: Map<String, Any?>? = null,
    ) = AuditEntry(
        tenantId = tenant,
        seq = 7,
        occurredAt = Instant.parse("2026-10-18T09:15:02.123456789Z"),
        actorType = "SYSTEM",
        actorId = "job",
        actingAsId = null,
        clientIp = null,
        clientUserAgent = null,
        correlationId = "c-1",
        action = "tenant.setting.changed",
        objectType = "tenant.setting",
        objectId = "ui.theme",
        before = before,
        after = after,
    )

    @Test
    fun `canonical form sorts keys, drops whitespace, truncates time to microseconds and writes nulls`() {
        assertThat(AuditCanonicalForm.encode(entry(after = mapOf("z" to 1, "a" to "x"))))
            .isEqualTo(
                """{"action":"tenant.setting.changed","actor":{"actingAs":null,"id":"job","type":"SYSTEM"},""" +
                    """"after":{"a":"x","z":1},"at":"2026-10-18T09:15:02.123456Z","before":null,""" +
                    """"client":{"ip":null,"ua":null},"corr":"c-1","object":{"id":"ui.theme","type":"tenant.setting"},""" +
                    """"seq":7,"tenant":"01900000-0000-7000-8000-0000000000aa","v":1}""",
            )
    }

    @Test
    fun `equal content gives equal bytes regardless of map order`() {
        val one = AuditCanonicalForm.encode(entry(after = linkedMapOf("b" to listOf(1, 2), "a" to mapOf("y" to true, "x" to null))))
        val two = AuditCanonicalForm.encode(entry(after = linkedMapOf("a" to linkedMapOf("x" to null, "y" to true), "b" to listOf(1, 2))))
        assertThat(one).isEqualTo(two)
    }

    @Test
    fun `strings are escaped minimally and control characters as unicode escapes`() {
        assertThat(AuditCanonicalForm.json("q\"b\\n\nt\tc\u0001é")).isEqualTo("\"q\\\"b\\\\n\\nt\\tc\\u0001é\"")
    }

    @Test
    fun `floating point and other types are rejected`() {
        assertThatThrownBy { AuditCanonicalForm.json(mapOf("x" to 0.5)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AuditCanonicalForm.json(mapOf("x" to Instant.EPOCH)) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `hash chains on the previous hash and genesis differs per chain`() {
        val canonical = AuditCanonicalForm.encode(entry())
        val g1 = AuditCanonicalForm.genesis(tenant)
        val g2 = AuditCanonicalForm.genesis(UUID.randomUUID())
        assertThat(g1).isNotEqualTo(g2)
        assertThat(AuditCanonicalForm.hash(g1, canonical)).hasSize(32).isNotEqualTo(AuditCanonicalForm.hash(g2, canonical))
    }
}
