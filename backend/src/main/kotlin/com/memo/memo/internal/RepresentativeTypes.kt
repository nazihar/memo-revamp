package com.memo.memo.internal

import java.math.BigDecimal

/**
 * Representative bank memo types for the demo tenant (Phase 3 E07, §10 Q2: with no pilot bank, a typical configuration
 * stands in): each with its fields, numbering and lifecycle policy, as a tenant administrator would set them up through
 * the admin screens. Seeded through the reviewed lifecycle, so the demo shows maker-checker too.
 */
internal object RepresentativeTypes {
    data class Spec(
        val name: String,
        val code: String,
        val settings: (MemoTypeSettings) -> MemoTypeSettings,
    )

    private val BDT_USD = FieldRules(currencies = listOf("BDT", "USD"), min = BigDecimal.ZERO)

    private fun choices(vararg options: Pair<String, String>) =
        FieldRules(choices = options.map { (key, label) -> FieldChoice(key, label) })

    private fun parts(vararg parts: NumberPart) = parts.toList()

    private fun text(value: String) = NumberPart(NumberPartKind.TEXT, text = value)

    private fun sequence(digits: Int) = NumberPart(NumberPartKind.SEQUENCE, digits = digits)

    private val YEAR = NumberPart(NumberPartKind.YEAR, digits = 4)
    private val FY = NumberPart(NumberPartKind.FINANCIAL_YEAR, style = FinancialYearStyle.LONG)

    val ALL =
        listOf(
            Spec("Credit proposal", "CP") {
                it.copy(
                    stepUpOnApprove = true,
                    fields =
                        listOf(
                            MemoField("customer", "Customer", type = FieldType.TEXT, required = true),
                            MemoField(
                                "amount",
                                "Facility amount",
                                type = FieldType.AMOUNT,
                                required = true,
                                rules = BDT_USD,
                                attribute = true,
                            ),
                            MemoField(
                                "purpose",
                                "Purpose",
                                type = FieldType.CHOICE,
                                required = true,
                                rules =
                                    choices(
                                        "working-capital" to "Working capital",
                                        "term-loan" to "Term loan",
                                        "trade" to "Trade finance",
                                    ),
                                attribute = true,
                            ),
                            MemoField(
                                "tenor",
                                "Tenor (months)",
                                type = FieldType.NUMBER,
                                rules = FieldRules(min = BigDecimal.ONE, max = BigDecimal(360)),
                            ),
                        ),
                    numbering =
                        Numbering(
                            series = "credit",
                            parts = parts(text("CR/"), FY, text("/"), sequence(4)),
                            reset = NumberReset.FINANCIAL_YEAR,
                            fyStartMonth = 7,
                        ),
                    retention = Retention(10, RetentionFrom.CLOSURE),
                )
            },
            Spec("Expense approval", "EXP") {
                it.copy(
                    stepUpOnApprove = false,
                    fields =
                        listOf(
                            MemoField("amount", "Amount", type = FieldType.AMOUNT, required = true, rules = BDT_USD, attribute = true),
                            MemoField("costCentre", "Cost centre", type = FieldType.UNIT, required = true, attribute = true),
                            MemoField(
                                "category",
                                "Category",
                                type = FieldType.CHOICE,
                                required = true,
                                rules =
                                    choices(
                                        "travel" to "Travel",
                                        "training" to "Training",
                                        "equipment" to "Equipment",
                                        "other" to "Other",
                                    ),
                            ),
                        ),
                    numbering = Numbering(series = "expense", parts = parts(text("EXP-"), YEAR, text("-"), sequence(5))),
                    autoCloseDays = 30,
                    retention = Retention(7, RetentionFrom.APPROVAL),
                )
            },
            Spec("Procurement", "PRC") {
                it.copy(
                    fields =
                        listOf(
                            MemoField(
                                "amount",
                                "Estimated value",
                                type = FieldType.AMOUNT,
                                required = true,
                                rules = BDT_USD,
                                attribute = true,
                            ),
                            MemoField("vendor", "Proposed vendor", type = FieldType.TEXT),
                            MemoField(
                                "method",
                                "Procurement method",
                                type = FieldType.CHOICE,
                                required = true,
                                rules =
                                    choices(
                                        "open-tender" to "Open tender",
                                        "rfq" to "Request for quotation",
                                        "direct" to "Direct purchase",
                                    ),
                                attribute = true,
                            ),
                        ),
                    numbering =
                        Numbering(
                            series = "procurement",
                            parts = parts(text("PRC/"), FY, text("/"), sequence(4)),
                            reset = NumberReset.FINANCIAL_YEAR,
                            moment = NumberMoment.ON_APPROVAL,
                            fyStartMonth = 7,
                        ),
                    attachmentRules = AttachmentRules(required = true, min = 1, note = "Attach the quotations or the tender evaluation."),
                    retention = Retention(10, RetentionFrom.CLOSURE),
                )
            },
            Spec("Policy approval", "POL") {
                it.copy(
                    fields =
                        listOf(
                            MemoField("effective", "Effective from", type = FieldType.DATE, required = true),
                            MemoField(
                                "areas",
                                "Policy areas",
                                type = FieldType.MULTI_CHOICE,
                                rules =
                                    choices(
                                        "credit" to "Credit",
                                        "operations" to "Operations",
                                        "hr" to "Human resources",
                                        "it" to "IT",
                                    ),
                            ),
                        ),
                    numbering = Numbering(series = "policy", parts = parts(text("POL-"), YEAR, text("-"), sequence(3))),
                    autoCloseDays = 90,
                    retention = Retention(25, RetentionFrom.CLOSURE),
                )
            },
        )
}
