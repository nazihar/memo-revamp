package com.memo.actions

import com.memo.actions.internal.ActionFields
import com.memo.actions.internal.ActionIssuing
import com.memo.actions.internal.ActionRaising
import com.memo.actions.internal.ActionStatus
import com.memo.actions.internal.ActionStore
import com.memo.actions.internal.OwnerInput
import com.memo.actions.internal.OwnerKind
import com.memo.common.review.ReviewState
import com.memo.forum.ActionsPolicy
import com.memo.forum.ForumRole
import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumMembers
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypes
import com.memo.forum.internal.Forums
import com.memo.forum.internal.MemberInput
import com.memo.identity.MfaSeeding
import com.memo.identity.mfa.Totp
import com.memo.memo.internal.FieldRules
import com.memo.memo.internal.FieldType
import com.memo.memo.internal.MemoField
import com.memo.memo.internal.MemoTemplatePurpose
import com.memo.memo.internal.MemoTypeLifecycle
import com.memo.memo.internal.MemoTypes
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.AppRoleJdbc.update
import com.memo.testsupport.Audit
import com.memo.testsupport.Docx
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.adminSession
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.login
import com.memo.testsupport.postJson
import com.memo.testsupport.query
import com.memo.testsupport.sessionCookie
import com.memo.workflow.WorkflowQueries
import com.memo.workflow.Workflows
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.JsonNode
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.Year
import java.time.ZoneOffset
import java.util.UUID

/**
 * Follow-up memos (test plan E07 T6 `FollowUpMemoIT`; ADR-0067 D1, design E07 §1.11): T6's acceptance criteria on a
 * direct action of the Board Risk Committee, over HTTP. The owner raises a draft memo of a type with a template: the
 * memo is theirs, filled from the template, says on its note sheet where it came from, and both sides show the link. The
 * memo decided (approved, rejected, withdrawn) leaves an entry on the action and tells its holder, and the action stays
 * open. The memo's state shows on the action by the memo's own rule (the verifier and secretaries see the outcome only),
 * and the memo names the action with a link only for those who see the action. Linking an existing memo, the limits, who
 * may and when, the idempotent replay, and another tenant.
 */
class FollowUpMemoIT : MemoIntegrationTest() {
    @Autowired lateinit var forumTypes: ForumTypes

    @Autowired lateinit var forums: Forums

    @Autowired lateinit var members: ForumMembers

    @Autowired lateinit var raising: ActionRaising

    @Autowired lateinit var issuing: ActionIssuing

    @Autowired lateinit var actionStore: ActionStore

    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var workflowQueries: WorkflowQueries

    @Autowired lateinit var memoTypes: MemoTypes

    @Autowired lateinit var typeLifecycle: MemoTypeLifecycle

    @Autowired lateinit var mfaSeeding: MfaSeeding

    lateinit var secretary: Person
    lateinit var chair: Person
    lateinit var member: Person

    /** The action's owner: outside the committee. */
    lateinit var owner: Person
    lateinit var contributor: Person

    /** Approves the memos: outside the committee and the action, signed in with a second factor. */
    lateinit var approver: Person
    lateinit var brc: UUID

    private val year get() = Year.now(ZoneOffset.UTC).value

    private val title = "Revise the liquidity contingency plan"

    @BeforeEach
    fun committee() {
        secretary = person(clearance = 1)
        chair = person(clearance = 1)
        member = person(clearance = 1)
        owner = person(clearance = 1)
        contributor = person(clearance = 1)
        approver = withTotp()
        clear(approver.id, 1)
        val type = asUser(admin) { forumTypes.create("BC", "Board committee", ForumTypePreset.BOARD_COMMITTEE, publish = true) }.type.id
        brc =
            asUser(admin) {
                forums.create(ForumInput("board-risk", "BRC", "Board Risk Committee", "Risk", type))
            }.id
        seat(secretary, ForumRole.SECRETARY)
        seat(chair, ForumRole.CHAIR)
        seat(member, ForumRole.MEMBER)
    }

    @Test
    fun `the owner raises a draft from the type's template, the memo says where it came from, and its approval leaves the action open`() {
        val action = issued()
        val typeId = templatedType()

        val raised = post("/api/v1/actions/$action/memos", owner.session, """{"typeId":"$typeId"}""")
        assertThat(raised).hasStatus(HttpStatus.CREATED)
        val memoId = raised.json()["memoId"].asString()
        assertThat(raised.response.getHeader("Location")).isEqualTo("/api/v1/memos/$memoId")
        assertThat(raised.json()["templateFilled"].asBoolean()).isTrue()
        val listed = raised.json()["action"]["memos"].single()
        assertThat(listOf(listed["kind"], listed["readable"], listed["subject"], listed["status"]).map { it.asString() })
            .containsExactly("RAISED", "true", title, "DRAFT")

        // An ordinary draft of the owner, at the action's level, its document the filled template.
        val memo = get(memoId, owner.session).json()
        assertThat(memo["initiator"]["id"].asString()).isEqualTo(owner.id.toString())
        assertThat(memo["status"].asString() to memo["subject"].asString()).isEqualTo("DRAFT" to title)
        assertThat(memo["classification"]["id"].asString()).isEqualTo(levels()[1].id.toString())
        val fileId = memo["document"]["fileId"].asString()
        val text = Docx.text(read("/api/v1/files/$fileId/content", owner.session).response.contentAsByteArray)
        assertThat(text).contains("Subject: $title")
        Audit.assertAudited(tenant.id, "memo.created", memoId, owner.id.toString())
        Audit.assertAudited(tenant.id, "memo.document.filled", memoId)

        // "Raised from action BRC A1/yyyy (Board Risk Committee)" on the note sheet, by the system, never corrected.
        val line = read("/api/v1/memos/$memoId/notes", owner.session).json()["entries"].single()
        assertThat(listOf(line["kind"], line["actionKind"], line["text"], line["canCorrect"]).map { it.asString() })
            .containsExactly("COMMENT", "RAISED_FROM_ACTION", "Raised from action BRC A1/$year (Board Risk Committee)", "false")
        val correction =
            post(
                "/api/v1/memos/$memoId/notes",
                owner.session,
                """{"body":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"No"}]}]},""" +
                    """"correctsId":"${line["id"].asString()}"}""",
            )
        assertThat(correction.json()["reason"].asString()).isEqualTo("NOT_YOURS")

        // Both ways: the memo names the action, with its id for those who see it; the action lists the memo.
        val origin = memo["followsUp"].single()
        val fields = listOf("kind", "recordType", "reference", "owner", "recordId")
        assertThat(fields.map { origin[it].asString() })
            .containsExactly("RAISED_FROM", "ACTION", "BRC A1/$year", "Board Risk Committee", action.toString())
        assertThat(entries(action)).containsExactly("DRAFTED", "ISSUED", "MEMO_RAISED")
        val audited = Audit.assertAudited(tenant.id, "actions.memo.raised", action.toString(), owner.id.toString())
        assertThat(audited.canonical).contains(memoId, typeId).doesNotContain("liquidity")

        // The approver reads the memo, not the action: the reference as text, without the action's id.
        val decided = approve(memoId)
        val seenByApprover = get(memoId, approver.session).json()["followsUp"].single()
        assertThat(seenByApprover["reference"].asString()).isEqualTo("BRC A1/$year")
        assertThat(seenByApprover.absent("recordId")).isTrue()

        // Approved: an entry on the action, its holder told (reference and outcome, no title), the action still open.
        assertThat(decided).hasStatusOk()
        assertThat(entries(action)).containsExactly("DRAFTED", "ISSUED", "MEMO_RAISED", "MEMO_DECIDED")
        assertThat(entryDetails(action, "MEMO_DECIDED")).contains(memoId, "APPROVED")
        Audit.assertAudited(tenant.id, "actions.memo.decided", action.toString(), approver.id.toString())
        assertThat(items(owner, "ACTION_MEMO_DECIDED")).singleElement().satisfies({
            assertThat(it).contains("BRC A1/$year", "APPROVED", "Board Risk Committee").doesNotContain("liquidity")
        })
        assertThat(actionRow(action).status).isEqualTo(ActionStatus.OPEN)
        assertThat(read("/api/v1/actions/$action", owner.session).json()["memos"].single()["status"].asString()).isEqualTo("APPROVED")
    }

    @Test
    fun `the memo's state follows its own rule, the verifier and secretaries see the outcome only, members nothing`() {
        // The only secretary owns the action, so the chair verifies it (design E07 §1.9).
        val action = issued(owner = secretary)
        val memoId = raise(action, secretary, generalType(secretary.session))
        val second = post("/api/v1/actions/$action/memos", secretary.session, """{"typeId":"${generalType(secretary.session)}"}""")
        assertThat(second.json()["templateFilled"].asBoolean()).isFalse()
        val ownerTask = checkNotNull(asUser(secretary.account) { workflowQueries.myTask(ActionsPolicy.SUBJECT, action, secretary.id) })
        asUser(secretary.account) { workflows.approve(ownerTask.id, "The plan is with the committee") }
        assertThat(actionRow(action).status).isEqualTo(ActionStatus.DONE_PENDING)

        val chairs = memoOf(read("/api/v1/actions/$action", chair.session), memoId)
        assertThat(chairs["readable"].asBoolean()).isFalse()
        assertThat(chairs.absent("subject") && chairs.absent("reference")).isTrue()
        assertThat(chairs["status"].asString()).isEqualTo("DRAFT")
        val members = memoOf(read("/api/v1/actions/$action", member.session), memoId)
        assertThat(members["readable"].asBoolean()).isFalse()
        assertThat(members.absent("status") && members.absent("subject")).isTrue()
        // The chair does not read the memo itself.
        assertThat(get(memoId, chair.session)).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a memo rejected or withdrawn is recorded on the action too, and a level below the action's needs a confirmation`() {
        val action = issued()
        val general = generalType(owner.session)
        val lower = levels()[0].id
        val refused = post("/api/v1/actions/$action/memos", owner.session, """{"typeId":"$general","classificationId":"$lower"}""")
        assertThat(refused).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(refused.errors()).containsExactly("classificationId:LOWER_THAN_ACTION")
        val confirmed =
            post(
                "/api/v1/actions/$action/memos",
                owner.session,
                """{"typeId":"$general","classificationId":"$lower","confirmLowerLevel":true,"subject":"Interim funding note"}""",
            )
        assertThat(confirmed).hasStatus(HttpStatus.CREATED)
        val rejected = confirmed.json()["memoId"].asString()
        assertThat(get(rejected, owner.session).json()["classification"]["id"].asString()).isEqualTo(lower.toString())
        val withdrawn = raise(action, owner, general)

        submit(rejected)
        assertThat(post("/api/v1/tasks/${task(rejected, approver)}/reject", approver.session, """{"remark":"Not needed"}""")).hasStatusOk()
        submit(withdrawn)
        assertThat(post("/api/v1/memos/$withdrawn/withdraw", owner.session)).hasStatusOk()

        val outcomes =
            AppRoleJdbc.inTransaction(tenant.id) { c ->
                c.query(
                    "select details->>'outcome' from actions_entry where action_id = ? and kind = 'MEMO_DECIDED' " +
                        "order by seq",
                    action,
                ) {
                    it.getString(1)
                }
            }
        assertThat(outcomes).containsExactly("REJECTED", "WITHDRAWN")
        assertThat(items(owner, "ACTION_MEMO_DECIDED")).hasSize(2)
        assertThat(actionRow(action).status).isEqualTo(ActionStatus.OPEN)
        // An unknown type is the memo's own refusal.
        assertThat(post("/api/v1/actions/$action/memos", owner.session, """{"typeId":"${UUID.randomUUID()}"}""").errors())
            .containsExactly("typeId:unknown")
    }

    @Test
    fun `an existing memo the person reads is linked once, at most ten in all, and shown on the memo to those who see the action`() {
        val action = issued(contributors = listOf(contributor))
        // The secretary links their own memo; the memo then names the action for them, not for its other readers.
        val own = aDraft(secretary, rank = 1)
        val linked = post("/api/v1/actions/$action/memos/link", secretary.session, """{"memoId":"$own"}""")
        assertThat(linked).hasStatusOk()
        assertThat(linked.json()["memos"].single()["kind"].asString()).isEqualTo("LINKED")
        Audit.assertAudited(tenant.id, "actions.memo.linked", action.toString(), secretary.id.toString())
        assertThat(get(own, secretary.session).json()["followsUp"].single()["kind"].asString()).isEqualTo("LINKED_TO")
        post("/api/v1/memos/$own/shares", secretary.session, """{"userId":"${approver.id}","reason":"For information"}""")
        assertThat(get(own, approver.session).json()["followsUp"].size()).isZero()

        assertThat(post("/api/v1/actions/$action/memos/link", secretary.session, """{"memoId":"$own"}""").json()["reason"].asString())
            .isEqualTo("ALREADY_LINKED")
        // A memo the person cannot read, or one discarded, is refused the same way for every reader.
        val others = aDraft(approver)
        assertThat(post("/api/v1/actions/$action/memos/link", contributor.session, """{"memoId":"$others"}""").errors())
            .containsExactly("memoId:NOT_READABLE")
        assertThat(post("/api/v1/actions/$action/memos/link", contributor.session, """{"memoId":"${UUID.randomUUID()}"}""").errors())
            .containsExactly("memoId:NOT_READABLE")
        val discarded = aDraft(contributor, rank = 1)
        post("/api/v1/memos/$discarded/discard", contributor.session)
        assertThat(post("/api/v1/actions/$action/memos/link", contributor.session, """{"memoId":"$discarded"}""").errors())
            .containsExactly("memoId:DISCARDED")

        // A contributor raises one; eight more fill the ten; the eleventh is refused.
        val general = generalType(contributor.session)
        assertThat(get(raise(action, contributor, general), contributor.session).json()["initiator"]["id"].asString())
            .isEqualTo(contributor.id.toString())
        repeat(8) { raise(action, owner, general) }
        val full = post("/api/v1/actions/$action/memos", owner.session, """{"typeId":"$general"}""")
        assertThat(full.errors()).containsExactly("memos:TOO_MANY")
        assertThat(read("/api/v1/actions/$action", owner.session).json()["memos"].size()).isEqualTo(10)
    }

    @Test
    fun `only the owner and contributors raise, secretaries also link, only while the action is open, and a replay raises nothing new`() {
        val action = issued()
        val general = generalType(owner.session)
        val asOwner = read("/api/v1/actions/$action", owner.session).json()["can"]
        assertThat(asOwner["raiseMemo"].asBoolean() to asOwner["linkMemo"].asBoolean()).isEqualTo(true to true)
        val asSecretary = read("/api/v1/actions/$action", secretary.session).json()["can"]
        assertThat(asSecretary["raiseMemo"].asBoolean() to asSecretary["linkMemo"].asBoolean()).isEqualTo(false to true)

        // A member sees the action and may do neither; someone outside the committee does not see it.
        val byMember = post("/api/v1/actions/$action/memos", member.session, """{"typeId":"$general"}""")
        assertThat(byMember).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(byMember.json()["reason"].asString()).isEqualTo("NOT_INVOLVED")
        val linkByMember = post("/api/v1/actions/$action/memos/link", member.session, """{"memoId":"${aDraft(member)}"}""")
        assertThat(linkByMember).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(post("/api/v1/actions/$action/memos", approver.session, """{"typeId":"$general"}""")).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(post("/api/v1/actions/$action/memos", secretary.session, """{"typeId":"$general"}""")).hasStatus(HttpStatus.FORBIDDEN)

        // The same key raises one memo, once.
        val key = UUID.randomUUID().toString()
        val first = post("/api/v1/actions/$action/memos", owner.session, """{"typeId":"$general"}""", key).json()["memoId"].asString()
        val again = post("/api/v1/actions/$action/memos", owner.session, """{"typeId":"$general"}""", key).json()["memoId"].asString()
        assertThat(again).isEqualTo(first)
        assertThat(AppRoleJdbc.inTransaction(tenant.id) { c -> c.long("select count(*) from actions_memo where action_id = ?", action) })
            .isEqualTo(1)

        // A draft action has no memos: the secretary who may see it is told it is not open.
        val draftFields = fields(owner)
        val draft = asUser(secretary.account) { raising.raiseDirect(brc, draftFields) }
        val own = aDraft(secretary, rank = 1)
        assertThat(post("/api/v1/actions/$draft/memos/link", secretary.session, """{"memoId":"$own"}""").json()["reason"].asString())
            .isEqualTo("WRONG_STATE")
        assertThat(post("/api/v1/actions/$draft/memos", owner.session, """{"typeId":"$general"}""")).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `another tenant neither sees nor writes the follow-up memos`() {
        val action = issued()
        val memoId = UUID.fromString(raise(action, owner, generalType(owner.session)))
        val b = registry.aTestTenant(transactions, settings, "followb", mfaRequired = false)
        val bUser = accounts.anActiveUser(transactions, b, aPassword())
        AppRoleJdbc.inTransaction(b.id) { c ->
            assertThat(c.long("select count(*) from actions_memo where tenant_id = ?", tenant.id.value)).isZero()
            assertThat(c.long("select count(*) from actions_memo where memo_id = ?", memoId)).isZero()
        }
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val pointing =
            runCatching {
                AppRoleJdbc.inTransaction(b.id) { c ->
                    c.update(
                        "insert into actions_memo (tenant_id, action_id, memo_id, kind, linked_by, linked_at) " +
                            "values (?, ?, ?, 'LINKED', ?, ?)",
                        b.id.value,
                        action,
                        UUID.randomUUID(),
                        bUser.id.value,
                        now,
                    )
                }
            }
        assertThat(pointing.exceptionOrNull()).isInstanceOf(SQLException::class.java)
        // The link is the record: the app role changes or removes none.
        val changes = listOf("update actions_memo set kind = 'LINKED' where action_id = ?", "delete from actions_memo where action_id = ?")
        changes.forEach { sql ->
            val written = runCatching { AppRoleJdbc.inTransaction(tenant.id) { c -> c.update(sql, action) } }
            assertThat(written.exceptionOrNull()).describedAs(sql).isInstanceOf(SQLException::class.java)
        }
        assertThat(AppRoleJdbc.inTransaction(tenant.id) { c -> c.long("select count(*) from actions_memo where action_id = ?", action) })
            .isEqualTo(1)
    }

    // Set-up

    private fun seat(
        person: Person,
        role: ForumRole,
    ) = asUser(admin) { members.add(brc, MemberInput(userId = person.id, role = role)) }

    private fun fields(
        owner: Person,
        contributors: List<Person> = emptyList(),
    ) = ActionFields(
        title,
        "Details",
        null,
        OwnerInput(OwnerKind.PERSON, userId = owner.id),
        contributors.map { it.id },
        LocalDate.now(ZoneOffset.UTC).plusDays(30),
        classificationId = levels()[1].id,
    )

    /** A direct action of the BRC, issued by the secretary to [owner]; its id. */
    private fun issued(
        owner: Person = this.owner,
        contributors: List<Person> = emptyList(),
    ): UUID {
        val fields = fields(owner, contributors)
        val id = asUser(secretary.account) { raising.raiseDirect(brc, fields) }
        asUser(secretary.account) { issuing.issue(id, null) }
        return id
    }

    /** A follow-up memo of [typeId] raised from [action] by [by]; its id. */
    private fun raise(
        action: UUID,
        by: Person,
        typeId: String,
    ): String {
        val raised = post("/api/v1/actions/$action/memos", by.session, """{"typeId":"$typeId"}""")
        assertThat(raised).hasStatus(HttpStatus.CREATED)
        return raised.json()["memoId"].asString()
    }

    /** Sends the owner's memo [memoId] to [approver], with a document when the template gave none. */
    private fun submit(memoId: String) {
        if (get(memoId, owner.session).json().absent("document")) uploadDocument(memoId, owner.session)
        route(memoId, owner, approver)
        assertThat(post("/api/v1/memos/$memoId/submit", owner.session)).hasStatusOk()
    }

    private fun approve(memoId: String): MvcTestResult {
        submit(memoId)
        return post("/api/v1/tasks/${task(memoId, approver)}/approve", approver.session)
    }

    private fun task(
        memo: String,
        who: Person,
    ) = get(memo, who.session).json()["myTask"]["id"].asString()

    /** A published memo type whose template starts the document (ADR-0048), as in `MemoTemplatesIT`. */
    private fun templatedType(): String {
        val session = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        val amount = MemoField(key = "amount", label = "Amount", type = FieldType.AMOUNT, rules = FieldRules(currencies = listOf("BDT")))
        val suffix = UUID.randomUUID().toString().take(6)
        val (type, _) = asUser(admin) { memoTypes.createDraft("Liquidity $suffix", "L${(100..999).random()}") }
        val draft = asUser(admin) { typeLifecycle.list(type.id) }.single { it.state == ReviewState.DRAFT }
        val settings = draft.settings.copy(fields = listOf(amount))
        val version = asUser(admin) { typeLifecycle.replace(type.id, draft.number, draft.revision, settings) }
        val base = "/api/v1/admin/memo-types/${type.id}/versions/1/template"
        val templateUri = "/api/v1/files?purpose=${MemoTemplatePurpose.KEY}&owner=${version.id}"
        assertThat(upload(session, Docx.letterhead(), templateUri, "letterhead.docx"))
            .hasStatus(HttpStatus.CREATED)
        assertThat(post("$base/preview", session)).hasStatusOk()
        asUser(admin) { typeLifecycle.submit(type.id, 1, typeLifecycle.get(type.id, 1).revision) }
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        asUser(checker) { typeLifecycle.approve(type.id, 1, null) }
        return type.id.toString()
    }

    /** A person signed in with password and TOTP (an approval may need a fresh second factor). */
    private fun withTotp(): Person {
        val password = aPassword()
        val account = accounts.anActiveUser(transactions, tenant, password)
        val secret = Totp.base32(Totp.newSecret())
        transactions.inTenant(tenant.id) { mfaSeeding.ensureTotp(account.id, secret) }
        val partial = requireNotNull(mvc.login(tenant, account.email, password).sessionCookie())
        val code = Totp.code(Totp.decodeBase32(secret), Totp.step(Instant.now()))
        return Person(
            account,
            requireNotNull(mvc.postJson(tenant, "/api/v1/auth/mfa", partial, """{"code":"$code"}""").sessionCookie(partial)),
        )
    }

    // Reading back

    private fun actionRow(id: UUID) = transactions.readInTenant(tenant.id) { checkNotNull(actionStore.find(id)) }

    private fun entries(action: UUID): List<String> =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.query("select kind from actions_entry where action_id = ? order by seq", action) { it.getString(1) }
        }

    private fun entryDetails(
        action: UUID,
        kind: String,
    ): String =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.query("select details::text from actions_entry where action_id = ? and kind = ?", action, kind) { it.getString(1) }.single()
        }

    private fun items(
        person: Person,
        kind: String,
    ): List<String> =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.query("select params::text from notification_item where user_id = ? and kind = ?", person.id, kind) { it.getString(1) }
        }

    private fun memoOf(
        action: MvcTestResult,
        memoId: String,
    ): JsonNode {
        val memos = action.json()["memos"]
        return (0 until memos.size()).map { memos[it] }.single { it["memoId"].asString() == memoId }
    }

    private fun JsonNode.single(): JsonNode {
        assertThat(size()).describedAs("entries").isEqualTo(1)
        return this[0]
    }
}
