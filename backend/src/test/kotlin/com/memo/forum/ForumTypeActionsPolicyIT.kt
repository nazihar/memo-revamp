package com.memo.forum

import com.memo.common.review.ReviewState
import com.memo.common.web.RefusedException
import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumRejectedException
import com.memo.forum.internal.ForumTypeLifecycle
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypes
import com.memo.forum.internal.Forums
import com.memo.identity.MfaSeeding
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.aPassword
import com.memo.testsupport.adminSession
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.sendJson
import com.memo.workflow.internal.DefinitionLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * A committee type's actions policy (design E07 §1.13; test plan T2 `ForumTypeActionsPolicyIT`): the presets and null
 * read as the defaults; the checks with their fields at save and at submit, over HTTP as 422; `UNKNOWN_DEFINITION` for a
 * definition that is not published or not bound to action items; the maker-checker path with its audit; the version in
 * force today read for a committee whatever its meetings pinned; and a definition a type names kept from retirement.
 */
class ForumTypeActionsPolicyIT : MemoIntegrationTest() {
    @Autowired lateinit var types: ForumTypes

    @Autowired lateinit var lifecycle: ForumTypeLifecycle

    @Autowired lateinit var policies: ForumPolicies

    @Autowired lateinit var forums: Forums

    @Autowired lateinit var definitions: DefinitionLifecycle

    @Autowired lateinit var mfaSeeding: MfaSeeding

    private fun type(
        code: String,
        preset: ForumTypePreset = ForumTypePreset.BOARD_COMMITTEE,
    ): UUID = asUser(admin) { types.create(code, "$code committee", preset) }.type.id

    @Test
    fun `the presets set the policy, and a version stored before it reads as the defaults`() {
        val created = ForumTypePreset.entries.associateWith { asUser(admin) { types.create("P${it.ordinal}", it.displayName, it) } }
        assertThat(
            created.mapValues {
                it.value.open!!
                    .settings.actions
            },
        ).containsEntry(ForumTypePreset.BOARD, ActionsPolicy())
            .containsEntry(ForumTypePreset.BOARD_COMMITTEE, ActionsPolicy())
            .containsEntry(ForumTypePreset.BOARD_AUDIT_COMMITTEE, ActionsPolicy(evidenceRequired = true))
            .containsEntry(ForumTypePreset.MANAGEMENT_COMMITTEE, ForumTypePreset.MANAGEMENT_COMMITTEE.settings.actions)

        val old = type("OLD")
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.prepareStatement("update forum_type_version set actions = null where forum_type_id = ?").use {
                it.setObject(1, old)
                it.executeUpdate()
            }
        }
        assertThat(asUser(admin) { lifecycle.get(old, 1) }.settings.actions).isEqualTo(ActionsPolicy())
    }

    @Test
    fun `the checks name their fields, and a definition must be published and bound to action items`() {
        val id = type("CHK")
        val draft = asUser(admin) { lifecycle.get(id, 1) }

        fun save(policy: ActionsPolicy) = problems { lifecycle.replace(id, 1, draft.revision, draft.settings.copy(actions = policy)) }

        assertThat(save(ActionsPolicy(amberWorkingDays = 0))).isEqualTo("actions/amberWorkingDays:RANGE")
        assertThat(save(ActionsPolicy(staleWorkingDays = 3))).isEqualTo("actions/staleWorkingDays:RANGE")
        assertThat(save(ActionsPolicy(defaultDueWorkingDays = 121))).isEqualTo("actions/defaultDueWorkingDays:RANGE")
        assertThat(save(ActionsPolicy(updateRequestWorkingDays = 21))).isEqualTo("actions/updateRequestWorkingDays:RANGE")
        assertThat(save(ActionsPolicy(definitionKey = "nothing-here"))).isEqualTo("actions/definitionKey:UNKNOWN_DEFINITION")
        // A memo workflow is published but not bound to action items.
        assertThat(save(ActionsPolicy(definitionKey = "sequential-approval"))).isEqualTo("actions/definitionKey:UNKNOWN_DEFINITION")
        val off = ActionsPolicy(staleWorkingDays = 0, updateRequestWorkingDays = 0, verifiedBy = ActionVerifier.CHAIR)
        assertThat(asUser(admin) { lifecycle.replace(id, 1, draft.revision, draft.settings.copy(actions = off)) }.settings.actions)
            .isEqualTo(off)
    }

    @Test
    fun `a draft with amber at 0 working days is refused at submit with 422 at its field`() {
        val session = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        val id = type("SUB")
        val revision = asUser(admin) { lifecycle.get(id, 1) }.revision
        // A draft stored with a value the save checks refuse today (written past them), committed for the request to read.
        AppRoleJdbc.connect { c ->
            c.autoCommit = false
            c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                it.setString(1, tenant.id.value.toString())
                it.execute()
            }
            c.prepareStatement("update forum_type_version set actions = '{\"amberWorkingDays\":0}'::jsonb where forum_type_id = ?").use {
                it.setObject(1, id)
                it.executeUpdate()
            }
            c.commit()
        }
        assertThat(asUser(admin) { lifecycle.get(id, 1) }.settings.actions.amberWorkingDays).isZero()
        val submitted =
            mvc.sendJson(
                HttpMethod.POST,
                tenant,
                "/api/v1/admin/forum-types/$id/versions/1/submit",
                session,
                "{}",
                mapOf("If-Match" to "\"$revision\""),
            )
        assertThat(submitted).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(submitted.errors()).containsExactly("actions/amberWorkingDays:RANGE")
        assertThat(asUser(admin) { lifecycle.get(id, 1) }.state).isEqualTo(ReviewState.DRAFT)
    }

    @Test
    fun `a change is reviewed by a second administrator and audited, and a committee reads the version in force today`() {
        val id = type("BRC")
        val draft = asUser(admin) { lifecycle.get(id, 1) }
        val first =
            ActionsPolicy(
                amberWorkingDays = 7,
                staleWorkingDays = 30,
                dueChange = DueChange.CHAIR_AGREES,
                verifiedBy = ActionVerifier.CHAIR,
                evidenceRequired = true,
                defaultDue = DefaultDue.WORKING_DAYS,
                defaultDueWorkingDays = 15,
                updateRequestWorkingDays = 3,
            )
        val saved = asUser(admin) { lifecycle.replace(id, 1, draft.revision, draft.settings.copy(actions = first)) }
        val row = Audit.assertAudited(tenant.id, "forum.type.version.saved", id.toString(), admin.id.value.toString())
        assertThat(row.canonical).contains("amberWorkingDays=7", "CHAIR_AGREES", "evidenceRequired=true", "definitionKey=action-item")
        asUser(admin) { lifecycle.submit(id, 1, saved.revision) }
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        // The submitter cannot publish it.
        assertThatThrownBy { asUser(admin) { lifecycle.approve(id, 1, null) } }.isInstanceOf(RuntimeException::class.java)
        assertThat(asUser(checker) { lifecycle.approve(id, 1, null) }.state).isEqualTo(ReviewState.PUBLISHED)
        Audit.assertAudited(tenant.id, "forum.type.version.published", id.toString(), checker.id.value.toString())

        val forum = asUser(admin) { forums.create(ForumInput("brc-actions", "BRCA", "Board Risk Committee", "Risk", id)) }
        assertThat(asUser(admin) { policies.current(forum.id) }?.actions).isEqualTo(first)

        val next = asUser(admin) { lifecycle.newDraft(id) }
        assertThat(next.settings.actions).describedAs("a new draft copies the published policy").isEqualTo(first)
        val second = ActionsPolicy(amberWorkingDays = 5, dueChange = DueChange.OWNER_WITH_REASON)
        val changed = asUser(admin) { lifecycle.replace(id, 2, next.revision, next.settings.copy(actions = second)) }
        assertThat(asUser(admin) { policies.current(forum.id) }?.actions).describedAs("a draft is not in force").isEqualTo(first)
        asUser(admin) { lifecycle.submit(id, 2, changed.revision) }
        asUser(checker) { lifecycle.approve(id, 2, null) }

        val current = asUser(admin) { policies.current(forum.id) }
        assertThat(current?.version).isEqualTo(2)
        assertThat(current?.actions).isEqualTo(second)
        assertThat(asUser(admin) { policies.version(id, 1) }?.actions).describedAs("version 1, as a meeting pinned it").isEqualTo(first)
        assertThat(asUser(admin) { policies.current(UUID.randomUUID()) }).isNull()
    }

    @Test
    fun `a committee type may name another definition for action items, listed for the tab, and what it names is not retired`() {
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        val body = json.readTree(ACTION_FAST)
        val created = asUser(admin) { definitions.create("action-fast", "Quick actions", null, false, body) }
        asUser(admin) { definitions.submit("action-fast", 1, created.version.revision) }
        asUser(checker) { definitions.approve("action-fast", 1, null) }

        val session = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        val listed = read("/api/v1/admin/forum-types/action-definitions", session)
        assertThat(listed).hasStatusOk()
        val keys = listed.json().let { list -> (0 until list.size()).map { list[it]["key"].asString() } }
        assertThat(keys).containsExactlyInAnyOrder("action-item", "action-fast")

        val id = type("FAST")
        val draft = asUser(admin) { lifecycle.get(id, 1) }
        val fast = ActionsPolicy(definitionKey = "action-fast")
        asUser(admin) { lifecycle.replace(id, 1, draft.revision, draft.settings.copy(actions = fast)) }

        val retire = { key: String -> runCatching { asUser(checker) { definitions.retire(key, 1) } }.exceptionOrNull() }
        assertThat((retire("action-fast") as RefusedException).reason).describedAs("named by a draft").isEqualTo("IN_USE")
        // Nothing names the built-in definition yet in this tenant; a type with the defaults does.
        assertThat(retire("action-item")).describedAs("unused, so retired").isNull()
        val item = asUser(admin) { definitions.newDraft("action-item", null) }
        asUser(admin) { definitions.submit("action-item", 2, item.version.revision) }
        asUser(checker) { definitions.approve("action-item", 2, null) }
        type("DEF")
        val again = runCatching { asUser(checker) { definitions.retire("action-item", 2) } }.exceptionOrNull()
        assertThat((again as RefusedException).reason).describedAs("named by default").isEqualTo("IN_USE")
    }

    private fun problems(block: () -> Any): String {
        val rejected = runCatching { asUser(admin, block) }.exceptionOrNull() as? ForumRejectedException ?: error("expected a rejection")
        return rejected.problems.joinToString { "${it.field}:${it.code}" }
    }

    private companion object {
        /** A tenant's own definition for action items: the built-in one without reminders. */
        const val ACTION_FAST =
            """{"schemaVersion":2,"subject":"action-item","start":"owner","steps":[
              {"id":"owner","type":"review","name":"Owner","assignee":{"kind":"subject","slot":"owner"},"actions":["RECOMMEND"],
               "sla":{"from":"subject"},"next":"verify"},
              {"id":"verify","type":"approve","name":"Verification","assignee":{"kind":"subject","slot":"verifier"},
               "actions":["APPROVE","SEND_BACK"],"remarks":["SEND_BACK"],"next":"end"}]}"""
    }
}
