package com.memo.memo.internal.rendition

import com.memo.audit.AuditLog
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileUploads
import com.memo.files.Files
import com.memo.memo.internal.MemoWatchNews
import com.memo.memo.internal.Memos
import com.memo.memo.internal.WatchEventKind
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import java.nio.file.Files as Paths

/**
 * Builds one version of a memo's final record (ADR-0053, design E04 §3): claims it, reads the plan in one transaction,
 * prepares the parts in a spool directory, assembles and validates the PDF/A, checks its text, stores it through the files
 * pipeline as a locked record, then marks it READY and audits its hash. A record veraPDF rejects is still stored, marked
 * not compliant. Retryable failures (the converter down) leave it PENDING for the scheduler's next try, until the attempts
 * run out; a plan that can never work (its stamps and document disagree) fails it for good.
 */
@Component
class RecordBuilder(
    private val store: MemoRenditionStore,
    private val planner: RecordPlanner,
    private val sources: RecordSources,
    private val assembler: RecordAssembler,
    private val validator: PdfaValidator,
    private val renderer: PageRenderer,
    private val uploads: FileUploads,
    private val files: Files,
    private val transactions: TenantTransactions,
    private val audit: AuditLog,
    private val clock: Clock,
    private val news: MemoWatchNews,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Builds [renditionId]; throws [RecordRetry] when the scheduler should try again. */
    fun build(renditionId: UUID) {
        // The task may start before the approval that asked for it commits: not found yet means try again.
        val found = tx { store.get(renditionId) } ?: throw RecordRetry("NOT_FOUND")
        if (!tx { store.claim(found.id) }) return
        val rendition = found
        val spool = Paths.createTempDirectory("rendition-")
        try {
            val plan = tx { planner.plan(rendition.memoId, rendition.version, renderer.name, clock.instant()) }
            val built = make(rendition, plan, spool)
            tx {
                val earlier = store.of(rendition.memoId).firstOrNull { it.state == RenditionState.READY }
                store.ready(rendition.id, built)
                earlier?.let { superseded(it, rendition, built) }
                // Followers hear that the final record is ready (made by the system: everyone following is told).
                news.happened(rendition.memoId, WatchEventKind.RECORD_READY, actor = null)
                audit.record(
                    "memo.rendition.created",
                    Memos.OBJECT_TYPE,
                    rendition.memoId.toString(),
                    after =
                        mapOf(
                            "version" to rendition.version,
                            "sha256" to built.sha256,
                            "sizeBytes" to built.sizeBytes,
                            "pages" to built.pages,
                            "compliant" to built.compliant,
                            "failedRules" to (built.validation["failedRules"] as? Map<*, *>)?.size,
                            "rasterisedPages" to built.flags["rasterisedPages"],
                            "placeholders" to built.flags["placeholders"],
                        ),
                )
            }
            if (!built.compliant) log.warn("Final record {} of memo {} is not valid PDF/A", rendition.version, rendition.memoId)
        } catch (e: RecordPlanException) {
            fail(rendition, e.code, final = true)
        } catch (e: RecordRetry) {
            retryOrFail(rendition, e.code, e)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            // Anything unexpected (storage, a parser) is retried like a converter outage, so no version stays BUILDING.
            log.warn("Final record {} of memo {} failed: {}", rendition.version, rendition.memoId, e.javaClass.simpleName)
            retryOrFail(rendition, "UNEXPECTED", e)
        } finally {
            spool.toFile().deleteRecursively()
        }
    }

    private fun make(
        rendition: MemoRendition,
        plan: RecordPlan,
        spool: Path,
    ): BuiltRecord {
        val prepared = plan.parts.mapIndexed { i, planned -> sources.prepare(planned, i, rendition.id, spool) }
        val out = spool.resolve("record.pdf")
        val assembly = assembler.assemble(prepared.map { it.part }, plan.metadata, out)
        val validation = validator.validate(out)
        val textMissing = RecordText.missing(out, plan.textItems)
        val versionId = store(rendition, plan, out)
        val stored = checkNotNull(tx { files.version(versionId) }) { "stored record version missing" }
        val parts =
            assembly.parts.mapIndexed { i, at ->
                val planned = plan.parts[i]
                RenditionPart(
                    position = i,
                    kind = if (prepared[i].placeholder) PartKind.PLACEHOLDER else planned.kind,
                    title = planned.title,
                    sourceVersionId = (planned as? PlannedPart.Source)?.file?.versionId,
                    sourceSha256 = (planned as? PlannedPart.Source)?.file?.sha256,
                    firstPage = at.firstPage,
                    pages = at.pages,
                    mode = if (prepared[i].placeholder) PartMode.PLACEHOLDER else at.mode,
                )
            }
        return BuiltRecord(
            fileId = stored.fileId,
            fileVersionId = stored.id,
            sha256 = stored.sha256.lowercase(),
            sizeBytes = stored.sizeBytes,
            pages = assembly.pages,
            classificationId = plan.classification.id,
            classificationRank = plan.classification.rank,
            compliant = validation.compliant,
            validation =
                mapOf(
                    "profile" to validation.profile,
                    "validator" to validation.validator,
                    "failedRules" to validation.failedRules,
                    "error" to validation.error,
                ),
            flags =
                mapOf(
                    "rasterisedPages" to assembly.rasterisedPages,
                    "placeholders" to parts.count { it.mode == PartMode.PLACEHOLDER },
                    "missingGlyphs" to assembly.missingGlyphs,
                    "textMissing" to textMissing,
                ),
            renderer = mapOf("renderer" to renderer.name, "validator" to validation.validator),
            parts = parts,
        )
    }

    /** Version 1 is a new record file; later versions are new versions of the same file (the earlier ones stay locked). */
    private fun store(
        rendition: MemoRendition,
        plan: RecordPlan,
        out: Path,
    ): UUID {
        val key = "rendition:${rendition.id}"
        val earlier =
            tx {
                store
                    .of(rendition.memoId)
                    .firstOrNull { it.fileId != null && it.id != rendition.id }
                    ?.fileId
                    ?.let(files::info)
            }
        return if (earlier == null) {
            uploads.uploadFile(MemoRenditionPurpose.KEY, rendition.memoId.toString(), plan.fileName, out, key)
        } else {
            uploads.uploadVersionFile(earlier.id, earlier.version, plan.fileName, out, key)
        }
    }

    /** A later version replaces [earlier] as the record of reference; both stay stored and locked. */
    private fun superseded(
        earlier: MemoRendition,
        rendition: MemoRendition,
        built: BuiltRecord,
    ) {
        audit.record(
            "memo.rendition.superseded",
            Memos.OBJECT_TYPE,
            rendition.memoId.toString(),
            before = mapOf("version" to earlier.version, "sha256" to earlier.sha256),
            after = mapOf("version" to rendition.version, "sha256" to built.sha256, "reason" to rendition.reason),
        )
    }

    /** Back to PENDING and [cause] rethrown for the scheduler's next try, or FAILED for good once the attempts run out. */
    private fun retryOrFail(
        rendition: MemoRendition,
        code: String,
        cause: RuntimeException,
    ) {
        val final = rendition.attempts + 1 >= MAX_ATTEMPTS
        fail(rendition, code, final)
        if (!final) throw cause
    }

    private fun fail(
        rendition: MemoRendition,
        code: String,
        final: Boolean,
    ) {
        tx {
            store.failed(rendition.id, code, final)
            if (final) {
                audit.record(
                    "memo.rendition.failed",
                    Memos.OBJECT_TYPE,
                    rendition.memoId.toString(),
                    after = mapOf("version" to rendition.version, "code" to code),
                )
            }
        }
    }

    private fun <T> tx(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)

    companion object {
        const val MAX_ATTEMPTS = 5
    }
}
