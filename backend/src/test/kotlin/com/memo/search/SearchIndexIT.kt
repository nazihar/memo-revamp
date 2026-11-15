package com.memo.search

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.files.Files
import com.memo.files.RenditionKind
import com.memo.files.internal.RenditionRetry
import com.memo.files.internal.RenditionScheduler
import com.memo.files.internal.RenditionWorker
import com.memo.memo.internal.MemoAttachmentPurpose
import com.memo.search.internal.ReaderScope
import com.memo.search.internal.SearchCandidate
import com.memo.search.internal.SearchCriteria
import com.memo.search.internal.SearchFinder
import com.memo.search.internal.SearchIndexing
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.Samples
import com.memo.testsupport.TestDatabase
import com.memo.testsupport.sendJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import java.sql.DriverManager
import java.util.UUID

/**
 * The search index (ADR-0054; test plan E05 T2): memo and note rows written with their change, document text split into
 * chunks by the job (a phrase near the end of a long text is found), a new document version replacing the old text, an
 * attachment's level and removal followed, and the backfill. Rendition and chunk tasks are run here by hand (test
 * contexts share the scheduler table).
 */
class SearchIndexIT : MemoIntegrationTest() {
    @Autowired lateinit var finder: SearchFinder

    @Autowired lateinit var indexing: SearchIndexing

    @Autowired lateinit var files: Files

    @Autowired internal lateinit var worker: RenditionWorker

    @Autowired internal lateinit var scheduling: RenditionScheduler

    @BeforeEach
    fun noJobs() {
        scheduling.jobs = false
        indexing.jobs = false
    }

    @AfterEach
    fun jobsAgain() {
        scheduling.jobs = true
        indexing.jobs = true
    }

    @Test
    fun `a memo's details and posted notes are indexed with the change, and edits replace the old words`() {
        val alice = person()
        val memo = aDraft(alice)
        assertThat(rename(memo, alice, "Zebra crossing budget")).hasStatusOk()
        assertThat(search(alice, "zebra").map { it.memoId }).containsExactly(UUID.fromString(memo))
        assertThat(search(alice, "zebra").single().inDetails).isTrue()

        val note = """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"The pelican report is late."}]}]}"""
        assertThat(post("/api/v1/memos/$memo/notes", alice.session, """{"body":$note}""").response.status).isLessThan(300)
        assertThat(search(alice, "pelican").single().inNotes).isTrue()

        rename(memo, alice, "Giraffe budget")
        assertThat(search(alice, "zebra")).isEmpty()
        assertThat(search(alice, "giraffe")).hasSize(1)
        // Another person reads nothing of it.
        assertThat(search(person(), "giraffe")).isEmpty()
    }

    @Test
    fun `a long document is split into chunks, and words near its end are found`() {
        val alice = person()
        val memo = aDraft(alice)
        uploadDocument(memo, alice.session, Samples.docx("<doc>long</doc>"), "large-annual.docx")
        val fileId = UUID.fromString(get(memo, alice.session).json()["document"]["fileId"].asString())
        chunks(fileId, alice)

        assertThat(owner("select count(*) from search_chunk where file_id = '$fileId'")).isGreaterThan(1L)
        // The 18,000th (last) line names the file: it is in the last chunk.
        val found = search(alice, "18000")
        assertThat(found.map { it.memoId }).containsExactly(UUID.fromString(memo))
        assertThat(found.single().inDocuments).isTrue()
    }

    @Test
    fun `a new version of the document replaces the text of the old one`() {
        val alice = person()
        val memo = aDraft(alice)
        uploadDocument(memo, alice.session, Samples.docx("<doc>one</doc>"), "otter report.docx")
        val fileId = UUID.fromString(get(memo, alice.session).json()["document"]["fileId"].asString())
        chunks(fileId, alice)
        assertThat(search(alice, "otter")).hasSize(1)

        uploadDocument(memo, alice.session, Samples.docx("<doc>two</doc>"), "walrus report.docx")
        chunks(fileId, alice)
        assertThat(search(alice, "otter")).isEmpty()
        assertThat(search(alice, "walrus").single().inDocuments).isTrue()
    }

    @Test
    fun `an attachment raised above a reader's clearance stops matching for them, and a removed one is gone`() {
        val alice = person(clearance = 1)
        val memo = aDraft(alice)
        upload(alice.session, Samples.docx("<doc>annex</doc>"), attachmentsOf(memo), "quokka annex.docx")
        val attachment = read("/api/v1/memos/$memo/attachments", alice.session).json()[0]
        val fileId = UUID.fromString(attachment["fileId"].asString())
        chunks(fileId, alice)
        assertThat(search(alice, "quokka", rank = 0).single().inDocuments).isTrue()
        // The API names the attachment it was found in.
        val foundIn = read("/api/v1/search?q=quokka", alice.session).json()["results"][0]["foundIn"]
        assertThat(foundIn["kind"].asString()).isEqualTo("ATTACHMENT")
        assertThat(foundIn["name"].asString()).isEqualTo("quokka annex.docx")

        val id = attachment["id"].asString()
        val raised =
            mvc.sendJson(
                HttpMethod.PATCH,
                tenant,
                "/api/v1/memos/$memo/attachments/$id",
                alice.session,
                """{"classificationId":"${levels()[1].id}"}""",
                mapOf("If-Match" to "\"${attachment["version"].asLong()}\""),
            )
        assertThat(raised).hasStatusOk()
        assertThat(search(alice, "quokka", rank = 0)).isEmpty()
        assertThat(search(alice, "quokka", rank = 1)).hasSize(1)

        assertThat(
            mvc.sendJson(HttpMethod.DELETE, tenant, "/api/v1/memos/$memo/attachments/$id", alice.session, "", emptyMap()).response.status,
        ).isLessThan(300)
        assertThat(search(alice, "quokka", rank = 1)).isEmpty()
        assertThat(owner("select count(*) from search_chunk where file_id = '$fileId'")).isZero()
    }

    @Test
    fun `the backfill indexes memos that have no search row, once`() {
        val alice = person()
        val memo = aDraft(alice)
        val note = """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"About the narwhal."}]}]}"""
        post("/api/v1/memos/$memo/notes", alice.session, """{"body":$note}""")
        // As before E05: no search rows.
        owner("delete from search_note where memo_id = '$memo'")
        owner("delete from search_memo where memo_id = '$memo'")
        assertThat(search(alice, "narwhal")).isEmpty()

        assertThat(asUser(alice.account) { indexing.backfill() }).isEqualTo(1)
        assertThat(search(alice, "narwhal").single().inNotes).isTrue()
        assertThat(asUser(alice.account) { indexing.backfill() }).isZero()
    }

    private fun search(
        who: Person,
        text: String,
        rank: Int = 0,
    ): List<SearchCandidate> =
        asUser(who.account) {
            finder.find(SearchCriteria(text = text), ReaderScope(who.id, rank, false, emptySet()))
        }

    private fun rename(
        memo: String,
        who: Person,
        subject: String,
    ) = put(
        "/api/v1/memos/$memo",
        who.session,
        """{"subject":"$subject","typeId":"${generalType(who.session)}"}""",
        get(memo, who.session).json()["version"].asLong(),
    )

    private fun attachmentsOf(memo: String) = "/api/v1/files?purpose=${MemoAttachmentPurpose.KEY}&owner=$memo"

    /** Builds the TEXT rendition of [fileId]'s current version and runs its chunk job, as the tasks would. */
    private fun chunks(
        fileId: UUID,
        who: Person,
    ) {
        val version = requireNotNull(asUser(who.account) { files.info(fileId) }?.currentVersionId)
        ActorContext.asSystem("files-rendition") {
            TenantContext.call(tenant.id) {
                repeat(RenditionWorker.MAX_ATTEMPTS) {
                    try {
                        worker.run(version, RenditionKind.TEXT)
                        return@call
                    } catch (_: RenditionRetry) {
                        // As the scheduler would: again.
                    }
                }
            }
        }
        ActorContext.asSystem("search-chunks") { TenantContext.call(tenant.id) { indexing.chunk(fileId, version) } }
    }

    /** A statement as the schema owner (the search tables grant nothing to the application role); a count, or 0. */
    private fun owner(sql: String): Long =
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use { c ->
            c.autoCommit = false
            c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                it.setString(1, tenant.id.value.toString())
                it.execute()
            }
            c.createStatement().use { st -> count(st, sql) }.also { c.commit() }
        }

    private fun count(
        st: java.sql.Statement,
        sql: String,
    ): Long = if (st.execute(sql)) st.resultSet.use { rs -> if (rs.next()) rs.getLong(1) else 0L } else 0L
}
