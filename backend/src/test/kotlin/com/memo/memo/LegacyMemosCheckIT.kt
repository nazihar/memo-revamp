package com.memo.memo

import com.memo.memo.internal.FieldChoice
import com.memo.memo.internal.FieldRules
import com.memo.memo.internal.FieldType
import com.memo.memo.internal.FinancialYearStyle
import com.memo.memo.internal.MemoField
import com.memo.memo.internal.MemoNumberStore
import com.memo.memo.internal.MemoTypeFields
import com.memo.memo.internal.MemoTypeLifecycle
import com.memo.memo.internal.MemoTypeSettings
import com.memo.memo.internal.NumberCountPer
import com.memo.memo.internal.NumberKey
import com.memo.memo.internal.NumberPart
import com.memo.memo.internal.NumberPartKind
import com.memo.memo.internal.NumberReset
import com.memo.memo.internal.Numbering
import com.memo.memo.internal.RoutePolicy
import com.memo.org.OrgDirectory
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The memo module's checks of a legacy row and its live number series (ADR-0055; test plan E06 T3 `LegacyMemosCheckIT`):
 * field values checked without `requireAll`, lengths, the mapped type, level and unit, dates, approvals, originals and
 * linked people, a number already in use; and each published format as a pattern, with whether its series may still
 * give a number (the current or a later period, above the counter). Checks read only: they run in a read-only transaction.
 */
class LegacyMemosCheckIT : ImportedMemoTest() {
    @Autowired lateinit var lifecycle: MemoTypeLifecycle

    @Autowired lateinit var numberStore: MemoNumberStore

    @Autowired lateinit var directory: OrgDirectory

    private fun check(row: LegacyMemoRow): List<String> =
        asJob { transactions.readInTenant(tenant.id) { legacy.check(row) } }.map { "${it.field}:${it.code}" }

    private fun aType(
        name: String,
        code: String,
        numbering: Numbering = Numbering(),
        fields: List<MemoField> = emptyList(),
    ): UUID {
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        val id = asUser(admin) { memoTypes.create(MemoTypeFields(name, null, stepUpOnApprove = false, active = true)) }.id
        val settings =
            MemoTypeSettings(
                name,
                code,
                null,
                "sequential-approval",
                RoutePolicy.INITIATOR_DEFINED,
                stepUpOnApprove = false,
                fields = fields,
                numbering = numbering,
            )
        val draft = asUser(admin) { lifecycle.newDraft(id, settings) }
        val submitted = asUser(admin) { lifecycle.submit(id, draft.number, draft.revision) }
        asUser(checker) { lifecycle.approve(id, submitted.number, null) }
        return id
    }

    @Test
    fun `a good row passes, and field values are checked without requiring the required ones`() {
        assertThat(check(row(stored = false))).isEmpty()
        val amount =
            MemoField("amount", "Amount", type = FieldType.AMOUNT, required = true, rules = FieldRules(currencies = listOf("BDT")))
        val purpose =
            MemoField(
                "purpose",
                "Purpose",
                type = FieldType.CHOICE,
                required = true,
                rules = FieldRules(choices = listOf(FieldChoice("wc", "WC"))),
            )
        val type = aType("Credit facility", "CF", fields = listOf(amount, purpose))
        val base = row(stored = false)

        fun typed(metadata: Map<String, Any?>) = base.copy(mapped = base.mapped.copy(typeId = type, metadata = metadata))
        assertThat(check(typed(emptyMap()))).isEmpty()
        assertThat(check(typed(mapOf("amount" to mapOf("value" to BigDecimal.TEN.toString(), "currency" to "BDT"))))).isEmpty()
        assertThat(check(typed(mapOf("amount" to mapOf("value" to "lots"), "colour" to "red"))))
            .containsExactlyInAnyOrder("field:amount:FIELD_INVALID", "field:colour:FIELD_INVALID")
    }

    @Test
    fun `lengths, the mapped type, level and unit, and a number already in use are refused`() {
        val unknownType = UUID.randomUUID()
        val base = row(stored = false)
        assertThat(check(base.copy(given = base.given.copy(subject = "x".repeat(301))))).containsExactly("subject:SUBJECT_TOO_LONG")
        assertThat(check(base.copy(given = base.given.copy(subject = " ")))).containsExactly("subject:VALUE_REQUIRED")
        assertThat(check(base.copy(initiator = LegacyPerson("")))).containsExactly("initiator/name:VALUE_REQUIRED")
        assertThat(
            check(base.copy(source = base.source.copy(legacyNumber = "9".repeat(101)))),
        ).containsExactly("legacy_number:NUMBER_TOO_LONG")
        assertThat(check(base.copy(source = base.source.copy(sourceCode = "legacy dms", rowNumber = 0))))
            .containsExactlyInAnyOrder("source_code:VALUE_INVALID", "row_number:VALUE_INVALID")
        assertThat(
            check(
                base.copy(
                    mapped = base.mapped.copy(typeId = unknownType, classificationId = UUID.randomUUID(), originUnitId = UUID.randomUUID()),
                ),
            ),
        ).containsExactlyInAnyOrder("memo_type:TYPE_UNMAPPED", "classification:CLASSIFICATION_UNMAPPED", "origin_unit:UNIT_UNMAPPED")

        val imported = row()
        import(imported)
        val clash = row(stored = false)
        assertThat(check(clash.copy(mapped = clash.mapped.copy(reference = imported.mapped.reference))))
            .containsExactly("reference:NUMBER_IN_USE")
    }

    @Test
    fun `dates, approvals, originals and linked people follow the rules`() {
        val base = row(stored = false)
        val future = LocalDate.now().plusDays(2)
        assertThat(
            check(base.copy(dates = LegacyDates(LocalDate.of(1899, 12, 31), LocalDate.of(1900, 1, 2)))),
        ).containsExactly("dates:DATE_INVALID")
        assertThat(check(base.copy(dates = LegacyDates(future, future)))).containsExactly("dates:DATE_FUTURE")
        assertThat(
            check(base.copy(dates = LegacyDates(LocalDate.of(2019, 3, 14), LocalDate.of(2019, 3, 1)))),
        ).containsExactly("dates:DATE_ORDER")
        assertThat(check(base.copy(dates = LegacyDates(LocalDate.of(2019, 3, 14))))).containsExactly("decided_on:VALUE_REQUIRED")
        val closed = base.copy(mapped = base.mapped.copy(status = LegacyStatus.CLOSED))
        assertThat(check(closed)).containsExactly("closed_on:VALUE_REQUIRED")

        val approval = base.approvals.single()
        assertThat(check(base.copy(approvals = listOf(approval, approval)))).containsExactly("approvals:APPROVAL_ORDER_DUPLICATE")
        assertThat(
            check(base.copy(approvals = (1..51).map { approval.copy(ordinal = it) })),
        ).containsExactly("approvals:TOO_MANY_APPROVALS")
        assertThat(check(base.copy(approvals = listOf(approval.copy(decision = "")))))
            .containsExactly("approvals/1/decision:VALUE_REQUIRED")

        val main = base.originals.single()
        val attachment = main.copy(role = LegacyFileRole.ATTACHMENT)
        assertThat(check(base.copy(originals = emptyList()))).containsExactly("originals:MAIN_FILE_MISSING")
        assertThat(check(base.copy(originals = listOf(main, main)))).containsExactly("originals:MAIN_FILE_MULTIPLE")
        assertThat(check(base.copy(originals = listOf(main) + List(51) { attachment }))).containsExactly("originals:TOO_MANY_FILES")
        assertThat(check(base.copy(originals = listOf(main, attachment.copy(classificationId = levels()[0].id)))))
            .containsExactly("originals/1/classification:FILE_CLASSIFICATION_LOWER")
        assertThat(check(base.copy(originals = listOf(main, attachment.copy(classificationId = levels()[1].id))))).isEmpty()

        val nobody = UUID.randomUUID()
        assertThat(check(base.copy(initiator = LegacyPerson("Gone", userId = nobody)))).containsExactly("people/$nobody:PERSON_UNKNOWN")
    }

    @Test
    fun `create refuses an original that is not this memo's stored legacy file`() {
        val other = row()
        val base = row(stored = false)
        val foreign = base.copy(originals = listOf(base.originals.single().copy(fileId = other.originals.single().fileId)))
        val refused = catchThrowableOfType(LegacyMemoRefusedException::class.java) { import(foreign) }
        assertThat(refused.problems.map { "${it.field}:${it.code}" }).containsExactly("originals/0:FILE_NOT_RECEIVED")
    }

    @Test
    fun `each published format is a pattern, and a series may still give numbers of the current or a later period above its counter`() {
        val today = asUser(admin) { directory.today() }
        val year = today.year
        val fyStart = if (today.monthValue >= 7) year else year - 1
        val perUnit =
            Numbering(
                series = "credit",
                parts =
                    listOf(
                        NumberPart(NumberPartKind.TYPE_CODE),
                        NumberPart(NumberPartKind.TEXT, text = "/"),
                        NumberPart(NumberPartKind.UNIT_CODE),
                        NumberPart(NumberPartKind.TEXT, text = "/"),
                        NumberPart(NumberPartKind.FINANCIAL_YEAR, style = FinancialYearStyle.SHORT),
                        NumberPart(NumberPartKind.TEXT, text = "/"),
                        NumberPart(NumberPartKind.SEQUENCE, digits = 3),
                    ),
                reset = NumberReset.FINANCIAL_YEAR,
                countPer = NumberCountPer.UNIT,
            )
        val forever =
            Numbering(
                series = "board",
                parts = listOf(NumberPart(NumberPartKind.TEXT, text = "BR-"), NumberPart(NumberPartKind.SEQUENCE, digits = 4)),
                reset = NumberReset.NEVER,
            )
        val credit = aType("Credit", "CR", perUnit)
        val board = aType("Board", "BD", forever)
        val period = "FY$fyStart-${((fyStart + 1) % 100).toString().padStart(2, '0')}"
        asUser(admin) {
            numberStore.start(NumberKey("credit", period, unit.toString()), 10)
            numberStore.start(NumberKey("board", "ALL", ""), 100)
        }

        val all = asJob { transactions.readInTenant(tenant.id) { legacy.series() } }
        val general = all.single { it.typeId == generalTypeId() }
        assertThat(general.format).isEqualTo("M-{year4}-{sequence}")
        assertThat(general.currentPeriod).isEqualTo("$year")
        assertThat(general.mayStillGive("M-$year-00001")).isTrue()
        assertThat(general.mayStillGive("m-${year + 1}-00001")).describedAs("a later year, any case").isTrue()
        assertThat(general.mayStillGive("M-2019-00042")).describedAs("an earlier year").isFalse()
        assertThat(general.mayStillGive("CR/2019/0042")).describedAs("another format").isFalse()

        val unitSeries = all.single { it.typeId == credit }
        val fy = ((fyStart + 1) % 100).toString().padStart(2, '0')
        assertThat(unitSeries.pattern.matches("CR/CRD/FY$fy/007")).isTrue()
        assertThat(unitSeries.mayStillGive("CR/CRD/FY$fy/009")).describedAs("below the unit's counter").isFalse()
        assertThat(unitSeries.mayStillGive("CR/CRD/FY$fy/010")).describedAs("above the unit's counter").isTrue()
        assertThat(unitSeries.mayStillGive("CR/NEW/FY$fy/001")).describedAs("a unit code no unit has yet").isTrue()
        assertThat(
            unitSeries.mayStillGive("CR/CRD/FY${(fyStart % 100).toString().padStart(2, '0')}/500"),
        ).describedAs("last year").isFalse()

        val boardSeries = all.single { it.typeId == board }
        assertThat(boardSeries.mayStillGive("BR-0099")).isFalse()
        assertThat(boardSeries.mayStillGive("BR-0100")).isTrue()
        assertThat(boardSeries.mayStillGive("BR-12345")).describedAs("more digits than the padding").isTrue()
    }
}
