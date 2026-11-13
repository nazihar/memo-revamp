package com.memo.workflow

import com.memo.common.tenant.TenantDsl
import com.memo.identity.MfaSeeding
import com.memo.jooq.tables.references.WORKFLOW_INSTANCE
import com.memo.jooq.tables.references.WORKFLOW_TASK
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.adminSession
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode

/**
 * The definition simulation (design E06 §4, test plan T5): a draft run against sample values with the organisation as it
 * is now. Branches follow the sample, people come with their names, an uncleared approver blocks the step, an invalid
 * body is refused with its problems, the limit applies per user, and nothing is ever written.
 */
class DefinitionSimulationIT : MemoIntegrationTest() {
    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Autowired lateinit var tenantDsl: TenantDsl

    private val base = "/api/v1/admin/workflow-definitions"
    private lateinit var session: Cookie
    private lateinit var author: Person
    private lateinit var reviewer: Person
    private lateinit var director: Person
    private lateinit var finance: Person

    @BeforeEach
    fun definition() {
        session = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        author = person()
        reviewer = person(clearance = 2)
        director = person(clearance = 2)
        finance = person(clearance = 2)
        assertThat(post(base, session, """{"key":"credit","name":"Credit","body":${body()}}""")).hasStatus(HttpStatus.CREATED)
    }

    @Test
    fun `a high classification goes through the committee, a low one to the director`() {
        val high = simulate(2)
        assertThat(high["ended"].asString()).isEqualTo("APPROVED")
        assertThat(ids(high)).containsExactly("review", "size", "committee", "tell")
        assertThat(people(high, "review")).containsExactly(reviewer.id.toString())
        assertThat(step(high, "size")["branch"].asInt()).isEqualTo(0)
        assertThat(step(high, "size")["condition"].asString()).isEqualTo("item.classificationRank >= 2")
        assertThat(people(high, "committee")).containsExactlyInAnyOrder(director.id.toString(), finance.id.toString())
        assertThat(step(high, "committee")["people"][0]["name"].asString()).isNotBlank()
        assertThat(step(high, "tell")["outcome"].asString()).isEqualTo("NOTIFIED")
        assertThat(people(high, "tell")).containsExactly(author.id.toString())

        val low = simulate(0)
        assertThat(ids(low)).containsExactly("review", "size", "director", "tell")
        assertThat(step(low, "size").has("branch")).isFalse()
        assertThat(step(low, "size")["next"].asString()).isEqualTo("director")
        assertThat(step(low, "director")["sla"]["due"].asString()).isEqualTo("P2D")
    }

    @Test
    fun `an approver not cleared for the sample blocks the step, and nothing is ever written`() {
        val audited = Audit.storedEntries(tenant.id).size
        clear(finance.id, 0)

        val blocked = simulate(2)
        assertThat(blocked["ended"].asString()).isEqualTo("BLOCKED")
        assertThat(step(blocked, "committee")["outcome"].asString()).isEqualTo("BLOCKED")
        assertThat(step(blocked, "committee")["blockedReason"].asString()).isEqualTo("NO_CLEARANCE")

        val invalid = """{"initiatorId":"${author.id}","body":{"schemaVersion":2,"start":"nowhere","steps":[]}}"""
        val refused = post("$base/credit/versions/1/simulate", session, invalid)
        assertThat(refused).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(refused.json()["reason"].asString()).isEqualTo("DEFINITION_INVALID")

        transactions.readInTenant(tenant.id) {
            assertThat(tenantDsl.current().fetchCount(WORKFLOW_INSTANCE)).isZero()
            assertThat(tenantDsl.current().fetchCount(WORKFLOW_TASK)).isZero()
        }
        // The clearance change is the only audit row since the start.
        assertThat(Audit.storedEntries(tenant.id).size).isEqualTo(audited + 1)
    }

    @Test
    fun `a version names the people its body mentions (E06 T1)`() {
        val labels = read("$base/credit/versions/1", session).json()["labels"]
        assertThat(labels[reviewer.id.toString()].asString()).isNotBlank()
        assertThat(labels[director.id.toString()].asString()).isNotBlank()
        assertThat(labels.has(author.id.toString())).isFalse()
    }

    @Test
    fun `simulations are limited per user`() {
        val statuses = (1..31).map { post("$base/credit/versions/1/simulate", session, request(0)).response.status }
        assertThat(statuses.take(30)).allMatch { it == HttpStatus.OK.value() }
        assertThat(statuses.last()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value())
    }

    private fun simulate(rank: Int): JsonNode {
        val result = post("$base/credit/versions/1/simulate", session, request(rank))
        assertThat(result).hasStatusOk()
        return result.json()
    }

    private fun request(rank: Int) = """{"initiatorId":"${author.id}","attributes":{"classificationRank":$rank}}"""

    private fun ids(simulation: JsonNode): List<String> = simulation["steps"].toList().map { it["stepId"].asString() }

    private fun step(
        simulation: JsonNode,
        id: String,
    ): JsonNode = simulation["steps"].first { it["stepId"].asString() == id }

    private fun people(
        simulation: JsonNode,
        id: String,
    ): List<String> = step(simulation, id)["people"].toList().map { it["id"].asString() }

    private fun body(): String {
        fun person(p: Person) = """{"kind":"person","userId":"${p.id}"}"""
        return """
            {"schemaVersion":2,"attributes":[{"name":"classificationRank","type":"int"}],"start":"review","steps":[
              {"id":"review","type":"review","name":"Review","assignee":${person(reviewer)},"actions":["RECOMMEND","RETURN"],
               "next":"size"},
              {"id":"size","type":"branch","name":"By classification",
               "branches":[{"when":"item.classificationRank >= 2","next":"committee"}],"otherwise":"director"},
              {"id":"committee","type":"parallel","name":"Committee","mode":"all",
               "assignees":[${person(director)},${person(finance)}],"next":"tell"},
              {"id":"director","type":"approve","name":"Director","assignee":${person(director)},"actions":["APPROVE","RETURN"],
               "sla":{"due":"P2D"},"next":"tell"},
              {"id":"tell","type":"notify","name":"Tell the author","recipients":[{"kind":"initiator"}],"message":"done","next":"end"}
            ]}
            """.trimIndent()
    }
}
