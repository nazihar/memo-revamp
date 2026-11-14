package com.memo.org

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.identity.UserLifecycle
import com.memo.org.internal.Assignments
import com.memo.org.internal.LegalEntities
import com.memo.org.internal.OrgUnits
import com.memo.org.internal.Period
import com.memo.org.internal.Positions
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.onHost
import com.memo.testsupport.signIn
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.time.LocalDate
import java.util.UUID

/** The people picker (design E04 §6, research R14; test plan T6). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DirectorySearchIT : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var lifecycle: UserLifecycle

    @Autowired lateinit var entities: LegalEntities

    @Autowired lateinit var units: OrgUnits

    @Autowired lateinit var positions: Positions

    @Autowired lateinit var assignments: Assignments

    private val password = aPassword()

    private val tenant: TestTenant by lazy { registry.aTestTenant(transactions, settings, "picker", mfaRequired = false) }

    private val admin: UserAccount by lazy { person("Picker Admin", tenantAdmin = true) }

    private val unique = UUID.randomUUID().toString().take(6)

    private fun person(
        name: String,
        on: TestTenant = tenant,
        tenantAdmin: Boolean = false,
    ): UserAccount =
        transactions.inTenant(on.id) {
            val user = accounts.create("p-${UUID.randomUUID().toString().take(8)}@bank.example", name, tenantAdmin)
            accounts.setPassword(user.id, password)
        }

    private fun <T> asAdmin(block: () -> T): T =
        ActorContext.call(ActorScope(Actor.User(admin.id.value), null)) {
            TenantContext.call(tenant.id) { transactions.inTenant(tenant.id, block) }
        }

    private fun search(
        session: Cookie,
        q: String,
        kind: String? = null,
    ): MvcTestResult =
        mvc
            .get()
            .uri("/api/v1/directory/search")
            .param("q", q)
            .apply { if (kind != null) param("kind", kind) }
            .with(onHost(tenant.slug))
            .cookie(session)
            .exchange()

    @Test
    fun `people and positions are found by the start of any word, with today's holder and position`() {
        val nadia = person("Nadia Karim$unique")
        person("Karim$unique Nadia")
        val seat =
            asAdmin {
                val entity = entities.create("Bank $unique", null, null, LocalDate.of(2026, 1, 1))
                val unit = units.createUnit(entity.id, null, "U$unique", "Treasury $unique", LocalDate.of(2026, 1, 1))
                positions.create(unit.id, null, "Head of Dealing$unique", false, LocalDate.of(2026, 1, 1))
            }
        asAdmin { assignments.assign(seat.id, nadia.id.value, Period(LocalDate.of(2026, 1, 1), null)) }
        val session = mvc.signIn(tenant, person("Searcher").email, password)

        val byWord = search(session, "karim$unique")
        assertThat(byWord).hasStatus(HttpStatus.OK)
        assertThat(byWord)
            .bodyJson()
            .extractingPath("$.users[*].person.displayName")
            .asArray()
            .containsExactlyInAnyOrder("Nadia Karim$unique", "Karim$unique Nadia")
        assertThat(byWord)
            .bodyJson()
            .extractingPath("$.users[?(@.person.displayName == 'Nadia Karim$unique')].position.title")
            .asArray()
            .containsExactly("Head of Dealing$unique")

        val positionsOnly = search(session, "dealing$unique", "position")
        assertThat(positionsOnly)
            .bodyJson()
            .extractingPath("$.users")
            .asArray()
            .isEmpty()
        assertThat(positionsOnly).bodyJson().extractingPath("$.positions[0].holder.displayName").isEqualTo("Nadia Karim$unique")
        assertThat(positionsOnly).bodyJson().extractingPath("$.positions[0].unitName").isEqualTo("Treasury $unique")
    }

    @Test
    fun `units are found by name or code only when asked for (Phase 3 E01 unit picker)`() {
        asAdmin {
            val entity = entities.create("Units $unique", null, null, LocalDate.of(2026, 1, 1))
            units.createUnit(entity.id, null, "LC$unique", "Loan Centre $unique", LocalDate.of(2026, 1, 1))
        }
        val session = mvc.signIn(tenant, person("Unit Searcher").email, password)

        val byName = search(session, "centre $unique", "unit")
        assertThat(byName)
            .bodyJson()
            .extractingPath("$.units[*].code")
            .asArray()
            .containsExactly("LC$unique")
        assertThat(byName)
            .bodyJson()
            .extractingPath("$.users")
            .asArray()
            .isEmpty()
        assertThat(search(session, "lc$unique", "unit")).bodyJson().extractingPath("$.units[0].name").isEqualTo("Loan Centre $unique")
        assertThat(search(session, "centre $unique"))
            .bodyJson()
            .extractingPath("$.units")
            .asArray()
            .isEmpty()
        assertThat(search(session, "centre $unique", "team")).hasStatus(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `Arabic names match without tatweel, and deactivated people are not offered`() {
        person("أحمد السيد$unique")
        val gone = person("Gone Person$unique")
        asAdmin { lifecycle.deactivate(gone.id, gone.version, admin.id) }
        val session = mvc.signIn(tenant, person("Searcher").email, password)

        assertThat(search(session, "أحـــمد"))
            .bodyJson()
            .extractingPath("$.users[*].person.displayName")
            .asArray()
            .contains("أحمد السيد$unique")
        assertThat(search(session, "gone person$unique"))
            .bodyJson()
            .extractingPath("$.users")
            .asArray()
            .isEmpty()
    }

    @Test
    fun `another organisation's people are never found, and short queries are refused`() {
        val other = registry.aTestTenant(transactions, settings, "picker-other", mfaRequired = false)
        person("Hidden Elsewhere$unique", on = other)
        val session = mvc.signIn(tenant, person("Searcher").email, password)

        assertThat(search(session, "hidden elsewhere$unique"))
            .bodyJson()
            .extractingPath("$.users")
            .asArray()
            .isEmpty()
        assertThat(search(session, "h")).hasStatus(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `searching is limited to 60 a minute per person, refilled smoothly`() {
        val session = mvc.signIn(tenant, person("Busy Searcher").email, password)

        // The bucket refills one search a second, so a burst of 80 runs out somewhere after the 60th.
        val statuses = (1..80).map { search(session, "zz$unique").response.status }

        assertThat(statuses.take(60)).allMatch { it == 200 }
        assertThat(statuses).contains(429)
    }
}
