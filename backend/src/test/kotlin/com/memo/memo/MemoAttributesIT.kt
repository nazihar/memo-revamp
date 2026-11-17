package com.memo.memo

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.identity.UserAccount
import com.memo.memo.internal.FieldChoice
import com.memo.memo.internal.FieldRules
import com.memo.memo.internal.FieldType
import com.memo.memo.internal.MemoAttributes
import com.memo.memo.internal.MemoField
import com.memo.memo.internal.MemoTypeChecks
import com.memo.memo.internal.MemoTypeFields
import com.memo.memo.internal.MemoTypeLifecycle
import com.memo.memo.internal.MemoTypeSettings
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.RoutePolicy
import com.memo.org.OrgDirectory
import com.memo.org.internal.Assignments
import com.memo.org.internal.LegalEntities
import com.memo.org.internal.OrgUnits
import com.memo.org.internal.Period
import com.memo.org.internal.Positions
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import com.memo.workflow.AttributeType
import com.memo.workflow.WorkflowAuthority
import com.memo.workflow.internal.BuilderCatalogueController
import com.memo.workflow.internal.DoaLifecycle
import com.memo.workflow.internal.DoaMember
import com.memo.workflow.internal.DoaRule
import com.memo.workflow.internal.DoaRuleGroup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Fields as workflow attributes (test plan E01, T3; ADR-0043): a type's attribute fields join the memo catalogue when its
 * version is published (the builder and DoA rules see them); a DoA band reads a memo's amount; a key means one type across
 * the tenant; and a version cannot drop an attribute a published DoA rule reads.
 */
class MemoAttributesIT : MemoIntegrationTest() {
    @Autowired lateinit var types: MemoTypes

    @Autowired lateinit var lifecycle: MemoTypeLifecycle

    @Autowired lateinit var attributes: MemoAttributes

    @Autowired lateinit var checks: MemoTypeChecks

    @Autowired lateinit var builder: BuilderCatalogueController

    @Autowired lateinit var doa: DoaLifecycle

    @Autowired lateinit var authority: WorkflowAuthority

    @Autowired lateinit var directory: OrgDirectory

    @Autowired lateinit var entities: LegalEntities

    @Autowired lateinit var units: OrgUnits

    @Autowired lateinit var positions: Positions

    @Autowired lateinit var assignments: Assignments

    private lateinit var checker: UserAccount
    private lateinit var credit: UUID

    private val amount =
        MemoField(
            "amount",
            "Amount",
            type = FieldType.AMOUNT,
            rules = FieldRules(currencies = listOf("BDT"), min = BigDecimal.ZERO),
            attribute = true,
        )
    private val sector =
        MemoField(
            "sector",
            "Sector",
            type = FieldType.CHOICE,
            rules = FieldRules(choices = listOf(FieldChoice("sme", "SME"), FieldChoice("corporate", "Corporate"))),
            attribute = true,
        )

    @BeforeEach
    fun creditType() {
        checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        credit = asUser(admin) { types.create(MemoTypeFields("Credit facility", null, stepUpOnApprove = false, active = true)) }.id
    }

    @Test
    fun `published attribute fields join the catalogue the builder and DoA rules use`() {
        assertThat(asUser(admin) { attributes.catalogue() }).doesNotContainKey("amount")
        publish(credit, listOf(amount, sector))
        assertThat(asUser(admin) { attributes.catalogue() })
            .containsEntry("amount", AttributeType.DOUBLE)
            .containsEntry("amountCurrency", AttributeType.STRING)
            .containsEntry("sector", AttributeType.STRING)
            .containsEntry("memoTypeId", AttributeType.STRING)

        val catalogue =
            ActorContext.call(
                ActorScope(Actor.User(admin.id.value), null),
            ) { TenantContext.call(tenant.id) { builder.catalogue() } }
        val memo = checkNotNull(catalogue.body).subjectTypes.first { it.type == "memo" }
        assertThat(memo.attributes.map { it.name }).contains("amount", "amountCurrency", "sector")
    }

    @Test
    fun `a DoA band reads the memo's amount, and memos without it never match`() {
        publish(credit, listOf(amount))
        val director = person()
        val today = asUser(admin) { directory.today() }
        val seat =
            asUser(admin) {
                val entity = entities.create("Bank ${UUID.randomUUID().toString().take(6)}", null, null, today)
                val unit = units.createUnit(entity.id, null, "HO", "Head office", today)
                positions.create(unit.id, null, "Managing Director", true, today)
            }
        asUser(admin) { assignments.assign(seat.id, director.id, Period(today, null)) }
        val rule =
            DoaRule(
                name = "Large credit",
                subjectType = "memo",
                memoTypeId = credit,
                condition = "item.amount >= 5000000.0",
                groups = listOf(DoaRuleGroup(1, listOf(DoaMember("position", positionId = seat.id)))),
            )
        val draft = asUser(admin) { doa.create(today, listOf(rule)) }
        asUser(admin) { doa.submit(draft.version.number, draft.version.revision) }
        asUser(checker) { doa.approve(draft.version.number, null) }

        val alice = person()

        fun memo(value: String?): UUID {
            val metadata = value?.let { """{"amount":{"value":"$it","currency":"BDT"}}""" } ?: "{}"
            return UUID.fromString(
                create(alice.session, """{"subject":"Facility","typeId":"$credit","metadata":$metadata}""").json()["id"].asString(),
            )
        }
        val large = memo("6000000")
        val small = memo("4000000")
        val none = memo(null)

        fun gaps(id: UUID) = asUser(alice.account) { authority.shortfalls("memo", id, alice.id, emptyList()) }
        assertThat(gaps(large).single().people).containsExactly(director.id)
        assertThat(gaps(small)).isEmpty()
        assertThat(gaps(none)).isEmpty()

        // The rule reads item.amount: a version without it is refused.
        assertThat(problems(credit, listOf(sector))).contains("fields:ATTRIBUTE_IN_USE")
        assertThat(problems(credit, listOf(amount.copy(attribute = false), sector))).contains("fields:ATTRIBUTE_IN_USE")
    }

    @Test
    fun `an attribute key has one type across the tenant, and an amount's currency name stays free`() {
        publish(credit, listOf(amount, sector))
        val other = asUser(admin) { types.create(MemoTypeFields("Expense approval", null, stepUpOnApprove = false, active = true)) }.id
        assertThat(problems(other, listOf(MemoField("amount", "Amount", type = FieldType.TEXT, attribute = true))))
            .containsExactly("fields/0/attribute:ATTRIBUTE_CONFLICT")
        // The same key with the same type is fine, and so is a non-attribute field of another type.
        assertThat(problems(other, listOf(MemoField("amount", "Amount", type = FieldType.NUMBER, attribute = true)))).isEmpty()
        assertThat(problems(other, listOf(MemoField("amount", "Amount", type = FieldType.TEXT)))).isEmpty()
        assertThat(problems(other, listOf(amount, MemoField("amountCurrency", "Currency", type = FieldType.TEXT))))
            .containsExactly("fields/0/key:ATTRIBUTE_CONFLICT")
    }

    private fun publish(
        typeId: UUID,
        fields: List<MemoField>,
    ) {
        val draft = asUser(admin) { lifecycle.newDraft(typeId, settings(typeId, fields)) }
        val submitted = asUser(admin) { lifecycle.submit(typeId, draft.number, draft.revision) }
        asUser(checker) { lifecycle.approve(typeId, submitted.number, null) }
    }

    private fun problems(
        typeId: UUID,
        fields: List<MemoField>,
    ): List<String> = asUser(admin) { checks.problems(settings(typeId, fields), typeId) }.map { "${it.field}:${it.code}" }

    private fun settings(
        typeId: UUID,
        fields: List<MemoField>,
    ): MemoTypeSettings {
        val type = asUser(admin) { types.get(typeId) }
        return MemoTypeSettings(
            type.name,
            type.code,
            null,
            "sequential-approval",
            RoutePolicy.INITIATOR_DEFINED,
            stepUpOnApprove = false,
            fields = fields.map { it.normalized() },
        )
    }
}
