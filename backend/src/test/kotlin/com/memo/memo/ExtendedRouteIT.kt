package com.memo.memo

import com.memo.memo.internal.AllowedApprovers
import com.memo.memo.internal.MemoTypeFields
import com.memo.memo.internal.MemoTypes
import com.memo.org.OrgDirectory
import com.memo.org.OrgSeeding
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.TestWorkflowSubject
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import com.memo.workflow.WorkflowHandOns
import com.memo.workflow.WorkflowRefusedException
import com.memo.workflow.Workflows
import com.memo.workflow.internal.DefinitionLifecycle
import com.memo.workflow.internal.DoaLifecycle
import com.memo.workflow.internal.DoaMember
import com.memo.workflow.internal.DoaRule
import com.memo.workflow.internal.DoaRuleGroup
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.time.LocalDate
import java.util.UUID

/**
 * Initiator routes with positions and groups, memo type policies and forward limits (design E04 §4.7, test plan T7): a
 * position or a group on the route resolves when reached; a memo type may limit who is on its routes and require the
 * route to satisfy the DoA, which the initiator can preview; a step may keep forwarding within the holder's unit.
 */
class ExtendedRouteIT : MemoIntegrationTest() {
    @Autowired lateinit var seeding: OrgSeeding

    @Autowired lateinit var types: MemoTypes

    @Autowired lateinit var doa: DoaLifecycle

    @Autowired lateinit var definitions: DefinitionLifecycle

    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var org: OrgDirectory

    @Autowired lateinit var handOns: WorkflowHandOns

    private val start = LocalDate.of(2026, 1, 1)
    private val entity = UUID.randomUUID()
    private val credit = UUID.randomUUID()
    private val retail = UUID.randomUUID()
    private val gm = UUID.randomUUID()
    private val dmd = UUID.randomUUID()
    private val amd = UUID.randomUUID()
    private val clerk = UUID.randomUUID()
    private lateinit var alice: Person
    private lateinit var bob: Person
    private lateinit var carol: Person
    private lateinit var dave: Person
    private lateinit var erin: Person

    @BeforeEach
    fun organisation() {
        alice = person()
        bob = person()
        carol = person()
        dave = person()
        erin = person()
        asUser(admin) {
            seeding.legalEntity(entity, "Bank", start)
            seeding.unit(credit, entity, null, "CR", "Credit", start)
            seeding.unit(retail, entity, null, "RT", "Retail", start)
            seeding.position(gm, credit, "GM Credit", head = true, start)
            seeding.position(dmd, credit, "DMD", head = false, start)
            seeding.position(amd, credit, "AMD", head = false, start)
            seeding.position(clerk, retail, "Clerk", head = true, start)
            seeding.assignment(gm, bob.id, start)
            seeding.assignment(dmd, dave.id, start)
            seeding.assignment(amd, erin.id, start)
            seeding.assignment(clerk, carol.id, start)
        }
    }

    @Test
    fun `a position and a group on the route resolve when they are reached`() {
        val id = aDraft(alice)
        uploadDocument(id, alice.session)
        val body =
            """{"entries":[{"positionId":"$gm"},
               {"group":{"mode":"ANY","members":[{"userId":"${carol.id}"},{"positionId":"$dmd"}]}}]}"""
        val routed = put("/api/v1/memos/$id/route", alice.session, body, get(id, alice.session).json()["version"].asLong())
        assertThat(routed).hasStatusOk()
        val route = routed.json()["route"]
        assertThat(route[0]["position"]["holder"]["id"].asString()).isEqualTo(bob.id.toString())
        assertThat(route[1]["group"]["members"].size()).isEqualTo(2)

        assertThat(post("/api/v1/memos/$id/submit", alice.session)).hasStatusOk()
        approve(id, bob)
        assertThat(holders(id)).containsExactlyInAnyOrder(carol.id, dave.id)
        approve(id, dave)
        assertThat(get(id, alice.session).json()["status"].asString()).isEqualTo("APPROVED")
    }

    @Test
    fun `a memo type limits its approvers and requires the route to satisfy the DoA, which the initiator can preview`() {
        val type =
            asUser(admin) {
                types.create(
                    MemoTypeFields(
                        "Credit",
                        null,
                        false,
                        true,
                        allowedApprovers = AllowedApprovers(positions = listOf(gm, dmd, amd)),
                        doaCheck = true,
                    ),
                )
            }
        val level = levels()[0].id
        val id = create(alice.session, """{"subject":"Loan","typeId":"${type.id}","classificationId":"$level"}""").json()["id"].asString()
        uploadDocument(id, alice.session)
        assertThat(route(id, alice, carol).errors()).containsExactly("route[0]:not-allowed")
        assertThat(route(id, alice, bob)).hasStatusOk()

        publishDoa()
        assertThat(post("/api/v1/memos/$id/submit", alice.session).errors()).containsExactly("route:doa-missing")
        val preview = post("/api/v1/memos/$id/doa-preview", alice.session).json()
        assertThat(preview["groups"][0]["needed"].asInt()).isEqualTo(2)
        assertThat(preview["groups"][0]["covered"].asInt()).isZero()
        assertThat(post("/api/v1/memos/$id/doa-preview", bob.session)).hasStatus(HttpStatus.NOT_FOUND)

        assertThat(route(id, alice, dave, erin)).hasStatusOk()
        assertThat(post("/api/v1/memos/$id/submit", alice.session)).hasStatusOk()
    }

    @Test
    fun `a step may keep forwarding within the holder's unit`() {
        val body =
            """{"schemaVersion":2,"start":"gm","steps":[{"id":"gm","type":"approve","name":"GM",
               "assignee":{"kind":"position","positionId":"$gm"},"forward":{"within":"unit"},"actions":["APPROVE","RETURN","REJECT","FORWARD"],"next":"end"}]}"""
        val draft = asUser(admin) { definitions.create("gm-unit", "GM", null, false, json.readTree(body)) }
        asUser(admin) { definitions.submit("gm-unit", 1, draft.version.revision) }
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        asUser(checker) { definitions.approve("gm-unit", 1, null) }
        val started =
            asUser(alice.account) { workflows.start(TestWorkflowSubject.TYPE, UUID.randomUUID(), emptyList(), definitionKey = "gm-unit") }
        val task = checkNotNull(started.openTaskId)

        assertThatThrownBy { asUser(bob.account) { handOns.forward(task, carol.id, "Please look") } }
            .isInstanceOfSatisfying(WorkflowRefusedException::class.java) { assertThat(it.reason).isEqualTo("FORWARD_OUTSIDE_LIMIT") }
        val forwarded = asUser(bob.account) { handOns.forward(task, dave.id, "Please look") }
        assertThat(forwarded.currentAssignees).containsExactly(dave.id)
    }

    private fun publishDoa() {
        val rule =
            DoaRule(
                name = "Credit",
                subjectType = "memo",
                groups = listOf(DoaRuleGroup(2, listOf(DoaMember("position", positionId = dmd), DoaMember("position", positionId = amd)))),
            )
        val today = asUser(admin) { org.today() }
        val draft = asUser(admin) { doa.create(today, listOf(rule)) }
        asUser(admin) { doa.submit(1, draft.version.revision) }
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        asUser(checker) { doa.approve(1, null) }
    }

    private fun approve(
        id: String,
        approver: Person,
    ) {
        val task = get(id, approver.session).json()["myTask"]["id"].asString()
        assertThat(post("/api/v1/tasks/$task/approve", approver.session)).hasStatusOk()
    }

    private fun holders(id: String): List<UUID> {
        val holders = get(id, alice.session).json()["holders"]
        return (0 until holders.size()).map { UUID.fromString(holders[it]["id"].asString()) }
    }
}
