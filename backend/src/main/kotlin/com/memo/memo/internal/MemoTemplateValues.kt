package com.memo.memo.internal

import com.memo.templating.TemplateData
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The text that goes into a memo template (ADR-0048, design E03 §3): pre-formatted, English, dates as `d MMM yyyy`,
 * amounts with their currency and grouping. Built from what the memo page shows, so a template says what the page says.
 */
object MemoTemplateValues {
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
    const val UNNUMBERED = "(number on submit)"

    fun date(day: LocalDate): String = DATE.format(day)

    fun number(
        decimal: String,
        decimals: Int,
    ): String {
        val format = DecimalFormat("#,##0" + if (decimals > 0) "." + "0".repeat(decimals) else "", DecimalFormatSymbols(Locale.UK))
        return runCatching { format.format(BigDecimal(decimal)) }.getOrDefault(decimal)
    }

    /** One field's text: the page's display text when it has one, else the value formatted for its type. */
    fun field(
        field: MemoField?,
        detail: MemoDetailView,
    ): String =
        detail.display ?: when (detail.type) {
            FieldType.DATE -> runCatching { date(LocalDate.parse(detail.value.toString())) }.getOrDefault(detail.value.toString())
            FieldType.NUMBER -> number(detail.value.toString(), field?.decimals ?: 0)
            FieldType.AMOUNT -> amount(detail.value, field?.decimals ?: 2)
            else -> detail.value.toString()
        }

    private fun amount(
        value: Any,
        decimals: Int,
    ): String {
        val map = value as? Map<*, *> ?: return value.toString()
        val currency = map["currency"]?.toString().orEmpty()
        val number = number(map["value"]?.toString().orEmpty(), decimals)
        return listOf(currency, number).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /**
     * The data for [view]: every standard tag, every field of [fields] (blank when the memo has no value), and an empty
     * approval table (E04 fills it for the final rendition).
     */
    fun of(
        view: MemoView,
        fields: List<MemoField>,
        initiatorPosition: String,
        initiatorUnit: String,
        today: LocalDate,
    ): TemplateData {
        val details = view.details.associateBy { it.key }
        val values =
            mapOf(
                "memo.number" to (view.reference ?: UNNUMBERED),
                "memo.date" to date(today),
                "memo.subject" to view.subject,
                "memo.classification" to view.classification.name,
                "memo.type" to view.type.name,
                "initiator.name" to view.initiator.name,
                "initiator.position" to initiatorPosition,
                "initiator.unit" to initiatorUnit,
            ) + fields.associate { f -> MemoTemplateTags.field(f.key) to (details[f.key]?.let { field(f, it) } ?: "") }
        return TemplateData(values, mapOf(MemoTemplateTags.APPROVALS to emptyList()))
    }

    /** Values that show where each tag goes: labels in brackets, today's date, one sample approval. */
    fun sample(
        settings: MemoTypeSettings,
        today: LocalDate,
    ): TemplateData {
        val date = date(today)
        val values =
            mapOf(
                "memo.number" to "[Number]",
                "memo.date" to date,
                "memo.subject" to "[Subject]",
                "memo.classification" to "[Classification]",
                "memo.type" to settings.name,
                "initiator.name" to "[Initiator]",
                "initiator.position" to "[Position]",
                "initiator.unit" to "[Unit]",
            ) + settings.fields.associate { MemoTemplateTags.field(it.key) to "[${it.label}]" }
        val approval = mapOf("name" to "Sample approver", "position" to "[Position]", "decision" to "Approved", "decidedAt" to date)
        return TemplateData(values, mapOf(MemoTemplateTags.APPROVALS to listOf(approval)))
    }
}
