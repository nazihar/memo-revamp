package com.memo.memo.internal.rendition

import com.memo.common.authz.ClassificationLevelRef
import com.memo.common.authz.Clearances
import com.memo.files.FileType
import com.memo.files.Files
import com.memo.identity.UserSummaries
import com.memo.memo.internal.AttachmentRow
import com.memo.memo.internal.BoundAttachment
import com.memo.memo.internal.Memo
import com.memo.memo.internal.MemoApproval
import com.memo.memo.internal.MemoApprovalStore
import com.memo.memo.internal.MemoAttachmentStore
import com.memo.memo.internal.MemoNote
import com.memo.memo.internal.MemoNoteStore
import com.memo.memo.internal.MemoStore
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.NoteDoc
import com.memo.memo.internal.NoteDocument
import com.memo.memo.internal.NoteKind
import com.memo.workflow.ActionEntry
import com.memo.workflow.ActionKind
import com.memo.workflow.WorkflowQueries
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** A file that goes into the record: its version, hash, name and type. */
data class SourceFile(
    val versionId: UUID,
    val sha256: String,
    val name: String,
    val mediaType: String,
    val sizeBytes: Long,
) {
    val type: FileType? get() = FileType.ofMediaType(mediaType)
}

/** A part as planned, before conversion: generated pages, or a source file with the placeholder that stands in for it. */
sealed interface PlannedPart {
    val kind: PartKind
    val title: String
    val label: String

    data class Pages(
        override val kind: PartKind,
        override val title: String,
        override val label: String,
        val document: PageDocument,
    ) : PlannedPart

    data class Source(
        override val kind: PartKind,
        override val title: String,
        override val label: String,
        val file: SourceFile,
        val fallback: PageDocument,
    ) : PlannedPart
}

/** Everything a record version is built from, read in one tenant transaction (design E04 §3, step 1). */
data class RecordPlan(
    val memoId: UUID,
    val fileName: String,
    val metadata: RecordMetadata,
    val classification: ClassificationLevelRef,
    val parts: List<PlannedPart>,
    /** What the text check expects, in order (generated pages only, so rasterised parts cannot hide them). */
    val textItems: List<String>,
)

/** A memo whose record cannot be built, ever (for example its stamps and its document disagree). */
class RecordPlanException(
    val code: String,
) : RuntimeException(code)

/**
 * Reads what a memo's final record is made of, as the system for its tenant (no reader rule applies to the job): the
 * memo, its type, the final stamp and the document version it binds, the attachments bound to the final round, every
 * stamp and note, and the names to print. Fails for good when the final stamp's document hash differs from the stored
 * document's (threat model T2).
 */
@Component
class RecordPlanner(
    private val memos: MemoStore,
    private val types: MemoTypes,
    private val approvals: MemoApprovalStore,
    private val attachments: MemoAttachmentStore,
    private val notes: MemoNoteStore,
    private val workflows: WorkflowQueries,
    private val files: Files,
    private val users: UserSummaries,
    private val clearances: Clearances,
    private val json: JsonMapper,
) {
    fun plan(
        memoId: UUID,
        version: Int,
        renderer: String,
        now: Instant,
    ): RecordPlan {
        val facts = gather(memoId)
        val memo = facts.memo
        val number = memo.reference ?: "Unnumbered"
        val frame = Frame("$number · ${memo.subject}", "Final record v$version")
        val stampLines = facts.stamps.map { stampLine(it, facts.name) }
        val parts = mutableListOf<PlannedPart>()
        parts +=
            PlannedPart.Pages(
                PartKind.COVER,
                "Cover",
                "C-",
                cover(facts, frame, number, "Version $version, made ${RecordPages.time(now)}", renderer),
            )
        parts +=
            PlannedPart.Source(
                PartKind.DOCUMENT,
                "Approved document: ${facts.document.source.name}",
                "D-",
                facts.document.source,
                placeholder(frame, "Approved document", facts.document.source, facts.memoLevel.name),
            )
        parts += PlannedPart.Pages(PartKind.APPROVALS, "Approvals", "AP-", RecordPages.approvals(frame.header, frame.footer, stampLines))
        parts += PlannedPart.Pages(PartKind.NOTES, "Note sheet", "N-", RecordPages.notes(frame.header, frame.footer, noteLines(facts)))
        parts += attachmentParts(facts, frame)
        val metadata =
            RecordMetadata(
                title = "Final record of $number: ${memo.subject}",
                identifier = number,
                description = "Document SHA-256 ${facts.document.source.sha256}; record version $version; made with $renderer",
                author = facts.name(memo.initiatorId).orEmpty(),
                producer = "memo",
                created = now,
            )
        val textItems = listOf("Final record", number, memo.subject, "Approvals") + stampLines.map { it.recordHash } + "Note sheet"
        val fileName = "${number.replace(Regex("[^A-Za-z0-9-]"), "-")}-final-record-v$version.pdf"
        return RecordPlan(memo.id, fileName, metadata, facts.recordLevel, parts, textItems)
    }

    /** The running header and footer of the generated pages. */
    private data class Frame(
        val header: String,
        val footer: String,
    )

    /** What a record is read from, in one transaction. */
    private class Facts(
        val memo: Memo,
        val stamps: List<MemoApproval>,
        val final: MemoApproval,
        val document: DocumentRef,
        val bound: List<Pair<BoundAttachment, AttachmentRow>>,
        val notes: List<MemoNote>,
        val legacy: List<ActionEntry>,
        val levelOf: (UUID?, Int?) -> ClassificationLevelRef,
        val name: (UUID?) -> String?,
    ) {
        val memoLevel: ClassificationLevelRef = levelOf(memo.classificationId, memo.classificationRank)
        val recordLevel: ClassificationLevelRef =
            (bound.map { (_, row) -> levelOf(row.classificationId, row.classificationRank) } + memoLevel).maxBy { it.rank }
    }

    private fun gather(memoId: UUID): Facts {
        val memo = memos.find(memoId) ?: throw RecordPlanException("MEMO_MISSING")
        val stamps = approvals.of(memo.id)
        val final = stamps.lastOrNull { it.decision == ActionKind.APPROVE.name } ?: throw RecordPlanException("NO_APPROVAL")
        val noteList = notes.of(memo.id)
        val linked = noteList.mapNotNull { it.actionSeq }.toSet()
        val legacy =
            memo.workflowInstanceId
                ?.let(workflows::history)
                .orEmpty()
                .filter { !it.remark.isNullOrBlank() && it.seq !in linked }
        val people =
            stamps.flatMap { listOfNotNull(it.actorId, it.actingForId) } +
                noteList.flatMap { listOfNotNull(it.authorId, it.actingForId) } +
                legacy.flatMap { listOfNotNull(it.actorId, it.actingForId) } + listOfNotNull(memo.initiatorId)
        val names = users.names(people)
        val levels = clearances.levels()
        return Facts(
            memo = memo,
            stamps = stamps,
            final = final,
            document = documentOf(final),
            bound = attachments.bound(memo.id, final.round),
            notes = noteList,
            legacy = legacy,
            levelOf = { id, rank -> levels.firstOrNull { it.id == id } ?: levels.first { it.rank == (rank ?: 0) } },
            name = { id -> id?.let { names[it] ?: "Unknown person" } },
        )
    }

    private fun cover(
        facts: Facts,
        frame: Frame,
        number: String,
        record: String,
        renderer: String,
    ): PageDocument {
        val memo = facts.memo
        return RecordPages.cover(
            frame.header,
            frame.footer,
            listOf(
                "Number" to number,
                "Subject" to memo.subject,
                "Memo type" to types.forMemo(memo).name,
                "Classification" to facts.memoLevel.name,
                "Record classification" to facts.recordLevel.name,
                "Initiator" to facts.name(memo.initiatorId).orEmpty(),
                "Created" to RecordPages.time(memo.createdAt),
                "Approved" to "${RecordPages.time(facts.final.decidedAt)} by ${facts.name(facts.final.actorId)}",
                "Document" to "Version ${facts.document.number} · ${facts.document.source.name}",
                "Document SHA-256" to facts.document.source.sha256,
                "Attachments" to facts.bound.size.toString(),
                "Record" to record,
                "Made with" to renderer,
            ),
        )
    }

    private fun noteLines(facts: Facts): List<NoteLine> {
        val corrected = facts.notes.mapNotNull { it.correctsId }.toSet()
        val notes =
            facts.notes.map { n ->
                NoteLine(
                    n.round,
                    facts.name(n.authorId).orEmpty(),
                    facts.name(n.actingForId),
                    noteLabel(n.kind, n.actionKind),
                    n.createdAt,
                    body(n.body),
                    n.id in corrected,
                    restricted(n.body, facts.memo.classificationRank),
                )
            }
        val remarks =
            facts.legacy.map { a ->
                NoteLine(
                    a.round,
                    facts.name(a.actorId).orEmpty(),
                    facts.name(a.actingForId),
                    "${a.kind.name.lowercase().replaceFirstChar(Char::uppercase)} (remark)",
                    a.occurredAt,
                    NoteDocument.ofText(a.remark.orEmpty()),
                    false,
                )
            }
        return notes + remarks
    }

    private fun attachmentParts(
        facts: Facts,
        frame: Frame,
    ): List<PlannedPart> =
        facts.bound.mapIndexed { i, (b, row) ->
            val version = files.version(b.fileVersionId) ?: throw RecordPlanException("ATTACHMENT_MISSING")
            val source = SourceFile(b.fileVersionId, b.sha256, version.name, version.mediaType, version.sizeBytes)
            val title = "Attachment ${i + 1}: ${row.title}"
            val level = facts.levelOf(row.classificationId, row.classificationRank)
            PlannedPart.Source(PartKind.ATTACHMENT, title, "A${i + 1}-", source, placeholder(frame, title, source, level.name))
        }

    private data class DocumentRef(
        val number: Int,
        val source: SourceFile,
    )

    /** The document version the final stamp binds, checked against the stored version's hash. */
    private fun documentOf(final: MemoApproval): DocumentRef {
        val versionId =
            final.content
                .get("documentVersionId")
                ?.asString()
                ?.let(UUID::fromString)
        val version = versionId?.let(files::version) ?: throw RecordPlanException("NO_DOCUMENT")
        val stamped = final.content.get("sha256")?.asString()
        if (stamped == null || !stamped.equals(version.sha256, ignoreCase = true)) throw RecordPlanException("HASH_MISMATCH")
        return DocumentRef(version.number, SourceFile(version.id, version.sha256, version.name, version.mediaType, version.sizeBytes))
    }

    private fun stampLine(
        stamp: MemoApproval,
        name: (UUID?) -> String?,
    ): StampLine {
        val auth = stamp.auth
        return StampLine(
            round = stamp.round,
            person = name(stamp.actorId).orEmpty(),
            actingFor = name(stamp.actingForId),
            decision = stamp.decision,
            at = stamp.decidedAt,
            factors = auth.get("factors")?.let { node -> (0 until node.size()).map { node[it].asString() } }.orEmpty(),
            secondFactorAt =
                auth
                    .get("secondFactorAt")
                    ?.takeIf { !it.isNull }
                    ?.asString()
                    ?.let(Instant::parse),
            documentNumber =
                stamp.content
                    .get("documentNumber")
                    ?.takeIf { it.isNumber }
                    ?.asInt(),
            documentHash =
                stamp.content
                    .get("sha256")
                    ?.takeIf { !it.isNull }
                    ?.asString(),
            recordHash = stamp.recordHash,
            previousHash = stamp.prevHash,
        )
    }

    private fun body(stored: String): NoteDocument = NoteDoc.parse(json.readTree(stored))

    /** Memo references in a note to memos classified above this one: the record does not name them. */
    private fun restricted(
        stored: String,
        rank: Int,
    ): Set<UUID> = body(stored).memoRefs().filter { ref -> (memos.find(ref)?.classificationRank ?: Int.MAX_VALUE) > rank }.toSet()

    private fun placeholder(
        frame: Frame,
        title: String,
        source: SourceFile,
        classification: String,
    ) = RecordPages.placeholder(
        frame.header,
        frame.footer,
        PlaceholderLine(
            title,
            "${source.name} (${source.mediaType})",
            source.sizeBytes,
            source.sha256,
            classification,
            "This file cannot be shown as PDF/A. It is kept unchanged as a record file of its own; this SHA-256 identifies it.",
        ),
    )

    private fun noteLabel(
        kind: NoteKind,
        action: String?,
    ): String =
        when (kind) {
            NoteKind.COMMENT -> "Comment"
            NoteKind.CORRECTION -> "Correction"
            NoteKind.REMARK -> "Remark on ${action?.lowercase() ?: "a decision"}"
        }
}
