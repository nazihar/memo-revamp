package com.memo.files

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.internal.RejectionReason
import com.memo.files.internal.UploadRejected
import com.memo.files.internal.UploadRequest
import com.memo.files.internal.Uploads
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.FilesIntegrationTest
import com.memo.testsupport.Samples
import com.memo.testsupport.TestDocumentPurpose
import com.memo.testsupport.TestMalwareScanner
import com.memo.testsupport.TestObjectStorage
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import java.io.InputStream
import java.util.UUID

/**
 * The hostile-file corpus (TESTING_STRATEGY §3.6, Phase 1a special test): every sample is refused with its reason, within
 * limits, and nothing reaches object storage or the files tables. Samples are built at test time.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HostileFileCorpusIT : FilesIntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired internal lateinit var uploads: Uploads

    private val tenant: TestTenant by lazy { registry.aTestTenant(transactions, settings, "corpus") }

    /** Uploads `test.document` (records, the widest allow-list); a new uploader per sample keeps clear of the rate limit. */
    private fun uploader(): UserAccount = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)

    private class Sample(
        val name: String,
        val fileName: String,
        val expected: RejectionReason,
        val declaredLength: Long? = null,
        val body: () -> InputStream,
    )

    private fun sample(
        name: String,
        fileName: String,
        expected: RejectionReason,
        bytes: () -> ByteArray,
    ) = Sample(name, fileName, expected) { bytes().inputStream() }

    private val corpus =
        listOf(
            sample("EICAR", "readme.txt", RejectionReason.MALWARE) { TestMalwareScanner.eicar },
            sample("planted signature inside a zip", "reports.zip", RejectionReason.MALWARE) {
                Samples.zip("q3.txt" to "figures ${TestMalwareScanner.MARKER}".toByteArray())
            },
            sample("zip nested 12 levels", "nested.zip", RejectionReason.LIMIT_EXCEEDED) { Samples.nestedZip(12) },
            sample("zip bomb, 200 MiB of zeros", "bomb.zip", RejectionReason.LIMIT_EXCEEDED) { Samples.zipBomb(200) },
            sample("encrypted zip", "secret.zip", RejectionReason.ENCRYPTED) { Samples.encryptedZip() },
            sample("PDF/ZIP polyglot", "report.pdf", RejectionReason.MALFORMED) { Samples.pdfZipPolyglot() },
            sample("HTML named .png", "logo.png", RejectionReason.TYPE_NOT_ALLOWED) { Samples.html },
            sample("PDF named .docx", "letter.docx", RejectionReason.TYPE_MISMATCH) { Samples.pdf() },
            sample("DOCM renamed .docx", "budget.docx", RejectionReason.TYPE_NOT_ALLOWED) { Samples.docm() },
            sample("DOCX with a VBA project", "budget.docx", RejectionReason.TYPE_NOT_ALLOWED) { Samples.docxWithVba() },
            sample("ODT with Basic macros", "notes.odt", RejectionReason.TYPE_NOT_ALLOWED) { Samples.odf(macros = true) },
            sample("legacy OLE2 document", "minutes.doc", RejectionReason.TYPE_NOT_ALLOWED) { Samples.ole2 },
            sample("password-protected PDF", "salaries.pdf", RejectionReason.ENCRYPTED) { Samples.pdf(userPassword = "secret") },
            sample("malformed PDF", "broken.pdf", RejectionReason.MALFORMED) { Samples.malformedPdf },
            sample("PDF with 100,000 pages", "huge.pdf", RejectionReason.LIMIT_EXCEEDED) { Samples.pdf(pages = 100_000) },
            sample("empty file", "empty.pdf", RejectionReason.TYPE_NOT_ALLOWED) { ByteArray(0) },
            Sample("51 MiB stream without a declared length", "big.zip", RejectionReason.TOO_LARGE) { ZeroStream(51L shl 20) },
        )

    @TestFactory
    fun corpus(): List<DynamicTest> =
        corpus.map { sample ->
            DynamicTest.dynamicTest(sample.name) {
                val rejected =
                    runCatching {
                        ActorContext.call(ActorScope(Actor.User(uploader().id.value), null)) {
                            TenantContext.call(tenant.id) {
                                uploads.upload(
                                    UploadRequest(
                                        TestDocumentPurpose.KEY,
                                        null,
                                        null,
                                        null,
                                        sample.fileName,
                                        sample.declaredLength,
                                        UUID.randomUUID().toString(),
                                        sample.body(),
                                    ),
                                )
                            }
                        }
                    }.exceptionOrNull()
                assertThat(rejected).describedAs(sample.name).isInstanceOf(UploadRejected::class.java)
                assertThat((rejected as UploadRejected).reason).describedAs("${sample.name}: ${rejected.detail}").isEqualTo(sample.expected)
            }
        }

    @AfterAll
    fun nothingWasStored() {
        val keys =
            listOf("records", "work").flatMap { bucket ->
                TestObjectStorage.appClient().listObjectsV2 { it.bucket(bucket).prefix("t/${tenant.id}/") }.contents()
            }
        assertThat(keys).isEmpty()
        assertThat(AppRoleJdbc.inTransaction(tenant.id) { it.long("select count(*) from files_version") }).isZero()
        assertThat(AppRoleJdbc.inTransaction(tenant.id) { it.long("select count(*) from files_pending_upload") }).isZero()
    }

    /** [size] zero bytes, generated as they are read. */
    private class ZeroStream(
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int = if (remaining-- > 0) 0 else -1

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (remaining <= 0) return -1
            val n = minOf(len.toLong(), remaining).toInt()
            b.fill(0, off, off + n)
            remaining -= n
            return n
        }
    }
}
