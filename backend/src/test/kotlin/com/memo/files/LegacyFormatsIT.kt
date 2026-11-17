package com.memo.files

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.ratelimit.RateLimitedException
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.internal.FileStore
import com.memo.files.internal.RejectionReason
import com.memo.files.internal.UploadRejected
import com.memo.files.internal.UploadRequest
import com.memo.files.internal.Uploads
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.imports.internal.ImportIntakePurpose
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.Audit
import com.memo.testsupport.FilesIntegrationTest
import com.memo.testsupport.ImportSupport
import com.memo.testsupport.LegacySamples
import com.memo.testsupport.Samples
import com.memo.testsupport.TestDocumentPurpose
import com.memo.testsupport.TestImagePurpose
import com.memo.testsupport.TestObjectStorage
import com.memo.testsupport.TestTenant
import com.memo.testsupport.TestTikaServer
import com.memo.testsupport.UploadRejections
import com.memo.testsupport.aClientIp
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.csrfToken
import com.memo.testsupport.fromIp
import com.memo.testsupport.onHost
import com.memo.testsupport.sendingCsrf
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.util.UUID

/**
 * Legacy formats in the upload pipeline (ADR-0055, test plan E06 T2 `LegacyFormatsIT`) against PostgreSQL, SeaweedFS,
 * clamd with the shared settings (`AlertOLE2Macros`, `AlertEncrypted`) and a stand-in for the Tika server's detection:
 * accepted only by a purpose that accepts legacy formats, refused everywhere else; an Outlook message, an encrypted or a
 * macro document refused with its reason; no detection, no upload; and the intake's own upload rate.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LegacyFormatsIT : FilesIntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired internal lateinit var uploads: Uploads

    @Autowired internal lateinit var store: FileStore

    @Autowired lateinit var imports: ImportSupport

    private val tenant: TestTenant by lazy { registry.aTestTenant(transactions, settings, "legacy", mfaRequired = false) }

    /**
     * A fresh uploader per test: upload rates are per user. Admins may upload test documents; originals need `memo.import`
     * and a registered path of an open batch (the real `imports.intake` purpose).
     */
    private fun anUploader(): UserAccount =
        accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true).also { imports.grant(tenant, it.id.value) }

    private val batch: UUID by lazy { imports.batch(tenant, anUploader().id.value) }

    /** A newly registered original: the owner of an intake upload. */
    private fun anOriginal(uploader: UserAccount): String = imports.intakeOwner(tenant, uploader.id.value, batch)

    private fun upload(
        bytes: ByteArray,
        name: String,
        uploader: UserAccount = anUploader(),
        purpose: String = ImportIntakePurpose.KEY,
        declaredLength: Long = bytes.size.toLong(),
        key: String = UUID.randomUUID().toString(),
        owner: String = if (purpose == ImportIntakePurpose.KEY) anOriginal(uploader) else "intake-1",
    ): UUID =
        ActorContext.call(ActorScope(Actor.User(uploader.id.value), null)) {
            TenantContext.call(tenant.id) {
                uploads.upload(UploadRequest(purpose, owner, null, null, name, declaredLength, key, bytes.inputStream()))
            }
        }

    private fun rejection(block: () -> Any): UploadRejected = catchThrowableOfType(UploadRejected::class.java) { block() }

    private fun mediaType(versionId: UUID) = transactions.readInTenant(tenant.id) { store.version(versionId)!!.mediaType }

    private fun workObjects(): Int =
        TestObjectStorage
            .appClient()
            .listObjectsV2 { it.bucket("work").prefix("t/${tenant.id}/") }
            .contents()
            .size

    @Test
    fun `macro-free legacy documents are stored for the intake and refused for every other purpose`() {
        mapOf(
            "minutes.doc" to (LegacySamples.doc() to FileType.DOC),
            "ledger.xls" to (LegacySamples.xls() to FileType.XLS),
            "letter.rtf" to (LegacySamples.rtf to FileType.RTF),
            "scan.tiff" to (LegacySamples.tiff() to FileType.TIFF),
        ).forEach { (name, sample) ->
            val (bytes, type) = sample
            assertThat(mediaType(upload(bytes, name))).describedAs(name).isEqualTo(type.mediaType)

            val asked = TestTikaServer.requests.get()
            val refused = rejection { upload(bytes, name, purpose = TestDocumentPurpose.KEY) }
            assertThat(refused.reason).describedAs(name).isEqualTo(RejectionReason.TYPE_NOT_ALLOWED)
            // Purposes without legacy formats never ask the Tika server: an OLE2 file stays a container there.
            assertThat(TestTikaServer.requests.get()).isEqualTo(asked)
        }
        assertThat(rejection { upload(LegacySamples.doc(), "minutes.doc", purpose = TestDocumentPurpose.KEY) }.detail["detectedType"])
            .isEqualTo(FileType.OLE2.mediaType)
        assertThat(rejection { upload(LegacySamples.xls(), "ledger.doc") }.reason).isEqualTo(RejectionReason.TYPE_MISMATCH)
    }

    @Test
    fun `an Outlook message is not allowed, and the owner learns why`() {
        val key = "imp-${UUID.randomUUID()}"
        val before = workObjects()
        val uploader = anUploader()
        val owner = anOriginal(uploader)

        val refused = rejection { upload(LegacySamples.msg(), "minutes.msg", uploader, key = key, owner = owner) }

        assertThat(refused.reason).isEqualTo(RejectionReason.TYPE_NOT_ALLOWED)
        assertThat(refused.detail["detectedType"]).isEqualTo("application/vnd.ms-outlook")
        assertThat(workObjects()).isEqualTo(before)
        val event = UploadRejections.seen.single { it.idempotencyKey == key }
        assertThat(event.tenantId).isEqualTo(tenant.id.value)
        assertThat(event.purposeKey).isEqualTo(ImportIntakePurpose.KEY)
        assertThat(event.ownerId).isEqualTo(owner)
        assertThat(event.reasonCode).isEqualTo("TYPE_NOT_ALLOWED")
        assertThat(event.fileId).isNull()
        assertThat(Audit.entries(tenant.id).last { it.action == "file.upload.rejected" }.canonical).contains("vnd.ms-outlook")
    }

    @Test
    fun `an encrypted workbook is refused as encrypted by clamd before the type is asked`() {
        val key = "imp-${UUID.randomUUID()}"
        val asked = TestTikaServer.requests.get()

        val refused = rejection { upload(LegacySamples.xls(password = "secret"), "ledger.xls", key = key) }

        assertThat(refused.reason).isEqualTo(RejectionReason.ENCRYPTED)
        assertThat(refused.detail["signature"]).isEqualTo("Heuristics.Encrypted.OLE2")
        assertThat(TestTikaServer.requests.get()).isEqualTo(asked)
        val event = UploadRejections.seen.single { it.idempotencyKey == key }
        assertThat(event.reasonCode).isEqualTo("ENCRYPTED")
        assertThat(event.signatureName).isEqualTo("Heuristics.Encrypted.OLE2")
    }

    @Test
    fun `a document with a VBA project is refused as malware with the signature`() {
        val refused = rejection { upload(LegacySamples.docWithMacros(), "minutes.doc") }

        assertThat(refused.reason).isEqualTo(RejectionReason.MALWARE)
        assertThat(refused.detail["signature"] as String).startsWith("Heuristics.OLE2.ContainsMacros")
    }

    @Test
    fun `with the Tika server unreachable an OLE2 upload fails closed with 503, and other legacy files still go in`() {
        val (importer, session) = imports.importer(mvc, tenant)
        val doc = imports.intakeOwner(tenant, importer, batch)
        val key = UUID.randomUUID().toString()
        val before = workObjects()

        TestTikaServer.down = true
        val (refused, rtf) =
            try {
                post(session, doc, LegacySamples.doc(), "minutes.doc", key) to
                    post(session, imports.intakeOwner(tenant, importer, batch), LegacySamples.rtf, "letter.rtf")
            } finally {
                TestTikaServer.down = false
            }

        assertThat(refused).hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(refused.response.getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank()
        assertThat(refused.response.contentAsString).contains("SCAN_UNAVAILABLE")
        // Unavailable is not a refusal: nothing for the owner to record, and the client may try again.
        assertThat(UploadRejections.seen.none { it.idempotencyKey == key }).isTrue()
        assertThat(rtf).hasStatus(HttpStatus.CREATED)
        assertThat(workObjects()).isEqualTo(before + 1)
        assertThat(post(session, doc, LegacySamples.doc(), "minutes.doc", key)).hasStatus(HttpStatus.CREATED)
    }

    private fun post(
        session: jakarta.servlet.http.Cookie,
        owner: String,
        bytes: ByteArray,
        name: String,
        key: String = UUID.randomUUID().toString(),
    ) = mvc
        .post()
        .uri("/api/v1/files?purpose=${ImportIntakePurpose.KEY}&owner=$owner")
        .with(onHost(tenant.slug))
        .with(fromIp(aClientIp()))
        .with(sendingCsrf(mvc.csrfToken(tenant), session))
        .header("Idempotency-Key", key)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$name\"")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .content(bytes)
        .exchange()

    @Test
    fun `the intake has its own upload rate of 600 a minute, and other purposes keep 30`() {
        val uploader = anUploader()
        // Refused by their declared length before anything is read: each still takes a token.
        val intake = ImportIntakePurpose.MAX_BYTES + 1
        val owner = anOriginal(uploader)
        assertThat(takenUntilLimited { upload(Samples.text, "a.txt", uploader, declaredLength = intake, owner = owner) })
            .isBetween(INTAKE_RATE.toLong(), INTAKE_RATE + refilledIntake)

        // The same person's other uploads are untouched by the intake, and still stop at 30.
        val image = Samples.png()
        assertThat(mediaType(upload(image, "logo.png", uploader, purpose = TestImagePurpose.KEY))).isEqualTo("image/png")
        val other = 3L shl 20
        assertThat(takenUntilLimited { upload(image, "logo.png", uploader, purpose = TestImagePurpose.KEY, declaredLength = other) } + 1)
            .isBetween(UPLOAD_RATE.toLong(), UPLOAD_RATE + refilledOther)
    }

    /** What came back to the intake's bucket (10 a second) and the general one (one every 2 s) while counting. */
    private var refilledIntake = 0L
    private var refilledOther = 0L

    /**
     * Uploads refused as too large until one is rate limited: how many took a token. The buckets refill smoothly, so the
     * caller allows for what came back meanwhile ([refilledIntake], [refilledOther]).
     */
    private fun takenUntilLimited(upload: () -> Any): Long {
        val started = System.nanoTime()
        var taken = 0L
        while (taken <= MAX_TRIES) {
            val refused = runCatching { upload() }.exceptionOrNull()
            if (refused is RateLimitedException) break
            assertThat((refused as UploadRejected).reason).isEqualTo(RejectionReason.TOO_LARGE)
            taken++
        }
        val elapsed = System.nanoTime() - started
        refilledIntake = elapsed / NANOS_PER_TENTH_SECOND + 1
        refilledOther = elapsed / (NANOS_PER_TENTH_SECOND * TENTHS_PER_OTHER_TOKEN) + 1
        return taken
    }

    private companion object {
        const val INTAKE_RATE = 600
        const val UPLOAD_RATE = 30
        const val MAX_TRIES = 5_000
        const val NANOS_PER_TENTH_SECOND = 100_000_000L
        const val TENTHS_PER_OTHER_TOKEN = 20L
    }
}
