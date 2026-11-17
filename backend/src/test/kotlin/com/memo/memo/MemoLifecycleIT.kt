package com.memo.memo

import com.memo.audit.AuditLog
import com.memo.identity.UserAccount
import com.memo.memo.internal.AttachmentRules
import com.memo.memo.internal.MemoClosing
import com.memo.memo.internal.MemoClosingStore
import com.memo.memo.internal.MemoTypeChecks
import com.memo.memo.internal.MemoTypeFields
import com.memo.memo.internal.MemoTypeLifecycle
import com.memo.memo.internal.MemoTypeSettings
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.Memos
import com.memo.memo.internal.Retention
import com.memo.memo.internal.RetentionFrom
import com.memo.memo.internal.RoutePolicy
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.MutableClock
import com.memo.testsupport.Person
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.sendJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.time.Duration
import java.util.UUID

/**
 * The memo lifecycle completed (test plan E01, T5; design E01 §1): an approved memo is closed by its initiator, or by the
 * system after its type's days, and stays closed; memos link to each other by number, only to memos the person may
 * open, and a link's removal is recorded; a type keeps its retention and attachment rules.
 */
class MemoLifecycleIT : MemoIntegrationTest() {
    @Autowired lateinit var types: MemoTypes

    @Autowired lateinit var lifecycle: MemoTypeLifecycle

    @Autowired lateinit var checks: MemoTypeChecks

    @Autowired lateinit var memos: Memos

    @Autowired lateinit var closingStore: MemoClosingStore

    @Autowired lateinit var audit: AuditLog

    private lateinit var checker: UserAccount

    @BeforeEach
    fun reviewer() {
        checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
    }

    @Test
    fun `the initiator closes an approved memo, which then takes no action or link`() {
        val alice = person()
        val bob = person()
        val id = approved(alice, bob)
        assertThat(get(id, alice.session).json()["allowedActions"].toString()).contains("CLOSE")
        assertThat(post("/api/v1/memos/$id/close", bob.session)).hasStatus(HttpStatus.FORBIDDEN)

        val closed = post("/api/v1/memos/$id/close", alice.session, """{"remark":"Disbursed"}""")
        assertThat(closed).hasStatusOk()
        assertThat(closed.json()["status"].asString()).isEqualTo("CLOSED")
        assertThat(closed.json().absent("closedAt")).isFalse()
        assertThat(closed.json()["canLink"].asBoolean()).isFalse()
        Audit.assertAudited(tenant.id, "memo.closed", id, alice.id.toString())

        assertThat(post("/api/v1/memos/$id/close", alice.session)).hasStatus(HttpStatus.CONFLICT)
        val other = approved(alice, bob)
        val reference = get(other, alice.session).json()["reference"].asString()
        assertThat(post("/api/v1/memos/$id/links", alice.session, """{"targetReference":"$reference"}""")).hasStatus(HttpStatus.CONFLICT)
    }

    @Test
    fun `only approved memos close`() {
        val alice = person()
        val id = aDraft(alice)
        assertThat(post("/api/v1/memos/$id/close", alice.session).json()["reason"].asString()).isEqualTo("NOT_APPROVED")
    }

    @Test
    fun `a type with auto-close closes approved memos after its days, as the system`() {
        val type = aType(autoCloseDays = 30)
        val alice = person()
        val bob = person()
        val id = approved(alice, bob, type)
        val clock = MutableClock()
        val closing = MemoClosing(memos, closingStore, audit, clock)

        clock.advance(Duration.ofDays(29))
        assertThat(asUser(admin) { closing.closeDue() }).isZero()
        clock.advance(Duration.ofDays(2))
        assertThat(asUser(admin) { closing.closeDue() }).isEqualTo(1)
        assertThat(get(id, alice.session).json()["status"].asString()).isEqualTo("CLOSED")
        assertThat(asUser(admin) { closing.closeDue() }).isZero()
        val entry = Audit.entries(tenant.id).single { it.action == "memo.closed" && it.objectId == id }
        assertThat(entry.canonical).contains("auto")
    }

    @Test
    fun `memos link by number to memos the person may open, both ways, and removal is recorded`() {
        val alice = person()
        val bob = person()
        val carol = person()
        val earlier = approved(alice, bob)
        val earlierNumber = get(earlier, alice.session).json()["reference"].asString()
        val hidden = approved(carol, bob)
        val hiddenNumber = get(hidden, carol.session).json()["reference"].asString()
        val draft = aDraft(alice)

        val linked = post("/api/v1/memos/$draft/links", alice.session, """{"targetReference":"$earlierNumber","kind":"FOLLOWS_UP"}""")
        assertThat(linked).hasStatusOk()
        val link = linked.json()["links"][0]
        assertThat(link["reference"].asString()).isEqualTo(earlierNumber)
        assertThat(link["kind"].asString()).isEqualTo("FOLLOWS_UP")
        assertThat(link["removable"].asBoolean()).isTrue()
        Audit.assertAudited(tenant.id, "memo.link.added", draft, alice.id.toString())
        // Seen from the other memo, by someone who may open both.
        assertThat(get(earlier, alice.session).json()["linkedFrom"][0]["memoId"].asString()).isEqualTo(draft)

        assertThat(post("/api/v1/memos/$draft/links", alice.session, """{"targetReference":"$hiddenNumber"}""").errors())
            .containsExactly("targetReference:LINK_TARGET_UNREADABLE")
        assertThat(post("/api/v1/memos/$draft/links", alice.session, """{"targetReference":"NO-SUCH-1"}""").errors())
            .containsExactly("targetReference:LINK_TARGET_UNREADABLE")
        assertThat(post("/api/v1/memos/$draft/links", alice.session, """{"targetReference":"$earlierNumber"}""").errors())
            .containsExactly("targetReference:LINK_EXISTS")

        // Bob approved the earlier memo but does not take part in the draft: he sees no link to it from there.
        assertThat(get(earlier, bob.session).json()["linkedFrom"].size()).isZero()

        val linkId = link["id"].asString()
        val removed =
            mvc.sendJson(
                org.springframework.http.HttpMethod.DELETE,
                tenant,
                "/api/v1/memos/$draft/links/$linkId",
                alice.session,
                "",
                emptyMap(),
            )
        assertThat(removed).hasStatusOk()
        assertThat(removed.json()["links"].size()).isZero()
        Audit.assertAudited(tenant.id, "memo.link.removed", draft, alice.id.toString())
    }

    @Test
    fun `retention, attachment rules and auto-close days are kept and checked`() {
        val type =
            aType(autoCloseDays = 90, retention = Retention(10, RetentionFrom.CLOSURE), rules = AttachmentRules(true, 1, "The appraisal"))
        val version = asUser(admin) { types.published(type) }!!.settings
        assertThat(version.retention).isEqualTo(Retention(10, RetentionFrom.CLOSURE))
        assertThat(version.attachmentRules.min).isEqualTo(1)
        assertThat(version.autoCloseDays).isEqualTo(90)

        val bad = settings("Bad", "BAD", 0, Retention(0), AttachmentRules(true, 0))
        assertThat(asUser(admin) { checks.problems(bad, null) }.map { "${it.field}:${it.code}" })
            .contains("retention/years:RANGE", "autoCloseDays:RANGE", "attachmentRules/min:RANGE")
    }

    private fun approved(
        initiator: Person,
        approver: Person,
        type: UUID? = null,
    ): String {
        val id =
            if (type == null) {
                aDraft(initiator)
            } else {
                create(initiator.session, """{"subject":"Facility","typeId":"$type"}""").json()["id"].asString()
            }
        assertThat(uploadDocument(id, initiator.session)).hasStatus(HttpStatus.CREATED)
        assertThat(route(id, initiator, approver)).hasStatusOk()
        assertThat(post("/api/v1/memos/$id/submit", initiator.session)).hasStatusOk()
        val task = get(id, approver.session).json()["myTask"]["id"].asString()
        assertThat(post("/api/v1/tasks/$task/approve", approver.session)).hasStatusOk()
        return id
    }

    private fun aType(
        autoCloseDays: Int? = null,
        retention: Retention? = null,
        rules: AttachmentRules = AttachmentRules(),
    ): UUID {
        val id = asUser(admin) { types.create(MemoTypeFields("Credit ${UUID.randomUUID().toString().take(4)}", null, false, true)) }.id
        val code = asUser(admin) { types.get(id) }.code
        val name = asUser(admin) { types.get(id) }.name
        val draft = asUser(admin) { lifecycle.newDraft(id, settings(name, code, autoCloseDays, retention, rules)) }
        val submitted = asUser(admin) { lifecycle.submit(id, draft.number, draft.revision) }
        asUser(checker) { lifecycle.approve(id, submitted.number, null) }
        return id
    }

    private fun settings(
        name: String,
        code: String,
        autoCloseDays: Int?,
        retention: Retention?,
        rules: AttachmentRules,
    ) = MemoTypeSettings(
        name,
        code,
        null,
        "sequential-approval",
        RoutePolicy.INITIATOR_DEFINED,
        stepUpOnApprove = false,
        retention = retention,
        attachmentRules = rules,
        autoCloseDays = autoCloseDays,
    )
}
