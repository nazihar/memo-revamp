package com.memo.memo

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.FieldProblem
import com.memo.memo.internal.MemoAction
import com.memo.memo.internal.MemoFields
import com.memo.memo.internal.MemoNotFoundException
import com.memo.memo.internal.MemoNotes
import com.memo.memo.internal.MemoRejectedException
import com.memo.memo.internal.MemoTemplateService
import com.memo.memo.internal.MemoViews
import com.memo.memo.internal.Memos
import com.memo.memo.internal.TemplateFill
import org.springframework.stereotype.Service
import java.util.UUID

/** What another module sets on a draft memo it raises for its actor (design E07 §2.3), as `POST /memos` takes it. */
data class MemoDraftFields(
    val subject: String,
    val typeId: UUID,
    /** Null: the type's default level. */
    val classificationId: UUID? = null,
)

/** What a line the system writes on a memo's note sheet says (design E07 §1.11). */
enum class SystemNoteKind {
    /** "Raised from action BRC A14/2026 (Board Risk Committee)": the memo was raised from an action item. */
    RAISED_FROM_ACTION,
}

/**
 * Where a draft raised by another module comes from: the line written on its note sheet when it is created. [text]
 * names the record by its reference, never its content ("Raised from action BRC A14/2026 (Board Risk Committee)").
 */
data class DraftOrigin(
    val note: SystemNoteKind,
    val text: String,
)

/**
 * The memo rules refused the draft (422 in the caller's answer): the problems as `POST /memos` answers them, by field
 * (`subject`, `typeId`, `classificationId`).
 */
class MemoDraftRejectedException(
    val problems: List<FieldProblem>,
    cause: Throwable? = null,
) : RuntimeException("draft memo rejected: ${problems.joinToString { "${it.field}:${it.code}" }}", cause)

/**
 * Draft memos raised by other modules (design E07 §2.3, ADR-0067): the same service as `POST /memos` (the actor needs
 * `memo.create`, the type must be one they may raise, the level at least the type's default and at most their clearance;
 * they are the initiator; `memo.created` is audited), with a line on the note sheet saying where it came from, and the
 * type's template filled after the caller's commit (ADR-0048). From then on it is an ordinary draft.
 */
@Service
class MemoDrafts(
    private val memos: Memos,
    private val notes: MemoNotes,
    private val views: MemoViews,
    private val templates: MemoTemplateService,
    private val transactions: TenantTransactions,
) {
    /**
     * Creates the draft inside the caller's tenant transaction and returns its id; [origin]'s line is written on its note
     * sheet in the same transaction. Throws [MemoDraftRejectedException] for what the memo rules refuse, and 403 without
     * `memo.create`.
     */
    fun create(
        fields: MemoDraftFields,
        origin: DraftOrigin?,
    ): UUID {
        val memo =
            try {
                memos.create(MemoFields(fields.subject, fields.typeId, fields.classificationId))
            } catch (e: MemoRejectedException) {
                throw MemoDraftRejectedException(e.problems, e)
            } catch (e: MemoNotFoundException) {
                throw MemoDraftRejectedException(listOf(FieldProblem("typeId", "unknown", "No such memo type.")), e)
            }
        origin?.let { notes.system(memo.id, it.note.name, it.text) }
        return memo.id
    }

    /**
     * Starts [memoId]'s document from its type's template, as `POST /memos` does after its commit: outside any
     * transaction, as the initiator. True when it was filled; a type without a template, or a fill that fails, leaves the
     * memo without a document (the initiator can fill it later).
     */
    fun fillTemplate(memoId: UUID): Boolean {
        val wanted =
            transactions.readInTenant(TenantContext.current()) {
                MemoAction.FILL_FROM_TEMPLATE in views.of(memos.get(memoId)).allowedActions
            }
        return wanted && templates.fillDocument(memoId) == TemplateFill.FILLED
    }
}
