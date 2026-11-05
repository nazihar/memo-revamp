package com.memo.files

import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccounts
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.FilesIntegrationTest
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.Samples
import com.memo.testsupport.TestDocumentPurpose
import com.memo.testsupport.TestImagePurpose
import com.memo.testsupport.TestMalwareScanner
import com.memo.testsupport.TestObjectStorage
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aClientIp
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.csrfToken
import com.memo.testsupport.fromIp
import com.memo.testsupport.onHost
import com.memo.testsupport.sendingCsrf
import com.memo.testsupport.signIn
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** The upload API (design §6, ADR-0029): raw bodies, names in Content-Disposition, Problem Details with reasons. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UploadApiIT : FilesIntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var json: JsonMapper

    private val tenant: TestTenant by lazy { registry.aTestTenant(transactions, settings, "upapi", mfaRequired = false) }

    /** A signed-in non-admin user (no MFA in this tenant); each test gets its own, as the upload limit is per user. */
    private fun aSession(on: TestTenant = tenant): Cookie {
        val password = aPassword()
        val user = accounts.anActiveUser(transactions, on, password)
        return mvc.signIn(on, user.email, password)
    }

    private fun upload(
        session: Cookie,
        bytes: ByteArray,
        uri: String = "/api/v1/files?purpose=${TestImagePurpose.KEY}",
        disposition: String? = "attachment; filename*=UTF-8''logo%20%C3%A9t%C3%A9.png",
        on: TestTenant = tenant,
        headers: Map<String, String> = emptyMap(),
        contentType: MediaType = MediaType.APPLICATION_OCTET_STREAM,
    ): MvcTestResult =
        mvc
            .post()
            .uri(uri)
            .with(onHost(on.slug))
            .with(fromIp(aClientIp()))
            .with(sendingCsrf(mvc.csrfToken(on), session))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .headers { h ->
                disposition?.let { h.add(HttpHeaders.CONTENT_DISPOSITION, it) }
                headers.forEach { (name, value) -> h.add(name, value) }
            }.contentType(contentType)
            .content(bytes)
            .exchange()

    @Suppress("UNCHECKED_CAST")
    private fun MvcTestResult.body(): Map<String, Any?> = json.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>

    @Test
    fun `an upload returns 201 with the version, and the file lists its versions behind an ETag`() {
        val session = aSession()

        val created = upload(session, Samples.png())

        assertThat(created).hasStatus(HttpStatus.CREATED)
        val body = created.body()
        assertThat(body["name"]).isEqualTo("logo été.png")
        assertThat(body["mediaType"]).isEqualTo("image/png")
        assertThat(body["number"]).isEqualTo(1)
        assertThat(created.response.getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/v1/files/${body["fileId"]}")

        val file =
            mvc
                .get()
                .uri("/api/v1/files/${body["fileId"]}")
                .with(onHost(tenant.slug))
                .cookie(session)
                .exchange()
        assertThat(file).hasStatusOk()
        assertThat(file.response.getHeader(HttpHeaders.ETAG)).isNotBlank()
        assertThat(file.body()["versions"] as List<*>).hasSize(1)
    }

    @Test
    fun `a new version needs the current ETag`() {
        val session = aSession()
        val fileId = upload(session, Samples.png()).body()["fileId"]
        val etag =
            mvc
                .get()
                .uri(
                    "/api/v1/files/$fileId",
                ).with(onHost(tenant.slug))
                .cookie(session)
                .exchange()
                .response
                .getHeader(HttpHeaders.ETAG)!!
        val versions = "/api/v1/files/$fileId/versions"

        assertThat(upload(session, Samples.jpeg(), versions, "attachment; filename=\"v2.jpg\"", headers = mapOf("If-Match" to etag)))
            .hasStatus(HttpStatus.CREATED)
        assertThat(upload(session, Samples.png(), versions, headers = mapOf("If-Match" to etag))).hasStatus(HttpStatus.PRECONDITION_FAILED)
        assertThat(upload(session, Samples.png(), versions)).hasStatus(HttpStatus.PRECONDITION_REQUIRED)
    }

    @Test
    fun `refusals carry a stable reason`() {
        val session = aSession()

        val html = upload(session, Samples.html, disposition = "attachment; filename=logo.png")
        assertThat(html).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(html.body()).containsEntry("reason", "TYPE_NOT_ALLOWED").containsEntry("detectedType", "text/html")
        assertThat(upload(session, TestMalwareScanner.eicar).body()).containsEntry("reason", "MALWARE")
        assertThat(upload(session, Samples.jpeg()).body()).containsEntry("reason", "TYPE_MISMATCH")

        val unknown = upload(session, Samples.png(), "/api/v1/files?purpose=nope")
        assertThat(unknown).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(unknown.body()).containsEntry("reason", "UNKNOWN_PURPOSE")
        assertThat(upload(session, Samples.pdf(), "/api/v1/files?purpose=${TestDocumentPurpose.KEY}")).hasStatus(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `multipart bodies are not accepted at all`() {
        assertThat(
            upload(aSession(), Samples.png(), contentType = MediaType.MULTIPART_FORM_DATA),
        ).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    }

    @Test
    fun `another tenant's file is not found, for reading, downloading or adding a version`() {
        val created = upload(aSession(), Samples.png()).body()
        val fileId = created["fileId"]
        val other = registry.aTestTenant(transactions, settings, "other", mfaRequired = false)
        val otherSession = aSession(other)

        listOf("/api/v1/files/$fileId", "/api/v1/files/$fileId/content", "/api/v1/files/$fileId/versions/${created["versionId"]}/content")
            .forEach { uri ->
                assertThat(
                    mvc
                        .get()
                        .uri(uri)
                        .with(onHost(other.slug))
                        .cookie(otherSession)
                        .exchange(),
                ).describedAs(uri).hasStatus(HttpStatus.NOT_FOUND)
            }
        val newVersion =
            upload(
                otherSession,
                Samples.png(),
                "/api/v1/files/$fileId/versions",
                on = other,
                headers =
                    mapOf("If-Match" to "\"1\""),
            )
        assertThat(newVersion).hasStatus(HttpStatus.NOT_FOUND)
    }
}

/** No scanner configured: uploads are refused with 503, never accepted unscanned (ADR-0029 fail closed). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScannerDownIT : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Test
    fun `without a scanner an upload is 503 with Retry-After and nothing is stored`() {
        val tenant = registry.aTestTenant(transactions, settings, "noscan", mfaRequired = false)
        val password = aPassword()
        val session = mvc.signIn(tenant, accounts.anActiveUser(transactions, tenant, password).email, password)

        val result =
            mvc
                .post()
                .uri("/api/v1/files?purpose=${TestImagePurpose.KEY}")
                .with(onHost(tenant.slug))
                .with(sendingCsrf(mvc.csrfToken(tenant), session))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(Samples.png())
                .exchange()

        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(result.response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("5")
        assertThat(result.response.contentAsString).contains("SCAN_UNAVAILABLE")
        assertThat(TestObjectStorage.appClient().listObjectsV2 { it.bucket("work").prefix("t/${tenant.id}/") }.contents()).isEmpty()
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun storage(registry: DynamicPropertyRegistry) {
            registry.add("memo.storage.endpoint") { TestObjectStorage.endpoint.toString() }
            registry.add("memo.storage.access-key") { TestObjectStorage.appKey }
            registry.add("memo.storage.secret-key") { TestObjectStorage.appSecret }
        }
    }
}
