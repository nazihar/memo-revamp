package com.memo.tenancy

import com.memo.common.tenant.TenantTransactions
import com.memo.identity.MfaSeeding
import com.memo.identity.UserAccounts
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

/** Terminology overrides (ADR-0032; threat model T4): per language, validated, versioned and audited. */
class TerminologyIT : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Autowired lateinit var json: JsonMapper

    private fun terms(
        tenant: TestTenant,
        session: Cookie,
        locale: String,
    ): JsonNode =
        json.readTree(
            mvc
                .get()
                .uri("/api/v1/terms?locale=$locale")
                .with(onHost(tenant.slug))
                .cookie(session)
                .exchange()
                .response.contentAsString,
        )

    private fun put(
        tenant: TestTenant,
        session: Cookie,
        body: String,
        version: Long,
    ) = mvc.sendJson(HttpMethod.PUT, tenant, "/api/v1/admin/terms", session, body, mapOf("If-Match" to "\"$version\""))

    @Test
    fun `an override applies to its language only, needs a gender in Arabic and refuses markup`() {
        val tenant = registry.aTestTenant(transactions, settings, "terms")
        val session = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)

        val saved =
            put(
                tenant,
                session,
                """{"locale":"en","terms":[{"term":"unit","one":"department","other":"departments"}]}""",
                terms(tenant, session, "en")["version"].asLong(),
            )
        assertThat(saved).hasStatus(HttpStatus.OK)
        assertThat(terms(tenant, session, "en")["terms"].toString()).contains("department", "departments")
        assertThat(terms(tenant, session, "ar")["terms"].size()).isZero()

        val noGender = put(tenant, session, """{"locale":"ar","terms":[{"term":"unit","one":"إدارة","other":"إدارات"}]}""", 0)
        assertThat(noGender).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(noGender.response.contentAsString).contains("terms[0].gender", "REQUIRED")
        val withGender =
            put(tenant, session, """{"locale":"ar","terms":[{"term":"unit","one":"إدارة","other":"إدارات","gender":"feminine"}]}""", 0)
        assertThat(withGender).hasStatus(HttpStatus.OK)

        val markup =
            put(
                tenant,
                session,
                """{"locale":"en","terms":[{"term":"unit","one":"{x}","other":"<b>"}]}""",
                terms(tenant, session, "en")["version"].asLong(),
            )
        assertThat(markup).hasStatus(HttpStatus.BAD_REQUEST)

        assertThat(Audit.entries(tenant.id).map { it.action to it.objectId }).contains("tenant.term.changed" to "unit.en")
    }
}
