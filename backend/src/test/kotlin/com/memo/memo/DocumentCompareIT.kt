package com.memo.memo

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.files.RenditionKind
import com.memo.files.internal.RenditionRetry
import com.memo.files.internal.RenditionScheduler
import com.memo.files.internal.RenditionWorker
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Samples
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * Comparing two versions of a memo's document (design E02 §3; test plan T6): the texts with who uploaded each version,
 * both reads audited, the state while a text is still being built, and nothing for people who may not see the memo.
 * Rendition tasks are run here by hand (test contexts share the scheduler table).
 */
class DocumentCompareIT : MemoIntegrationTest() {
    @Autowired internal lateinit var worker: RenditionWorker

    @Autowired internal lateinit var scheduling: RenditionScheduler

    @BeforeEach
    fun noJobs() {
        scheduling.jobs = false
    }

    @AfterEach
    fun jobsAgain() {
        scheduling.jobs = true
    }

    @Test
    fun `the initiator compares two versions of the document, and both reads are audited`() {
        val alice = person()
        val stranger = person()
        val memo = aDraft(alice)
        uploadDocument(memo, alice.session, Samples.docx("<doc>first</doc>"), "budget.docx")
        uploadDocument(memo, alice.session, Samples.docx("<doc>second</doc>"), "budget-v2.docx")
        val document = get(memo, alice.session).json()["document"]
        val fileId = document["fileId"].asString()
        val uri = "/api/v1/memos/$memo/files/$fileId/compare?from=1&to=2"

        // Before the texts are built: the versions and their state.
        val pending = read(uri, alice.session)
        assertThat(pending).hasStatusOk()
        assertThat(pending.json()["from"]["state"].asString()).isEqualTo("PENDING")
        assertThat(pending.json()["from"].absent("text")).isTrue()

        build(fileId, alice)
        val compared = read(uri, alice.session).json()
        assertThat(compared["from"]["number"].asInt()).isEqualTo(1)
        assertThat(compared["from"]["text"].asString()).isEqualTo("Text of budget.docx")
        assertThat(compared["to"]["text"].asString()).isEqualTo("Text of budget-v2.docx")
        assertThat(compared["to"]["uploadedBy"]["id"].asString()).isEqualTo(alice.id.toString())
        assertThat(compared["to"]["sha256"].asString()).hasSize(64)
        Audit.assertAudited(tenant.id, "file.opened", compared["to"]["versionId"].asString())

        assertThat(read(uri, stranger.session)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(read("/api/v1/memos/$memo/files/${UUID.randomUUID()}/compare?from=1&to=2", alice.session))
            .hasStatus(HttpStatus.NOT_FOUND)
        assertThat(read("/api/v1/memos/$memo/files/$fileId/compare?from=1&to=7", alice.session)).hasStatus(HttpStatus.NOT_FOUND)
    }

    /** Builds the text of every version of [fileId], as the rendition task would. */
    private fun build(
        fileId: String,
        owner: com.memo.testsupport.Person,
    ) {
        val versions = asUser(owner.account) { files.versions(UUID.fromString(fileId), 10, null) }
        versions.forEach { version ->
            repeat(RenditionWorker.MAX_ATTEMPTS) {
                try {
                    ActorContext.asSystem(
                        "files-rendition",
                    ) { TenantContext.call(tenant.id) { worker.run(version.id, RenditionKind.TEXT) } }
                    return@forEach
                } catch (_: RenditionRetry) {
                    // As the scheduler would: again.
                }
            }
        }
    }

    @Autowired lateinit var files: com.memo.files.Files
}
