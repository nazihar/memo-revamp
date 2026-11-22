package com.memo.memo

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.files.Files
import com.memo.files.RenditionKind
import com.memo.files.internal.RenditionRetry
import com.memo.files.internal.RenditionScheduler
import com.memo.files.internal.RenditionWorker
import com.memo.memo.internal.MemoAttachmentPurpose
import com.memo.testsupport.Latency
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.Samples
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * The server-side budgets of design E02 §9 that need no document server: the attachment list of a memo with 50
 * attachments, and the comparison of two versions with about 1 MB of text each. Budgets have 2x headroom for shared CI
 * runners; the p95 values are printed for the epic log. Rendition tasks are run by hand (test contexts share the scheduler
 * table).
 */
class DocumentsPerformanceIT : MemoIntegrationTest() {
    @Autowired internal lateinit var worker: RenditionWorker

    @Autowired internal lateinit var scheduling: RenditionScheduler

    @Autowired lateinit var files: Files

    @BeforeEach
    fun noJobs() {
        scheduling.jobs = false
    }

    @AfterEach
    fun jobsAgain() {
        scheduling.jobs = true
    }

    @Test
    fun `the attachment list of a memo with 50 attachments`() {
        val alice = person()
        val bob = person()
        val memo = aDraft(alice)
        // Uploads are limited to 30 a minute per user: the initiator and a co-author share the 50.
        assertThat(put("/api/v1/memos/$memo/coauthors", alice.session, """{"userIds":["${bob.id}"]}""", null)).hasStatusOk()
        repeat(MemoAttachmentPurpose.MAX_COUNT) { i ->
            val who = if (i % 2 == 0) alice else bob
            val uri = "/api/v1/files?purpose=${MemoAttachmentPurpose.KEY}&owner=$memo"
            assertThat(upload(who.session, Samples.pdf(), uri, "quotation-$i.pdf")).hasStatus(HttpStatus.CREATED)
        }
        val uri = "/api/v1/memos/$memo/attachments"
        assertThat(read(uri, alice.session).json().size()).isEqualTo(MemoAttachmentPurpose.MAX_COUNT)

        val p95 = p95 { assertThat(read(uri, alice.session)).hasStatusOk() }
        println("DocumentsPerformanceIT attachment list (50) p95: $p95 ms")
        assertThat(p95).isLessThan(LIST_BUDGET_MS * 2)
    }

    @Test
    fun `comparing two versions with about 1 MB of text each`() {
        val alice = person()
        val memo = aDraft(alice)
        uploadDocument(memo, alice.session, Samples.docx("<doc>first</doc>"), "large-budget.docx")
        uploadDocument(memo, alice.session, Samples.docx("<doc>second</doc>"), "large-budget-v2.docx")
        val fileId = get(memo, alice.session).json()["document"]["fileId"].asString()
        build(fileId, alice)
        val uri = "/api/v1/memos/$memo/files/$fileId/compare?from=1&to=2"
        assertThat(read(uri, alice.session).json()["to"]["text"].asString().length).isGreaterThan(ONE_MB_CHARS)

        val p95 = p95 { assertThat(read(uri, alice.session)).hasStatusOk() }
        println("DocumentsPerformanceIT compare (2 x 1 MB) p95: $p95 ms")
        assertThat(p95).isLessThan(COMPARE_BUDGET_MS * 2)
    }

    /** Builds the text of every version of [fileId], as the rendition task would. */
    private fun build(
        fileId: String,
        owner: Person,
    ) {
        val versions = asUser(owner.account) { files.versions(UUID.fromString(fileId), 10, null) }
        versions.forEach { version ->
            repeat(RenditionWorker.MAX_ATTEMPTS) {
                try {
                    ActorContext.asSystem("files-rendition") {
                        TenantContext.call(tenant.id) { worker.run(version.id, RenditionKind.TEXT) }
                    }
                    return@forEach
                } catch (_: RenditionRetry) {
                    // As the scheduler would: again.
                }
            }
        }
    }

    private fun p95(call: () -> Unit): Long = Latency.p95(WARM_UP, RUNS, call = call)

    private companion object {
        const val LIST_BUDGET_MS = 50L
        const val COMPARE_BUDGET_MS = 300L
        const val ONE_MB_CHARS = 1_000_000
        const val WARM_UP = 5
        const val RUNS = 40
    }
}
