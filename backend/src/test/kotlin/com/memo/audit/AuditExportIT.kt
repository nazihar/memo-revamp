package com.memo.audit

import com.memo.common.tenant.TenantTransactions
import com.memo.identity.MfaSeeding
import com.memo.identity.UserAccounts
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.adminSession
import com.memo.testsupport.onHost
import com.memo.testsupport.sendJson
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.zip.ZipInputStream

/**
 * The verifiable export and the query API (ADR-0033; test plan T5). The verifier below is written from VERIFY.md alone,
 * not from memo's own `AuditChainVerifier`, as an outside auditor would.
 */
class AuditExportIT : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Autowired lateinit var json: JsonMapper

    private val hex = HexFormat.of()

    private fun export(
        tenant: TestTenant,
        session: Cookie,
        narrowing: String = "",
    ): Map<String, ByteArray> {
        val from = Instant.now().minus(1, ChronoUnit.DAYS)
        val to = Instant.now().plus(1, ChronoUnit.MINUTES)
        val result =
            mvc.sendJson(
                HttpMethod.POST,
                tenant,
                "/api/v1/admin/audit/export",
                session,
                """{"from":"$from","to":"$to"$narrowing}""",
            )
        assertThat(result).hasStatus(HttpStatus.OK)
        return ZipInputStream(ByteArrayInputStream(result.response.contentAsByteArray)).use { zip ->
            generateSequence { zip.nextEntry }.associate { it.name to zip.readAllBytes() }
        }
    }

    private fun seqs(page: JsonNode): List<Long> = (0 until page["items"].size()).map { page["items"][it]["seq"].asLong() }

    /** VERIFY.md steps 1–3; returns the seq of the first line that fails, or null. */
    private fun verify(files: Map<String, ByteArray>): Long? {
        val manifest = json.readTree(files.getValue("manifest.json"))
        val events = files.getValue("events.ndjson")
        assertThat(hex.formatHex(MessageDigest.getInstance("SHA-256").digest(events))).isEqualTo(manifest["eventsSha256"].asString())
        var previous: String? = manifest["firstPrevHash"].asString()
        String(events, Charsets.UTF_8).lines().filter { it.isNotBlank() }.map { json.readTree(it) }.forEach { line: JsonNode ->
            val prev = line["prevHash"].asString()
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(hex.parseHex(prev))
            val computed = hex.formatHex(digest.digest(line["canonical"].asString().toByteArray(Charsets.UTF_8)))
            val chained = !manifest["continuous"].asBoolean() || prev == previous
            if (computed != line["hash"].asString() || !chained) return line["seq"].asLong()
            previous = line["hash"].asString()
        }
        if (manifest["continuous"].asBoolean()) assertThat(previous).isEqualTo(manifest["lastHash"].asString())
        return null
    }

    @Test
    fun `an outside verifier recomputes every hash of an unfiltered export, and an altered line fails`() {
        val tenant = registry.aTestTenant(transactions, settings, "export")
        val session = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)

        val files = export(tenant, session)

        assertThat(files.keys).containsExactlyInAnyOrder("events.ndjson", "manifest.json", "VERIFY.md")
        val manifest = json.readTree(files.getValue("manifest.json"))
        assertThat(manifest["continuous"].asBoolean()).isTrue()
        assertThat(manifest["count"].asLong()).isGreaterThan(2)
        assertThat(manifest["firstSeq"].asLong()).isEqualTo(1)
        assertThat(manifest["firstPrevHash"].asString()).isEqualTo(manifest["genesis"].asString())
        assertThat(verify(files)).isNull()

        val tampered = String(files.getValue("events.ndjson"), Charsets.UTF_8).replaceFirst("tenant.created", "tenant.cr3ated")
        val manifestFixed =
            String(files.getValue("manifest.json"), Charsets.UTF_8).replace(
                manifest["eventsSha256"].asString(),
                hex.formatHex(MessageDigest.getInstance("SHA-256").digest(tampered.toByteArray(Charsets.UTF_8))),
            )
        val broken = verify(files + mapOf("events.ndjson" to tampered.toByteArray(), "manifest.json" to manifestFixed.toByteArray()))
        assertThat(broken).isEqualTo(1)

        assertThat(Audit.entries(tenant.id).map { it.action }).contains("audit.exported")
    }

    @Test
    fun `a filtered export is not continuous, the viewer pages newest first, and other tenants never appear`() {
        val tenant = registry.aTestTenant(transactions, settings, "export-f")
        val other = registry.aTestTenant(transactions, settings, "export-o")
        val session = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)

        val filtered = export(tenant, session, ""","action":"identity.user"""")
        assertThat(json.readTree(filtered.getValue("manifest.json"))["continuous"].asBoolean()).isFalse()
        assertThat(verify(filtered)).isNull()
        assertThat(String(filtered.getValue("events.ndjson"))).contains("identity.user.").doesNotContain("tenant.created")

        val first =
            json.readTree(
                mvc
                    .get()
                    .uri("/api/v1/admin/audit?limit=2")
                    .with(onHost(tenant.slug))
                    .cookie(session)
                    .exchange()
                    .response.contentAsString,
            )
        val seqs = seqs(first)
        assertThat(seqs).isSortedAccordingTo(Comparator.reverseOrder())
        val next =
            json.readTree(
                mvc
                    .get()
                    .uri("/api/v1/admin/audit?limit=2&cursor=${first["nextCursor"].asLong()}")
                    .with(onHost(tenant.slug))
                    .cookie(session)
                    .exchange()
                    .response.contentAsString,
            )
        assertThat(seqs(next)).allMatch { it < seqs.min() }
        assertThat(first.toString() + next.toString()).doesNotContain(other.id.toString())
    }
}
