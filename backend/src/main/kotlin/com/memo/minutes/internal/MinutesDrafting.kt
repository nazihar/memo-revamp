package com.memo.minutes.internal

import com.memo.audit.AuditLog
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileContents
import com.memo.files.FileUploads
import com.memo.files.Files
import com.memo.files.UploadRefusedException
import com.memo.forum.MinutesLanguage
import com.memo.forum.MinutesTemplates
import com.memo.templating.ContentControlException
import com.memo.templating.DocxControls
import com.memo.templating.FillFailure
import com.memo.templating.TemplateFillException
import com.memo.templating.TemplateFiller
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.util.UUID

/** What the draft job read before filling: the request, the source and, for one item, the draft's current version. */
private data class DraftPrep(
    val row: MinutesRow,
    val source: DraftSource,
    val fileVersion: Long?,
)

/**
 * The `minutes-draft` job (design E06 §1.6.1, §9): fills the committee type's template pinned on the minutes (or the
 * built-in one) from [MinutesDraftSource] with [MinutesTemplateData], one locked content control per item; then stores it
 * as the requester, through the files pipeline (scan, inspection, encryption), as the draft's first or next version: the
 * whole draft, or the current version with one item's control filled again ([DocxControls.refill]) or put back after the
 * previous item's ([DocxControls.restore]). A request superseded by a later one is skipped. The version's listener closes
 * the request in the version's transaction; a failure is recorded on the minutes with a code once retrying cannot help.
 */
@Service
class MinutesDrafting(
    private val source: MinutesDraftSource,
    private val store: MinutesStore,
    private val templates: MinutesTemplates,
    private val filler: TemplateFiller,
    private val controls: DocxControls,
    private val files: Files,
    private val contents: FileContents,
    private val uploads: FileUploads,
    private val transactions: TenantTransactions,
    private val audit: AuditLog,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Runs request [task]; [last]: no retry follows, so a failure is recorded. */
    fun run(
        task: DraftTask,
        last: Boolean = true,
    ) {
        val tenant = TenantContext.current()
        val prep =
            transactions.readInTenant(tenant) {
                val row = store.find(task.meetingId)?.takeIf { it.workSeq == task.seq && it.workState == WorkState.PENDING }
                row?.let { r -> source.read(task.meetingId)?.let { DraftPrep(r, it, r.fileId?.let(files::info)?.version) } }
            } ?: return
        try {
            write(prep)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            val code = code(e)
            log.warn("Minutes draft {} of meeting {} failed: {}", task.seq, task.meetingId, e.toString())
            if (last || code in FINAL) failed(task, code) else throw e
        }
    }

    private fun write(prep: DraftPrep) {
        val row = prep.row
        val requester = checkNotNull(row.workRequestedBy)
        val filled = filler.fill(template(row).inputStream(), MinutesTemplateData.of(prep.source))
        val itemId = row.workItemId
        val (bytes, kind) =
            if (row.workKind == DraftWork.ITEM && itemId != null) {
                item(prep, itemId, filled)
            } else {
                filled to MinutesEventKind.GENERATED
            }
        val name = "Minutes ${prep.source.meeting.label ?: prep.source.committee}.docx".replace('/', '-')
        val key = "minutes-draft:${row.meetingId}:${row.workSeq}"
        ActorContext.call(ActorScope(Actor.User(requester), null)) {
            DraftWriting.with(DraftWriting(row.meetingId, row.workSeq, kind, itemId)) {
                val fileId = row.fileId
                if (fileId == null) {
                    uploads.upload(MinutesDraftPurpose.KEY, row.meetingId.toString(), name, bytes, key)
                } else {
                    uploads.uploadVersion(fileId, checkNotNull(prep.fileVersion), name, bytes, key)
                }
            }
        }
    }

    /** The draft's current version with [itemId]'s control filled again from [filled], or put back when it is missing. */
    private fun item(
        prep: DraftPrep,
        itemId: UUID,
        filled: ByteArray,
    ): Pair<ByteArray, MinutesEventKind> {
        val current = current(checkNotNull(prep.row.fileId))
        val tag = MinutesStructure.tag(itemId)
        val present =
            controls
                .scan(current.inputStream())
                .filter { it.bodyLevel }
                .mapNotNull { it.tag }
                .toSet()
        if (tag in present) return controls.refill(current.inputStream(), filled.inputStream(), tag) to MinutesEventKind.ITEM_REGENERATED
        val order = MinutesTemplateData.minuted(prep.source.items).map { it.id }
        val after = order.takeWhile { it != itemId }.lastOrNull { MinutesStructure.tag(it) in present }?.let(MinutesStructure::tag)
        return controls.restore(current.inputStream(), filled.inputStream(), tag, after) to MinutesEventKind.ITEM_RESTORED
    }

    /** The committee type's uploaded template pinned on the minutes, or the built-in one. */
    private fun template(row: MinutesRow): ByteArray {
        val uploaded = row.policy.templateFileId?.let { contents.current(it, MinutesTemplates.PURPOSE) }
        return uploaded?.let { version -> ByteArrayOutputStream().also { contents.write(version, it) }.toByteArray() }
            ?: templates.builtIn(MinutesLanguage.EN)
    }

    private fun current(fileId: UUID): ByteArray {
        val version = contents.current(fileId, MinutesDraftPurpose.KEY) ?: throw ContentControlException("the draft has no version")
        return ByteArrayOutputStream().also { contents.write(version, it) }.toByteArray()
    }

    /** Records the request [task] as given up with [code], unless a later request superseded it. */
    private fun failed(
        task: DraftTask,
        code: String,
    ) = transactions.inTenant(TenantContext.current()) {
        val row = store.lock(task.meetingId)?.takeIf { it.workSeq == task.seq && it.workState == WorkState.PENDING } ?: return@inTenant
        check(store.update(row, row.copy(workState = WorkState.FAILED, workError = code))) { "the locked minutes moved on" }
        audit.record(
            "minutes.draft.failed",
            MinutesDrafts.OBJECT_TYPE,
            task.meetingId.toString(),
            after = mapOf("seq" to task.seq, "kind" to row.workKind?.name, "itemId" to row.workItemId?.toString(), "error" to code),
        )
    }

    private fun code(e: RuntimeException): String =
        when (e) {
            is TemplateFillException -> {
                e.failure.name
            }

            is ContentControlException -> {
                CONTROLS
            }

            is UploadRefusedException -> {
                e.reason.take(MAX_CODE)
            }

            else -> {
                e.javaClass.simpleName
                    .take(MAX_CODE)
                    .ifEmpty { "ERROR" }
            }
        }

    private companion object {
        const val CONTROLS = "CONTROLS"
        const val MAX_CODE = 100

        /** Failures a retry cannot fix: the template does not fill, or the item's control cannot be found or copied. */
        val FINAL = setOf(FillFailure.INVALID_TEMPLATE.name, FillFailure.MISSING_VALUE.name, CONTROLS)
    }
}
