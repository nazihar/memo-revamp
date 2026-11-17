package com.memo.search

import com.memo.common.tenant.TenantDsl
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * `GET /search` and `GET /search/suggest` (design E05 §3; test plan E05 T3): words, phrases, OR and minus; numbers found
 * first; snippets with their matches and where they matched; facets; keyset paging; the capped count; input caps; the
 * rate limit; and an audit entry for every search.
 */
class SearchIT : MemoIntegrationTest() {
    @Autowired lateinit var tenantDsl: TenantDsl

    @Test
    fun `words, a phrase, OR and minus find the right memos, with snippets, facets and an audit entry`() {
        val alice = person()
        val a = memo(alice, "Rent schedule for floor three")
        val b = memo(alice, "Schedule of the rent review")
        val c = memo(alice, "Office rent increase")

        assertThat(ids(search(alice, q("rent schedule")))).containsExactlyInAnyOrder(a, b)
        assertThat(ids(search(alice, q("\"rent schedule\"")))).containsExactly(a)
        assertThat(ids(search(alice, q("rent -office")))).containsExactlyInAnyOrder(a, b)
        assertThat(ids(search(alice, q("floor or office")))).containsExactlyInAnyOrder(a, c)

        val page = search(alice, q("\"rent schedule\"")).json()
        val snippet = page["results"][0]["snippet"]
        assertThat((0 until snippet.size()).filter { snippet[it]["match"].asBoolean() }.map { snippet[it]["text"].asString() })
            .contains("Rent", "schedule")
        assertThat(page["results"][0]["foundIn"]["kind"].asString()).isEqualTo("DETAILS")
        assertThat(page["count"].asInt()).isEqualTo(1)
        assertThat(page["capped"].asBoolean()).isFalse()
        assertThat(page["facets"]["status"][0]["value"].asString()).isEqualTo("DRAFT")
        assertThat(page["facets"]["type"][0]["label"].asString()).isEqualTo("General memo")
        Audit.assertAudited(tenant.id, "search.performed", alice.id.toString())

        // Another person reads none of them: no result, count or facet.
        val stranger = search(person(), "q=rent").json()
        assertThat(stranger["count"].asInt()).isZero()
        assertThat(stranger["facets"]["status"].size()).isZero()
    }

    @Test
    fun `a note's words find the memo, found in the note with its author`() {
        val alice = person()
        val memo = memo(alice, "Branch generator")
        val note = """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"The pelican report is late."}]}]}"""
        assertThat(post("/api/v1/memos/$memo/notes", alice.session, """{"body":$note}""").response.status).isLessThan(300)

        val result = search(alice, "q=pelican").json()["results"][0]
        assertThat(result["memoId"].asString()).isEqualTo(memo)
        assertThat(result["foundIn"]["kind"].asString()).isEqualTo("NOTE")
        assertThat(result["foundIn"]["name"].asString()).isNotBlank()
        // Notes only, or details only.
        assertThat(ids(search(alice, "q=pelican&in=metadata"))).isEmpty()
        assertThat(ids(search(alice, "q=pelican&in=notes"))).containsExactly(memo)
    }

    @Test
    fun `a memo's number finds it first, in any spelling or by its last digits`() {
        val alice = person()
        val bob = person()
        val memo = memo(alice, "Numbered memo")
        uploadDocument(memo, alice.session)
        route(memo, alice, bob)
        assertThat(post("/api/v1/memos/$memo/submit", alice.session)).hasStatusOk()
        memo(alice, "Another memo")
        val number = get(memo, alice.session).json()["reference"].asString()

        listOf(number, number.replace('-', '/').lowercase(), number.substringAfterLast('-')).forEach { spelling ->
            val first = search(alice, q(spelling)).json()["results"][0]
            assertThat(first["memoId"].asString()).describedAs(spelling).isEqualTo(memo)
            assertThat(first["foundIn"]["kind"].asString()).describedAs(spelling).isEqualTo("NUMBER")
            assertThat(first["number"].asString()).isEqualTo(number)
        }
    }

    @Test
    fun `pages follow the cursor without repeats, and newest puts the last update first`() {
        val alice = person()
        val made = (1..5).map { memo(alice, "Kiwi orchard $it") }
        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = search(alice, "q=kiwi&limit=2" + (cursor?.let { "&cursor=$it" } ?: "")).json()
            assertThat(page["count"].asInt()).isEqualTo(5)
            seen += ids(page)
            cursor = page["cursor"]?.takeUnless { it.isNull }?.asString()
        } while (cursor != null)
        assertThat(seen).containsExactlyInAnyOrderElementsOf(made).doesNotHaveDuplicates()

        assertThat(ids(search(alice, "q=kiwi&sort=newest"))).containsExactlyElementsOf(made.reversed())
    }

    @Test
    fun `above 2,000 matches the count says so, and facets count the same set`() {
        val reader = person()
        val (type, level) =
            asUser(admin) {
                val dsl = tenantDsl.current()
                (dsl.fetchValue("select id from memo_type where key = 'general'") as UUID) to
                    (dsl.fetchValue("select id from authz_classification_level where rank = 0") as UUID)
            }
        SearchVolume(tenant.id.value, MANY, type, level, seed = "cap").generate(mapOf(reader.id to MANY))

        val page = search(reader, "q=loan").json()
        assertThat(page["count"].asInt()).isEqualTo(CAP)
        assertThat(page["capped"].asBoolean()).isTrue()
        val statuses = page["facets"]["status"]
        assertThat((0 until statuses.size()).sumOf { statuses[it]["count"].asInt() }).isEqualTo(CAP)
    }

    @Test
    fun `input beyond the caps is refused with the field that is wrong`() {
        val alice = person()
        assertThat(problems(search(alice, "q=")).single()).isEqualTo("q:REQUIRED")
        assertThat(problems(search(alice, "q=" + "a".repeat(201))).single()).isEqualTo("q:TOO_LONG")
        assertThat(problems(search(alice, q((1..13).joinToString(" ") { "w$it" }))).single()).isEqualTo("q:TOO_MANY_TERMS")
        assertThat(problems(search(alice, "q=rent&status=NOPE")).single()).isEqualTo("status:UNKNOWN")
        assertThat(problems(search(alice, "q=rent&from=2026-02-01&to=2026-01-01")).single()).isEqualTo("to:BEFORE_FROM")
        assertThat(problems(search(alice, "q=rent&in=everywhere")).single()).isEqualTo("in:UNKNOWN")
        assertThat(search(alice, "q=rent&cursor=nonsense")).hasStatus(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `the 31st search in a minute is refused`() {
        val alice = person()
        repeat(RATE) { assertThat(search(alice, "q=rent")).describedAs("search ${it + 1}").hasStatusOk() }
        assertThat(search(alice, "q=rent")).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(read("/api/v1/search/suggest?q=ren", alice.session)).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
    }

    @Test
    fun `suggestions match the last word as a prefix while typing`() {
        val alice = person()
        val a = memo(alice, "Rent schedule for floor three")
        val b = memo(alice, "Schedule of the rent review")
        memo(alice, "Office rent increase")

        val found = read("/api/v1/search/suggest?q=sched", alice.session).json()
        assertThat((0 until found.size()).map { found[it]["memoId"].asString() }).containsExactlyInAnyOrder(a, b)
        assertThat(read("/api/v1/search/suggest?q=rent sch", alice.session).json().size()).isEqualTo(2)
        // Two letters are not searched yet.
        assertThat(read("/api/v1/search/suggest?q=sc", alice.session).json().size()).isZero()
    }

    private fun memo(
        who: Person,
        subject: String,
    ): String =
        create(who.session, """{"subject":"$subject","typeId":"${generalType(who.session)}","classificationId":"${levels()[0].id}"}""")
            .json()["id"]
            .asString()

    // Raw text: the test client encodes the URI (it is a template).
    private fun q(text: String) = "q=$text"

    private fun search(
        who: Person,
        query: String,
    ): MvcTestResult = read("/api/v1/search?$query", who.session)

    private fun ids(result: MvcTestResult): List<String> = ids(result.json())

    private fun ids(page: JsonNode): List<String> {
        assertThat(page["results"]).describedAs(page.toString()).isNotNull()
        val results = page["results"]
        return (0 until results.size()).map { results[it]["memoId"].asString() }
    }

    private fun problems(result: MvcTestResult): List<String> {
        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        return result.errors()
    }

    private companion object {
        const val CAP = 2000
        const val MANY = 4000
        const val RATE = 30
    }
}
