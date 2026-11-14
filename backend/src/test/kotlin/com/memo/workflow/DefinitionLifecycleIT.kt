package com.memo.workflow

import com.memo.identity.MfaSeeding
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.adminSession
import com.memo.testsupport.ageSessionFactors
import com.memo.testsupport.sendJson
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.util.UUID

/**
 * The definition lifecycle over the API (design E03 §5–§7, test plan T7): the version state machine, maker-checker,
 * step-up, If-Match on drafts, published versions that never change, retiring only what no memo type uses, and memo
 * types that choose a definition and a route policy that fits it.
 */
class DefinitionLifecycleIT : MemoIntegrationTest() {
    @Autowired lateinit var mfaSeeding: MfaSeeding

    private lateinit var maker: Cookie
    private lateinit var checker: Cookie

    @BeforeEach
    fun admins() {
        maker = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        checker = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
    }

    @Test
    fun `a version goes from draft through review to published, decided by someone other than its author`() {
        val created = send(HttpMethod.POST, BASE, maker, create("purchase", """{"schemaVersion":2,"start":"nowhere","steps":[]}"""))
        assertThat(created).hasStatus(HttpStatus.CREATED)
        val draft = created.json()
        assertThat(draft["version"]["state"].asString()).isEqualTo("DRAFT")
        assertThat(draft["problems"].size()).isPositive()
        val id = draft["id"].asString()
        assertThat(send(HttpMethod.POST, "$BASE/purchase/versions/1/submit", maker, ifMatch = revision(draft)))
            .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)

        assertThat(
            send(HttpMethod.PUT, "$BASE/purchase/versions/1", maker, """{"body":$ROUTED}"""),
        ).hasStatus(HttpStatus.PRECONDITION_REQUIRED)
        assertThat(send(HttpMethod.PUT, "$BASE/purchase/versions/1", maker, """{"body":$ROUTED}""", "\"99\""))
            .hasStatus(HttpStatus.PRECONDITION_FAILED)
        val saved = send(HttpMethod.PUT, "$BASE/purchase/versions/1", maker, """{"body":$ROUTED}""", revision(draft)).json()
        assertThat(saved["problems"].size()).isZero()

        val submitted = send(HttpMethod.POST, "$BASE/purchase/versions/1/submit", maker, ifMatch = revision(saved)).json()
        assertThat(submitted["version"]["state"].asString()).isEqualTo("IN_REVIEW")
        assertThat(reason(send(HttpMethod.PUT, "$BASE/purchase/versions/1", maker, """{"body":$ROUTED}""", revision(submitted))))
            .isEqualTo("WRONG_STATE")
        assertThat(reason(send(HttpMethod.POST, "$BASE/purchase/versions/1/approve", maker))).isEqualTo("SAME_PERSON")
        assertThat(
            reason(send(HttpMethod.POST, "$BASE/purchase/versions/1/send-back", maker, """{"remark":"x"}""")),
        ).isEqualTo("SAME_PERSON")
        assertThat(send(HttpMethod.POST, "$BASE/purchase/versions/1/send-back", checker, """{"remark":" "}"""))
            .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)

        val returned = send(HttpMethod.POST, "$BASE/purchase/versions/1/send-back", checker, """{"remark":"Name the steps"}""").json()
        assertThat(returned["version"]["state"].asString()).isEqualTo("DRAFT")
        assertThat(returned["version"]["reviewRemark"].asString()).isEqualTo("Name the steps")
        val again = send(HttpMethod.POST, "$BASE/purchase/versions/1/submit", maker, ifMatch = revision(returned)).json()

        ageSessionFactors(checker)
        assertThat(send(HttpMethod.POST, "$BASE/purchase/versions/1/approve", checker)).hasStatus(HttpStatus.UNAUTHORIZED)
        checker = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        val published = send(HttpMethod.POST, "$BASE/purchase/versions/1/approve", checker, """{"remark":"Fine"}""").json()
        assertThat(published["version"]["state"].asString()).isEqualTo("PUBLISHED")
        assertThat(published["problems"].size()).isZero()
        assertThat(reason(send(HttpMethod.PUT, "$BASE/purchase/versions/1", maker, """{"body":$ROUTED}""", revision(published))))
            .isEqualTo("WRONG_STATE")

        val submitter = again["version"]["submittedBy"]["id"].asString()
        val reviewer = published["version"]["reviewedBy"]["id"].asString()
        assertThat(reviewer).isNotEqualTo(submitter)
        Audit.assertAudited(tenant.id, "workflow.definition.created", id, submitter)
        Audit.assertAudited(tenant.id, "workflow.definition.draft_updated", id, submitter)
        Audit.assertAudited(tenant.id, "workflow.definition.submitted", id, submitter)
        Audit.assertAudited(tenant.id, "workflow.definition.sent_back", id, returned["version"]["reviewedBy"]["id"].asString())
        Audit.assertAudited(tenant.id, "workflow.definition.published", id, reviewer)

        val listed = read(BASE, maker).json()
        val purchase = (0 until listed.size()).map { listed[it] }.single { it["key"].asString() == "purchase" }
        assertThat(purchase["publishedVersion"].asInt()).isEqualTo(1)
        assertThat(purchase.absent("openVersion")).isTrue()
        val export = read("$BASE/purchase/versions/1/export", checker)
        assertThat(export).hasStatusOk()
        assertThat(export.response.getHeader("Content-Disposition")).contains("purchase-v1.json")
        assertThat(json.readTree(export.response.contentAsString)["steps"][0]["id"].asString()).isEqualTo("route")
    }

    @Test
    fun `a new draft copies the published version, one at a time, and publishing it retires the old one`() {
        publish("travel", ROUTED)
        val draft = send(HttpMethod.POST, "$BASE/travel/versions", maker)
        assertThat(draft).hasStatus(HttpStatus.CREATED)
        assertThat(draft.json()["version"]["number"].asInt()).isEqualTo(2)
        assertThat(draft.json()["body"]["steps"][0]["id"].asString()).isEqualTo("route")
        Audit.assertAudited(tenant.id, "workflow.definition.draft_created", draft.json()["id"].asString())
        assertThat(reason(send(HttpMethod.POST, "$BASE/travel/versions", maker))).isEqualTo("DRAFT_EXISTS")

        submitAndApprove("travel", 2, revision(draft.json()))
        val versions = read("$BASE/travel", maker).json()["versions"]
        assertThat((0 until versions.size()).map { versions[it]["state"].asString() }).containsExactly("PUBLISHED", "RETIRED")
    }

    @Test
    fun `only holders of the definition permissions read definitions, and built-in ones cannot change`() {
        val user = person()
        assertThat(read(BASE, user.session)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(read("$BASE/sequential-approval/versions/1", user.session)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(send(HttpMethod.POST, BASE, user.session, create("mine", ROUTED))).hasStatus(HttpStatus.FORBIDDEN)

        val builtIn = read("$BASE/sequential-approval/versions/1", maker).json()
        assertThat(builtIn["version"]["state"].asString()).isEqualTo("PUBLISHED")
        assertThat(builtIn["body"]["steps"][0]["type"].asString()).isEqualTo("route")
        assertThat(reason(send(HttpMethod.POST, "$BASE/sequential-approval/versions", maker))).isEqualTo("BUILT_IN")
        assertThat(reason(send(HttpMethod.POST, "$BASE/sequential-approval/versions/1/retire", checker))).isEqualTo("BUILT_IN")
        assertThat(reason(send(HttpMethod.POST, BASE, maker, create("sequential-approval", ROUTED)))).isEqualTo("KEY_TAKEN")
        assertThat(read("$BASE/nothing-here", maker)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(send(HttpMethod.POST, BASE, maker, create("Bad Key", ROUTED))).hasStatus(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `memo types choose a published definition that fits their route policy, and a definition in use is not retired`() {
        val approver = person()
        publish("fixed", fixed(approver.id))
        publish("routed", ROUTED)

        val choices = read("/api/v1/admin/memo-types/definitions", maker).json()
        val routed = (0 until choices.size()).associate { choices[it]["key"].asString() to choices[it]["routed"].asBoolean() }
        assertThat(routed).containsEntry("fixed", false).containsEntry("routed", true).containsEntry("sequential-approval", true)

        // Since Phase 3 E01 a type is set up in a draft version and published by a second administrator (ADR-0043).
        assertThat(memoTypeDraft("Unknown", "UNK", "nothing", "INITIATOR_DEFINED").second.errors())
            .containsExactly("definitionKey:unknown")
        assertThat(memoTypeDraft("Wrong", "WR", "fixed", "BOTH").second.errors()).containsExactly("routePolicy:mismatch")
        assertThat(memoTypeDraft("Wrong too", "WRT", "routed", "FIXED").second.errors()).containsExactly("routePolicy:mismatch")
        val (typeId, draft) = memoTypeDraft("Purchase", "PUR", "fixed", "FIXED")
        assertThat(draft).hasStatusOk()
        assertThat(draft.json()["routePolicy"].asString()).isEqualTo("FIXED")
        publishType(typeId, 1, revision(draft.json()))
        assertThat(reason(send(HttpMethod.POST, "$BASE/fixed/versions/1/retire", checker))).isEqualTo("IN_USE")

        // A memo of a FIXED type goes to the definition's approver without a route.
        val initiator = person()
        val memo =
            create(
                initiator.session,
                """{"subject":"Laptops","typeId":"$typeId","classificationId":"${levels()[0].id}"}""",
            ).json()["id"].asString()
        val allowed = get(memo, initiator.session).json()["allowedActions"]
        assertThat((0 until allowed.size()).map { allowed[it].asString() }).contains("SUBMIT").doesNotContain("EDIT_ROUTE")
        uploadDocument(memo, initiator.session)
        assertThat(post("/api/v1/memos/$memo/submit", initiator.session)).hasStatusOk()
        assertThat(get(memo, approver.session).json()["myTask"]?.get("id")).isNotNull()
        val inbox = read("/api/v1/inbox/tasks", approver.session).json()["items"][0]
        assertThat(inbox["kind"].asString()).isEqualTo("DECIDE")
        assertThat(inbox["stepName"].asString()).isEqualTo("Buyer")

        // A new version moves the type to another definition; once published, the old one is no longer in use.
        val moved = send(HttpMethod.POST, "$TYPES/$typeId/versions", maker, settings("Purchases", "PUR", "routed", "INITIATOR_DEFINED"))
        assertThat(moved).hasStatus(HttpStatus.CREATED)
        publishType(typeId, 2, revision(moved.json()))
        val retired = send(HttpMethod.POST, "$BASE/fixed/versions/1/retire", checker)
        assertThat(retired.json()["version"]["state"].asString()).isEqualTo("RETIRED")
        Audit.assertAudited(tenant.id, "workflow.definition.retired", retired.json()["id"].asString())
        // The memo started on it keeps running on the retired version.
        assertThat(get(memo, approver.session).json()["status"].asString()).isEqualTo("IN_REVIEW")
    }

    private fun publish(
        key: String,
        body: String,
    ) {
        val created = send(HttpMethod.POST, BASE, maker, create(key, body)).json()
        submitAndApprove(key, 1, revision(created))
    }

    private fun submitAndApprove(
        key: String,
        number: Int,
        revision: String,
    ) {
        assertThat(send(HttpMethod.POST, "$BASE/$key/versions/$number/submit", maker, ifMatch = revision)).hasStatusOk()
        assertThat(send(HttpMethod.POST, "$BASE/$key/versions/$number/approve", checker)).hasStatusOk()
    }

    /** A new memo type with [definitionKey] and [routePolicy] put in its draft version 1: its id, and the answer to the put. */
    private fun memoTypeDraft(
        name: String,
        code: String,
        definitionKey: String,
        routePolicy: String,
    ): Pair<String, MvcTestResult> {
        val created = post(TYPES, maker, """{"name":"$name","code":"$code"}""")
        assertThat(created).hasStatus(HttpStatus.CREATED)
        val id = created.json()["id"].asString()
        val draft = read("$TYPES/$id/versions/1", maker).json()
        return id to send(HttpMethod.PUT, "$TYPES/$id/versions/1", maker, settings(name, code, definitionKey, routePolicy), revision(draft))
    }

    private fun publishType(
        id: String,
        number: Int,
        revision: String,
    ) {
        assertThat(send(HttpMethod.POST, "$TYPES/$id/versions/$number/submit", maker, ifMatch = revision)).hasStatusOk()
        assertThat(send(HttpMethod.POST, "$TYPES/$id/versions/$number/approve", checker)).hasStatusOk()
    }

    private fun settings(
        name: String,
        code: String,
        definitionKey: String,
        routePolicy: String,
    ) = """{"name":"$name","code":"$code","definitionKey":"$definitionKey","routePolicy":"$routePolicy"}"""

    private fun send(
        method: HttpMethod,
        uri: String,
        session: Cookie,
        body: String = "{}",
        ifMatch: String? = null,
    ): MvcTestResult = mvc.sendJson(method, tenant, uri, session, body, ifMatch?.let { mapOf("If-Match" to it) }.orEmpty())

    private fun revision(version: tools.jackson.databind.JsonNode) = "\"${version["revision"].asLong()}\""

    private fun reason(result: MvcTestResult): String = result.json()["reason"].asString()

    private fun create(
        key: String,
        body: String,
    ) = """{"key":"$key","name":"${key.replaceFirstChar { it.uppercase() }}","body":$body}"""

    private fun fixed(approver: UUID) =
        """{"schemaVersion":2,"start":"approve","steps":[
          {"id":"approve","type":"approve","name":"Buyer","assignee":{"kind":"person","userId":"$approver"},"next":"end"}]}"""

    private companion object {
        const val BASE = "/api/v1/admin/workflow-definitions"
        const val TYPES = "/api/v1/admin/memo-types"
        const val ROUTED = """{"schemaVersion":2,"start":"route","steps":[{"id":"route","type":"route","name":"Approvers","next":"end"}]}"""
    }
}
