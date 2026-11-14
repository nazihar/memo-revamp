package com.memo.memo

import com.memo.identity.UserAccount
import com.memo.memo.internal.FieldChoice
import com.memo.memo.internal.FieldRules
import com.memo.memo.internal.FieldType
import com.memo.memo.internal.MemoField
import com.memo.memo.internal.MemoRejectedException
import com.memo.memo.internal.MemoTypeFields
import com.memo.memo.internal.MemoTypeLifecycle
import com.memo.memo.internal.MemoTypeSettings
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.RaiseScope
import com.memo.memo.internal.RoutePolicy
import com.memo.memo.internal.ScopeUnit
import com.memo.org.internal.Assignments
import com.memo.org.internal.LegalEntities
import com.memo.org.internal.OrgUnits
import com.memo.org.internal.Period
import com.memo.org.internal.Positions
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

/**
 * Memo metadata (test plan E01, T2; ADR-0043): a type's fields are checked when a version is saved; a draft keeps
 * incomplete values checked by type; submit asks for the required ones and pins the memo to its type version, after
 * which neither the API nor the database lets the details change, and later versions do not change what the memo shows.
 * Values for fields a type dropped stay on a draft until submit. Who may raise a type follows its scope.
 */
class MemoMetadataIT : MemoIntegrationTest() {
    @Autowired lateinit var types: MemoTypes

    @Autowired lateinit var lifecycle: MemoTypeLifecycle

    @Autowired lateinit var entities: LegalEntities

    @Autowired lateinit var units: OrgUnits

    @Autowired lateinit var positions: Positions

    @Autowired lateinit var assignments: Assignments

    private lateinit var checker: UserAccount
    private lateinit var typeId: UUID

    private val amount =
        MemoField(
            "amount",
            "Amount",
            type = FieldType.AMOUNT,
            required = true,
            rules = FieldRules(currencies = listOf("BDT", "USD"), min = BigDecimal.ZERO),
            attribute = true,
        )
    private val purpose =
        MemoField(
            "purpose",
            "Purpose",
            type = FieldType.CHOICE,
            required = true,
            rules = FieldRules(choices = listOf(FieldChoice("working-capital", "Working capital"), FieldChoice("term-loan", "Term loan"))),
        )
    private val expected = MemoField("expected", "Expected date", type = FieldType.DATE)
    private val note = MemoField("note", "Note", type = FieldType.LONG_TEXT)

    @BeforeEach
    fun creditType() {
        checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        typeId = asUser(admin) { types.create(MemoTypeFields("Credit facility", null, stepUpOnApprove = false, active = true)) }.id
        publish(listOf(amount, purpose, expected, note))
    }

    @Test
    fun `field lists are checked when a version is saved`() {
        val bad =
            listOf(
                MemoField("Amount", "Amount", type = FieldType.TEXT),
                MemoField("subject", "Subject", type = FieldType.TEXT),
                MemoField("total", "Total", type = FieldType.AMOUNT, rules = FieldRules(currencies = listOf("XXZ"))),
                MemoField(
                    "total",
                    "",
                    type = FieldType.CHOICE,
                    rules = FieldRules(choices = listOf(FieldChoice("a", "A"), FieldChoice("a", "B"))),
                ),
                MemoField("size", "Size", type = FieldType.NUMBER, rules = FieldRules(min = BigDecimal.TEN, max = BigDecimal.ONE)),
            )
        val problems =
            (runCatching { asUser(admin) { lifecycle.newDraft(typeId, settings(bad)) } }.exceptionOrNull() as MemoRejectedException)
                .problems
                .map { "${it.field}:${it.code}" }
        assertThat(problems).containsExactlyInAnyOrder(
            "fields/0/key:FORMAT",
            "fields/1/key:RESERVED",
            "fields/2/key:DUPLICATE",
            "fields/2/rules/currencies/0:UNKNOWN_CURRENCY",
            "fields/3/key:DUPLICATE",
            "fields/3/label:LENGTH",
            "fields/3/rules/choices/0/key:DUPLICATE",
            "fields/3/rules/choices/1/key:DUPLICATE",
            "fields/4/rules/min:RANGE",
        )
    }

    @Test
    fun `a draft keeps incomplete details, submit needs the required ones and pins the memo`() {
        val alice = person()
        val bob = person()
        val bad = create(alice.session, draft("""{"amount":{"value":"lots"},"colour":"red"}"""))
        assertThat(bad).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(bad.errors()).containsExactlyInAnyOrder("metadata.colour:UNKNOWN_FIELD", "metadata.amount:FIELD_INVALID")

        val created = create(alice.session, draft("""{"amount":{"value":"6000000","currency":"usd"},"expected":"2026-11-30"}"""))
        assertThat(created).hasStatus(HttpStatus.CREATED)
        val id = created.json()["id"].asString()
        val details = created.json()["details"]
        assertThat(details[0]["key"].asString()).isEqualTo("amount")
        assertThat(details[0]["value"]["value"].asString()).isEqualTo("6000000.00")
        assertThat(details[0]["value"]["currency"].asString()).isEqualTo("USD")
        assertThat(details[1]["value"].asString()).isEqualTo("2026-11-30")
        assertThat(created.json().absent("typeVersion")).isTrue()

        assertThat(uploadDocument(id, alice.session)).hasStatus(HttpStatus.CREATED)
        assertThat(route(id, alice, bob)).hasStatusOk()
        val missing = post("/api/v1/memos/$id/submit", alice.session)
        assertThat(missing).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(missing.errors()).containsExactly("metadata.purpose:FIELD_REQUIRED")

        val version = get(id, alice.session).json()["version"].asLong()
        val filled =
            put(
                "/api/v1/memos/$id",
                alice.session,
                memo("""{"amount":{"value":"6000000","currency":"USD"},"purpose":"term-loan","expected":"2026-11-30"}"""),
                version,
            )
        assertThat(filled).hasStatusOk()
        assertThat(filled.json()["details"][1]["display"].asString()).isEqualTo("Term loan")
        val submitted = post("/api/v1/memos/$id/submit", alice.session)
        assertThat(submitted).hasStatusOk()
        assertThat(submitted.json()["typeVersion"].asInt()).isEqualTo(2)
        Audit.assertAudited(tenant.id, "memo.type_pinned", id, alice.id.toString())

        // A later version changes nothing on the submitted memo.
        publish(listOf(purpose, MemoField("tenor", "Tenor (months)", type = FieldType.NUMBER, required = true)))
        val after = get(id, bob.session).json()
        assertThat(after["typeVersion"].asInt()).isEqualTo(2)
        assertThat((0 until after["details"].size()).map { after["details"][it]["key"].asString() })
            .containsExactly("amount", "purpose", "expected")

        // Neither the API nor the database changes a pinned memo's details.
        val locked = put("/api/v1/memos/$id", alice.session, memo("""{"purpose":"working-capital"}"""), after["version"].asLong())
        assertThat(locked).hasStatus(HttpStatus.CONFLICT)
        assertThatThrownBy {
            AppRoleJdbc.inTransaction(tenant.id) { c ->
                c.prepareStatement("update memo set metadata = '{}'::jsonb where id = ?::uuid").use {
                    it.setString(1, id)
                    it.executeUpdate()
                }
            }
        }.isInstanceOf(SQLException::class.java).hasMessageContaining("pinned")
    }

    @Test
    fun `values for fields a type dropped stay on the draft until submit, which drops and records them`() {
        val alice = person()
        val bob = person()
        val id =
            create(
                alice.session,
                draft("""{"amount":{"value":"100","currency":"BDT"},"purpose":"term-loan","note":"Collateral to follow"}"""),
            ).json()["id"].asString()
        publish(listOf(amount, purpose))

        val draft = get(id, alice.session).json()
        assertThat(draft["staleDetails"][0]["key"].asString()).isEqualTo("note")
        assertThat(draft["staleDetails"][0]["label"].asString()).isEqualTo("Note")
        assertThat(draft["details"].size()).isEqualTo(2)
        // Sending the form back with the stale value unchanged is not an unknown field.
        val saved =
            put(
                "/api/v1/memos/$id",
                alice.session,
                memo("""{"amount":{"value":"100","currency":"BDT"},"purpose":"term-loan","note":"Collateral to follow"}"""),
                draft["version"].asLong(),
            )
        assertThat(saved).hasStatusOk()

        uploadDocument(id, alice.session)
        route(id, alice, bob)
        val submitted = post("/api/v1/memos/$id/submit", alice.session).json()
        assertThat(submitted["staleDetails"].size()).isZero()
        assertThat(submitted["typeVersion"].asInt()).isEqualTo(3)
        val pinned = Audit.entries(tenant.id).single { it.action == "memo.type_pinned" && it.objectId == id }
        assertThat(pinned.canonical).contains("dropped").contains("note")
    }

    @Test
    fun `only people in a type's scope see it and raise memos of it`() {
        val jan1 = LocalDate.of(2026, 1, 1)
        val (credit, branch) =
            asUser(admin) {
                val entity = entities.create("Bank ${UUID.randomUUID().toString().take(6)}", null, null, jan1)
                val head = units.createUnit(entity.id, null, "HO", "Head office", jan1)
                val credit = units.createUnit(entity.id, head.id, "HO-CR", "Credit", jan1)
                val branch = units.createUnit(entity.id, head.id, "BR-1", "Branch 1", jan1)
                credit to branch
            }
        val insider = person()
        val outsider = person()
        asUser(admin) {
            assignments.assign(positions.create(credit.id, null, "Credit officer", false, jan1).id, insider.id, Period(jan1, null))
            assignments.assign(positions.create(branch.id, null, "Teller", false, jan1).id, outsider.id, Period(jan1, null))
        }
        publish(listOf(amount), RaiseScope(units = listOf(ScopeUnit(credit.id, subtree = true))))

        fun names(person: com.memo.testsupport.Person) =
            read("/api/v1/memo-types", person.session).json().let { all -> (0 until all.size()).map { all[it]["name"].asString() } }
        assertThat(names(insider)).contains("Credit facility")
        assertThat(names(outsider)).doesNotContain("Credit facility")
        assertThat(read("/api/v1/memo-types/$typeId/form", insider.session).json()["fields"][0]["key"].asString()).isEqualTo("amount")
        assertThat(read("/api/v1/memo-types/$typeId/form", outsider.session)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(create(outsider.session, draft("{}")).errors()).containsExactly("typeId:NOT_ALLOWED_TO_RAISE")

        val memo = create(insider.session, draft("{}")).json()
        assertThat(memo["originUnit"]["name"].asString()).isEqualTo("Credit")
    }

    private fun publish(
        fields: List<MemoField>,
        scope: RaiseScope = RaiseScope(),
    ) {
        val draft = asUser(admin) { lifecycle.newDraft(typeId, settings(fields, scope)) }
        val submitted = asUser(admin) { lifecycle.submit(typeId, draft.number, draft.revision) }
        asUser(checker) { lifecycle.approve(typeId, submitted.number, null) }
    }

    private fun settings(
        fields: List<MemoField>,
        scope: RaiseScope = RaiseScope(),
    ) = MemoTypeSettings(
        "Credit facility",
        "CF",
        null,
        "sequential-approval",
        RoutePolicy.INITIATOR_DEFINED,
        stepUpOnApprove = false,
        fields = fields.map { it.normalized() },
        raiseScope = scope,
    )

    private fun draft(metadata: String) = """{"subject":"Facility","typeId":"$typeId","metadata":$metadata}"""

    private fun memo(metadata: String) = draft(metadata)
}
