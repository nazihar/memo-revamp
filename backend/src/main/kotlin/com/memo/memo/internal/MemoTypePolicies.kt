package com.memo.memo.internal

import com.memo.common.web.FieldProblem
import io.swagger.v3.oas.annotations.media.Schema

/** What a retention period counts from (ADR-0043). */
enum class RetentionFrom { APPROVAL, CLOSURE }

/** How long a memo type's memos are kept (ADR-0043): stored now; Phase 7 records management acts on it. */
data class Retention(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val years: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val from: RetentionFrom = RetentionFrom.CLOSURE,
)

/** What a memo type asks for in attachments (ADR-0043): stored now; Phase 3 E02 enforces it when attachments arrive. */
data class AttachmentRules(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val required: Boolean = false,
    /** The fewest attachments when [required]. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val min: Int = 0,
    /** What the initiator should attach, shown on the form. */
    val note: String? = null,
)

/** The checks of a type's lifecycle settings (design E01 §3): bounded retention, attachment rules and auto-close days. */
object MemoTypePolicies {
    const val MAX_RETENTION_YEARS = 100
    const val MAX_AUTO_CLOSE_DAYS = 3650
    const val MAX_ATTACHMENTS = 50
    const val MAX_NOTE = 300

    fun problems(settings: MemoTypeSettings): List<FieldProblem> {
        val rules = settings.attachmentRules
        return listOfNotNull(
            FieldProblem("retention/years", "RANGE", "1 to $MAX_RETENTION_YEARS years.").takeIf {
                settings.retention?.years?.let { it !in 1..MAX_RETENTION_YEARS } == true
            },
            FieldProblem("autoCloseDays", "RANGE", "1 to $MAX_AUTO_CLOSE_DAYS days, or empty for never.").takeIf {
                settings.autoCloseDays?.let { it !in 1..MAX_AUTO_CLOSE_DAYS } == true
            },
            FieldProblem("attachmentRules/min", "RANGE", "1 to $MAX_ATTACHMENTS when attachments are required, else 0.").takeIf {
                if (rules.required) rules.min !in 1..MAX_ATTACHMENTS else rules.min != 0
            },
            FieldProblem("attachmentRules/note", "LENGTH", "At most $MAX_NOTE characters.").takeIf { (rules.note?.length ?: 0) > MAX_NOTE },
        )
    }
}
