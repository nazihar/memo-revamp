package com.memo.branding

import com.memo.common.tenant.TenantTransactions
import com.memo.identity.MfaSeeding
import com.memo.identity.UserAccounts
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.FilesIntegrationTest
import com.memo.testsupport.Samples
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aClientIp
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.adminSession
import com.memo.testsupport.csrfToken
import com.memo.testsupport.fromIp
import com.memo.testsupport.onHost
import com.memo.testsupport.sendJson
import com.memo.testsupport.sendingCsrf
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Branding (design E07 §6; threat model T3): contrast enforced, raster logos only, served to the sign-in page. */
class BrandingIT : FilesIntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Autowired lateinit var json: JsonMapper

    private fun upload(
        tenant: TestTenant,
        session: Cookie,
        bytes: ByteArray,
        name: String,
    ) = mvc
        .post()
        .uri("/api/v1/files?purpose=tenant.logo&owner=tenant")
        .with(onHost(tenant.slug))
        .with(fromIp(aClientIp()))
        .with(sendingCsrf(mvc.csrfToken(tenant), session))
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$name\"")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .content(bytes)
        .exchange()

    private fun put(
        tenant: TestTenant,
        session: Cookie,
        body: String,
    ): org.springframework.test.web.servlet.assertj.MvcTestResult {
        val version =
            json
                .readTree(
                    mvc
                        .get()
                        .uri("/api/v1/admin/branding")
                        .with(onHost(tenant.slug))
                        .cookie(session)
                        .exchange()
                        .response.contentAsString,
                )["version"]
                .asLong()
        return mvc.sendJson(HttpMethod.PUT, tenant, "/api/v1/admin/branding", session, body, mapOf("If-Match" to "\"$version\""))
    }

    @Test
    fun `colours below their contrast minimum are refused with the ratio, and a good one is saved`() {
        val tenant = registry.aTestTenant(transactions, settings, "brand")
        val session = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)

        val yellow = put(tenant, session, """{"colourLight":"#ffff00"}""")
        assertThat(yellow).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(yellow.response.contentAsString).contains("LOW_CONTRAST", "1.07:1")
        assertThat(put(tenant, session, """{"colourLight":"red"}""").response.contentAsString).contains("INVALID")

        assertThat(put(tenant, session, """{"colourLight":"#1F4E8C","colourDark":"#8db2ea"}""")).hasStatus(HttpStatus.OK)
        val public =
            mvc
                .get()
                .uri("/api/v1/branding")
                .with(onHost(tenant.slug))
                .exchange()
        assertThat(public).hasStatus(HttpStatus.OK)
        assertThat(public.response.contentAsString).contains("#1f4e8c", "#8db2ea").doesNotContain("logoFileId\":\"")
    }

    @Test
    fun `a PNG logo is served without a session, an SVG or another purpose's file is not a logo`() {
        val tenant = registry.aTestTenant(transactions, settings, "logo")
        val session = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        assertThat(
            mvc
                .get()
                .uri("/api/v1/branding/logo")
                .with(onHost(tenant.slug))
                .exchange(),
        ).hasStatus(HttpStatus.NOT_FOUND)

        val svg = upload(tenant, session, "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray(), "logo.svg")
        assertThat(
            svg.response.status,
        ).isIn(HttpStatus.BAD_REQUEST.value(), HttpStatus.UNPROCESSABLE_CONTENT.value(), HttpStatus.UNSUPPORTED_MEDIA_TYPE.value())

        val png = Samples.png()
        val uploaded = upload(tenant, session, png, "logo.png")
        assertThat(uploaded).hasStatus(HttpStatus.CREATED)
        val fileId = json.readTree(uploaded.response.contentAsString)["fileId"].asString()
        assertThat(put(tenant, session, """{"logoFileId":"${UUID.randomUUID()}"}""")).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(put(tenant, session, """{"logoFileId":"$fileId"}""")).hasStatus(HttpStatus.OK)

        val logo =
            mvc
                .get()
                .uri("/api/v1/branding/logo")
                .with(onHost(tenant.slug))
                .exchange()
        assertThat(logo).hasStatus(HttpStatus.OK)
        assertThat(logo.response.contentAsByteArray).isEqualTo(png)
        assertThat(logo.response.contentType).isEqualTo("image/png")
        assertThat(logo.response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff")
        val etag = requireNotNull(logo.response.getHeader(HttpHeaders.ETAG))
        val again =
            mvc
                .get()
                .uri("/api/v1/branding/logo")
                .with(onHost(tenant.slug))
                .header(HttpHeaders.IF_NONE_MATCH, etag)
                .exchange()
        assertThat(again).hasStatus(HttpStatus.NOT_MODIFIED)
    }
}
