package com.memo.files

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.internal.FileStore
import com.memo.files.internal.UploadRequest
import com.memo.files.internal.Uploads
import com.memo.identity.UserAccounts
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.Audit
import com.memo.testsupport.FilesIntegrationTest
import com.memo.testsupport.Samples
import com.memo.testsupport.TestDocumentPurpose
import com.memo.testsupport.TestImagePurpose
import com.memo.testsupport.TestObjectStorage
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.onHost
import com.memo.testsupport.signIn
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import software.amazon.awssdk.core.sync.RequestBody
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** Streamed downloads with ranges, headers and open auditing (design §5, ADR-0029; test plan T6). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DownloadIT : FilesIntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired internal lateinit var uploads: Uploads

    @Autowired internal lateinit var store: FileStore

    private val tenant: TestTenant by lazy { registry.aTestTenant(transactions, settings, "dl", mfaRequired = false) }

    /** A zip holding ~1 MiB of random bytes stored as is: several ciphertext segments of content that never repeats. */
    private val content: ByteArray by lazy {
        val payload = Random(42).nextBytes(1_000_000)
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(
                ZipEntry("data.bin").apply {
                    method = ZipEntry.STORED
                    size = payload.size.toLong()
                    crc = CRC32().apply { update(payload) }.value
                },
            )
            zip.write(payload)
            zip.closeEntry()
        }
        out.toByteArray()
    }

    private val zipFile: Pair<UUID, UUID> by lazy { store(content, "data set.zip") }

    private fun store(
        bytes: ByteArray,
        name: String,
        purpose: String = TestDocumentPurpose.KEY,
    ): Pair<UUID, UUID> {
        val uploader = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        val versionId =
            ActorContext.call(ActorScope(Actor.User(uploader.id.value), null)) {
                TenantContext.call(tenant.id) {
                    uploads.upload(
                        UploadRequest(
                            purpose,
                            null,
                            null,
                            null,
                            name,
                            bytes.size.toLong(),
                            UUID.randomUUID().toString(),
                            bytes.inputStream(),
                        ),
                    )
                }
            }
        return transactions.readInTenant(tenant.id) { store.version(versionId)!!.fileId } to versionId
    }

    private fun aSession(): Cookie {
        val password = aPassword()
        return mvc.signIn(tenant, accounts.anActiveUser(transactions, tenant, password).email, password)
    }

    private val session: Cookie by lazy { aSession() }

    private fun get(
        uri: String,
        headers: Map<String, String> = emptyMap(),
        cookie: Cookie = session,
        head: Boolean = false,
    ): MvcTestResult =
        (if (head) mvc.head() else mvc.get())
            .uri(uri)
            .with(onHost(tenant.slug))
            .cookie(cookie)
            .headers { h -> headers.forEach { (name, value) -> h.add(name, value) } }
            .exchange()

    private val contentUri get() = "/api/v1/files/${zipFile.first}/content"

    private fun opens(version: UUID) = Audit.entries(tenant.id).count { it.action == "file.opened" && it.objectId == version.toString() }

    @Test
    fun `the whole file streams back with strict headers`() {
        val result = get(contentUri)

        assertThat(result).hasStatusOk()
        assertThat(result.response.contentAsByteArray).isEqualTo(content)
        val r = result.response
        assertThat(r.contentType).isEqualTo("application/zip")
        assertThat(r.getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo(content.size.toString())
        assertThat(r.getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment").contains("filename*=UTF-8''data%20set.zip")
        assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(r.getHeader("Content-Security-Policy")).contains("sandbox").contains("default-src 'none'")
        assertThat(r.getHeader("Cross-Origin-Resource-Policy")).isEqualTo("same-origin")
        assertThat(r.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, no-store")
        assertThat(r.getHeader(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes")
        assertThat(r.getHeader(HttpHeaders.ETAG)).isNotBlank()
    }

    @Test
    fun `single ranges anywhere give 206 with exactly those bytes`() {
        val size = content.size
        val segment = 64 * 1024
        listOf(
            "bytes=0-99" to (0 until 100),
            "bytes=${segment - 20}-${segment + 30}" to (segment - 20..segment + 30),
            "bytes=${3 * segment}-${5 * segment + 7}" to (3 * segment..5 * segment + 7),
            "bytes=-100" to (size - 100 until size),
            "bytes=${size - 10}-" to (size - 10 until size),
            "bytes=${size - 5}-${size + 100}" to (size - 5 until size),
        ).forEach { (range, indices) ->
            val result = get(contentUri, mapOf(HttpHeaders.RANGE to range))
            assertThat(result).describedAs(range).hasStatus(HttpStatus.PARTIAL_CONTENT)
            assertThat(result.response.contentAsByteArray).describedAs(range).isEqualTo(content.sliceArray(indices))
            assertThat(result.response.getHeader(HttpHeaders.CONTENT_RANGE))
                .isEqualTo("bytes ${indices.first}-${indices.last}/$size")
        }
    }

    @Test
    fun `an unsatisfiable range is 416, several ranges or a stale If-Range give the whole file`() {
        val size = content.size
        val unsatisfiable = get(contentUri, mapOf(HttpHeaders.RANGE to "bytes=$size-"))
        assertThat(unsatisfiable).hasStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
        assertThat(unsatisfiable.response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */$size")

        assertThat(get(contentUri, mapOf(HttpHeaders.RANGE to "bytes=0-9,20-29"))).hasStatusOk()
        assertThat(get(contentUri, mapOf(HttpHeaders.RANGE to "items=0-9"))).hasStatusOk()

        val etag = get(contentUri, mapOf(HttpHeaders.RANGE to "bytes=0-0")).response.getHeader(HttpHeaders.ETAG)!!
        assertThat(get(contentUri, mapOf(HttpHeaders.RANGE to "bytes=0-9", HttpHeaders.IF_RANGE to etag)))
            .hasStatus(HttpStatus.PARTIAL_CONTENT)
        assertThat(get(contentUri, mapOf(HttpHeaders.RANGE to "bytes=0-9", HttpHeaders.IF_RANGE to "\"stale\""))).hasStatusOk()
        assertThat(get(contentUri, mapOf(HttpHeaders.IF_NONE_MATCH to etag))).hasStatus(HttpStatus.NOT_MODIFIED)

        val head = get(contentUri, head = true)
        assertThat(head).hasStatusOk()
        assertThat(head.response.getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo(size.toString())
        assertThat(head.response.contentAsByteArray).isEmpty()
    }

    @Test
    fun `an open is audited once per session and version in the window, whatever the ranges`() {
        val (fileId, versionId) = store(Samples.pdf(pages = 3), "minutes.pdf")
        val uri = "/api/v1/files/$fileId/content"
        val first = aSession()

        get(uri, mapOf(HttpHeaders.RANGE to "bytes=100-199"), first)
        get(uri, cookie = first)
        assertThat(opens(versionId)).isEqualTo(1)

        get(uri, cookie = aSession())
        assertThat(opens(versionId)).isEqualTo(2)

        val (imageFile, imageVersion) = store(Samples.png(), "logo.png", TestImagePurpose.KEY)
        get("/api/v1/files/$imageFile/content")
        assertThat(opens(imageVersion)).isZero()
    }

    @Test
    fun `safe types the purpose allows are inline, and older versions stay readable by id`() {
        val (fileId, v1) = store(Samples.pdf(), "a.pdf")
        assertThat(get("/api/v1/files/$fileId/content").response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline")

        assertThat(get("/api/v1/files/$fileId/versions/$v1/content")).hasStatusOk()
        assertThat(get("/api/v1/files/${zipFile.first}/versions/$v1/content")).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(get("/api/v1/files/${UUID.randomUUID()}/content")).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a newer object written under a record's key does not change what is served`() {
        val (fileId, versionId) = store(Samples.pdf(pages = 2), "kept.pdf")
        val expected = get("/api/v1/files/$fileId/content").response.contentAsByteArray
        val key =
            AppRoleJdbc.inTransaction(tenant.id) { c ->
                c.prepareStatement("select object_key from files_version where id = ?").use { st ->
                    st.setObject(1, versionId)
                    st.executeQuery().use {
                        it.next()
                        it.getString(1)
                    }
                }
            }

        TestObjectStorage.appClient().putObject({
            it.bucket("records").key(key).contentMD5(md5("forged".toByteArray()))
        }, RequestBody.fromString("forged"))

        assertThat(get("/api/v1/files/$fileId/content", cookie = aSession()).response.contentAsByteArray).isEqualTo(expected)
    }

    @Test
    fun `tampered content is never served as the file`() {
        val (fileId, versionId) = store(Samples.png(), "logo.png", TestImagePurpose.KEY)
        val key =
            AppRoleJdbc.inTransaction(tenant.id) { c ->
                c.prepareStatement("select object_key from files_version where id = ?").use { st ->
                    st.setObject(1, versionId)
                    st.executeQuery().use {
                        it.next()
                        it.getString(1)
                    }
                }
            }
        val app = TestObjectStorage.appClient()
        val stored = app.getObjectAsBytes { it.bucket("work").key(key) }.asByteArray()
        val tampered = stored.copyOf().also { it[it.size - 20] = (it[it.size - 20] + 1).toByte() }
        app.putObject({ it.bucket("work").key(key).contentMD5(md5(tampered)) }, RequestBody.fromBytes(tampered))

        val result = runCatching { get("/api/v1/files/$fileId/content") }

        result.onSuccess { assertThat(it.response.contentAsByteArray).isNotEqualTo(Samples.png()) }
        assertThat(Audit.entries(tenant.id).map { it.action }).contains("file.integrity.failed")
    }

    /** The Content-MD5 transfer checksum that object-lock PUTs need (as in ObjectStore), to forge objects in tests. */
    private fun md5(bytes: ByteArray): String {
        // nosemgrep: kotlin.lang.security.use-of-md5.use-of-md5
        val digest = java.security.MessageDigest.getInstance("MD5")
        val encoder = java.util.Base64.getEncoder()
        return encoder.encodeToString(digest.digest(bytes))
    }
}
