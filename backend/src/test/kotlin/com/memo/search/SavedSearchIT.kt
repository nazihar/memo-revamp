package com.memo.search

import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.sendJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult

/**
 * Saved searches (design E05 §3, flow H3; test plan E05 T3): private to their owner (others get 404), named and checked
 * like a search, at most 50 per person, guarded by version, and every change audited.
 */
class SavedSearchIT : MemoIntegrationTest() {
    private val criteria = """{"q":"rent schedule","statuses":["IN_REVIEW"],"sources":["METADATA","NOTES"],"sort":"NEWEST"}"""

    @Test
    fun `the owner saves, lists, renames and deletes a search, and each change is audited`() {
        val alice = person()
        val created = save(alice, "Credit memos this quarter")
        assertThat(created).hasStatus(HttpStatus.CREATED)
        val saved = created.json()
        val id = saved["id"].asString()
        assertThat(saved["criteria"]["q"].asString()).isEqualTo("rent schedule")
        assertThat(saved["criteria"]["sort"].asString()).isEqualTo("NEWEST")
        Audit.assertAudited(tenant.id, "search.saved", id)

        val list = read("/api/v1/saved-searches", alice.session).json()
        assertThat((0 until list.size()).map { list[it]["name"].asString() }).containsExactly("Credit memos this quarter")

        val renamed = replace(alice, id, "Rent searches", saved["version"].asLong())
        assertThat(renamed).hasStatusOk()
        assertThat(renamed.json()["name"].asString()).isEqualTo("Rent searches")
        Audit.assertAudited(tenant.id, "search.updated", id)
        // A stale version is refused.
        assertThat(replace(alice, id, "Again", saved["version"].asLong())).hasStatus(HttpStatus.PRECONDITION_FAILED)

        assertThat(remove(alice, id, renamed.json()["version"].asLong())).hasStatus(HttpStatus.NO_CONTENT)
        Audit.assertAudited(tenant.id, "search.deleted", id)
        assertThat(read("/api/v1/saved-searches", alice.session).json().size()).isZero()
    }

    @Test
    fun `someone else's saved search is not found, and not listed`() {
        val alice = person()
        val bob = person()
        val saved = save(alice, "Mine").json()
        val id = saved["id"].asString()
        assertThat(read("/api/v1/saved-searches", bob.session).json().size()).isZero()
        assertThat(replace(bob, id, "Taken", saved["version"].asLong())).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(remove(bob, id, saved["version"].asLong())).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a name and criteria are checked, and a person keeps at most 50`() {
        val alice = person()
        assertThat(save(alice, " ").errors()).containsExactly("name:REQUIRED")
        assertThat(save(alice, "x".repeat(101)).errors()).containsExactly("name:TOO_LONG")
        assertThat(save(alice, "Empty", """{"q":""}""").errors()).containsExactly("q:REQUIRED")
        assertThat(save(alice, "Too wide", """{"q":"rent","statuses":["NOPE"]}""").errors()).containsExactly("status:UNKNOWN")

        repeat(LIMIT) { assertThat(save(alice, "Search $it")).hasStatus(HttpStatus.CREATED) }
        val over = save(alice, "One more")
        assertThat(over).hasStatus(HttpStatus.CONFLICT)
        assertThat(over.json()["reason"].asString()).isEqualTo("SAVED_SEARCH_LIMIT")
    }

    private fun save(
        who: Person,
        name: String,
        body: String = criteria,
    ): MvcTestResult = post("/api/v1/saved-searches", who.session, """{"name":"$name","criteria":$body}""")

    private fun replace(
        who: Person,
        id: String,
        name: String,
        version: Long,
    ): MvcTestResult = put("/api/v1/saved-searches/$id", who.session, """{"name":"$name","criteria":$criteria}""", version)

    private fun remove(
        who: Person,
        id: String,
        version: Long,
    ): MvcTestResult =
        mvc.sendJson(HttpMethod.DELETE, tenant, "/api/v1/saved-searches/$id", who.session, "{}", mapOf("If-Match" to "\"$version\""))

    private companion object {
        const val LIMIT = 50
    }
}
