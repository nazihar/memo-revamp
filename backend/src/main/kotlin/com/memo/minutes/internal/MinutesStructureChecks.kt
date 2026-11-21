package com.memo.minutes.internal

import com.memo.audit.AuditLog
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.RefusedException
import com.memo.files.FileContents
import com.memo.files.FileVersionInfo
import com.memo.files.Files
import com.memo.meeting.MeetingItems
import com.memo.templating.DocxControls
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The structure check of one version of the draft (design E06 §1.6.1). */
data class StructureResult(
    val versionId: UUID,
    val ok: Boolean,
    val problems: List<StructureProblem>,
    val trackedChanges: Int,
    val comments: Int,
)

/** The step the minutes are to take out of the draft, and so what must hold first (design E06 §1.5, §1.6.1). */
enum class MinutesStep {
    /** Send for the chair's review: the structure sound, every submission item decided. */
    REVIEW,

    /** Circulate, build copies, confirm: also no tracked changes or comments left unresolved. */
    CIRCULATE,
}

/**
 * The structure check (design E06 §1.6.1, ADR-0066) on every saved version of the draft and before each step out of it:
 * the current version is read with StAX ([DocxControls.scan], [DocxControls.marks]; a 200-page file in milliseconds) and
 * compared with the agenda ([MinutesStructure]); the result is stored on the minutes for that version (audited
 * `minutes.structure.checked`) and shown as the banner. Outside a transaction, with the tenant bound.
 */
@Service
class MinutesStructureChecks(
    private val store: MinutesStore,
    private val items: MeetingItems,
    private val source: MinutesDraftSource,
    private val files: Files,
    private val contents: FileContents,
    private val controls: DocxControls,
    private val transactions: TenantTransactions,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /** Checks [meetingId]'s draft as it is now (once per version); null when there is no draft. */
    fun check(meetingId: UUID): StructureResult? {
        val (row, version, minuted) =
            transactions.readInTenant(TenantContext.current()) {
                val row = store.find(meetingId)
                val version =
                    row
                        ?.fileId
                        ?.let(files::info)
                        ?.currentVersionId
                        ?.let(files::version)
                Triple(
                    row,
                    version,
                    MinutesTemplateData.minuted(items.agenda(meetingId).items).map { MinutedItem(it.id, checkNotNull(it.number)) },
                )
            }
        if (row == null || version == null) return null
        return if (row.checkedVersionId == version.id) stored(row) else fresh(meetingId, version, minuted)
    }

    /** Reads [version] and stores what it found, if it is still the draft's current version. */
    private fun fresh(
        meetingId: UUID,
        version: FileVersionInfo,
        minuted: List<MinutedItem>,
    ): StructureResult {
        val bytes = ByteArrayOutputStream().also { contents.write(version, it) }.toByteArray()
        val marks = controls.marks(bytes.inputStream())
        val problems = MinutesStructure.problems(minuted, controls.scan(bytes.inputStream()))
        val result = StructureResult(version.id, problems.isEmpty(), problems, marks.trackedChanges, marks.comments)
        transactions.inTenant(TenantContext.current()) {
            val locked = store.lock(meetingId)
            val current = locked?.fileId?.let { files.info(it) }?.currentVersionId
            if (locked != null && current == version.id && locked.checkedVersionId != version.id) {
                val after =
                    locked.copy(
                        checkedVersionId = version.id,
                        structureOk = result.ok,
                        structureProblems = problems,
                        trackedChanges = marks.trackedChanges,
                        comments = marks.comments,
                        checkedAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
                    )
                check(store.update(locked, after)) { "the locked minutes moved on" }
                audit.record(
                    "minutes.structure.checked",
                    MinutesDrafts.OBJECT_TYPE,
                    meetingId.toString(),
                    after =
                        mapOf(
                            "versionId" to version.id.toString(),
                            "ok" to result.ok,
                            "problems" to problems.map { listOfNotNull(it.problem.name, it.number).joinToString(":") },
                            "trackedChanges" to marks.trackedChanges,
                            "comments" to marks.comments,
                        ),
                )
            }
        }
        return result
    }

    /**
     * Refuses [step] for [meetingId]'s minutes while it may not be taken (T6's transitions call this first): 409
     * `NO_DRAFT` without a draft, `STRUCTURE` with the items whose blocks are missing or damaged ("Restore item n"),
     * `UNDECIDED_ITEMS` with the submission items without a decision, and for [MinutesStep.CIRCULATE] `UNRESOLVED_CHANGES`
     * while tracked changes or comments are left. Checks the current version first.
     */
    fun requireReady(
        meetingId: UUID,
        step: MinutesStep,
    ) {
        blocker(meetingId, step)?.let { throw it }
    }

    /** The first thing that stops [step], as its refusal; null when the step may be taken. */
    fun blocker(
        meetingId: UUID,
        step: MinutesStep,
    ): RefusedException? {
        val result = check(meetingId)
        val undecided by lazy { transactions.readInTenant(TenantContext.current()) { source.undecided(meetingId) } }
        return when {
            result == null -> {
                RefusedException("NO_DRAFT", "Make the draft first.")
            }

            !result.ok -> {
                RefusedException(
                    "STRUCTURE",
                    "Some items' blocks are missing or damaged: restore them first.",
                    mapOf("items" to result.problems.map { problemDetails(it) }),
                )
            }

            undecided.isNotEmpty() -> {
                RefusedException(
                    "UNDECIDED_ITEMS",
                    "Record a decision on every item from a memo first (deferred if it was not reached).",
                    mapOf("items" to undecided.map { mapOf("itemId" to it.id.toString(), "number" to it.number) }),
                )
            }

            step == MinutesStep.CIRCULATE && (result.trackedChanges > 0 || result.comments > 0) -> {
                RefusedException(
                    "UNRESOLVED_CHANGES",
                    "Accept or reject the chair's suggested changes and resolve the comments first.",
                    mapOf("trackedChanges" to result.trackedChanges, "comments" to result.comments),
                )
            }

            else -> {
                null
            }
        }
    }

    private fun stored(row: MinutesRow) =
        StructureResult(
            checkNotNull(row.checkedVersionId),
            row.structureOk == true,
            row.structureProblems,
            row.trackedChanges,
            row.comments,
        )

    private fun problemDetails(p: StructureProblem): Map<String, Any> =
        buildMap {
            put("problem", p.problem.name)
            p.itemId?.let { put("itemId", it.toString()) }
            p.number?.let { put("number", it) }
            p.tag?.let { put("tag", it) }
        }
}
