package com.memo.minutes.internal

import com.memo.audit.AuditLog
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileContents
import com.memo.files.FileInfo
import com.memo.files.FilePurpose
import com.memo.files.FileType
import com.memo.files.FileUploads
import com.memo.files.Files
import com.memo.files.RenditionConverter
import com.memo.files.RenditionInput
import com.memo.files.RenditionOutcome
import com.memo.files.StorageClass
import com.memo.templating.ControlParagraph
import com.memo.templating.DocxControls
import com.memo.templating.DocxCopies
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import org.apache.pdfbox.util.Matrix
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/**
 * A reader's copy of the minutes (ADR-0066, design E06 §6): a PDF built by the `minutes-copies` job, in ordinary storage
 * (the approved copies of T7 are records), stored by the system only and never served by the generic files endpoints: the
 * minutes' copy endpoint streams it to the one reader whose redaction set it is, auditing the open.
 */
@Component
class MinutesCopyPurpose : FilePurpose {
    override val key = KEY
    override val allowedTypes = setOf(FileType.PDF)
    override val maxBytes = MAX_BYTES
    override val maxPdfPages = MAX_PAGES
    override val storage = StorageClass.ORDINARY
    override val auditReads = true

    override fun mayUpload(ownerId: String?): Boolean = ActorContext.currentOrNull()?.actor is Actor.System

    override fun mayRead(file: FileInfo): Boolean = false

    companion object {
        const val KEY = "minutes.copy"
        const val MAX_BYTES = 64L shl 20
        const val MAX_PAGES = 2000
    }
}

/**
 * What a redacted item becomes in a reader's copy (ADR-0066), pure: the recused reader's line (the heading, "You declared
 * an interest in this item and took no part." and the decision as recorded: outcome, number, text, conditions, validity),
 * or the heading of an item they may not read ("Item n — restricted" when even its title is not theirs) with "The record
 * of this item is restricted."
 */
object CopyBlocks {
    fun block(
        item: com.memo.meeting.AgendaItemRef,
        kind: RedactionKind,
        decision: CurrentDecision?,
        number: String?,
    ): List<ControlParagraph> {
        val heading =
            if (kind == RedactionKind.HIDDEN || item.titleHidden) {
                "Item ${item.number} — restricted"
            } else {
                "${item.number}. ${MinutesTemplateData.clean(item.title)}"
            }
        val entry = decision?.entry
        return buildList {
            add(ControlParagraph(heading, bold = true))
            when {
                kind != RedactionKind.RECUSED -> add(ControlParagraph(RESTRICTED))
                entry == null -> addAll(listOf(ControlParagraph(RECUSED), ControlParagraph(NO_DECISION)))
                else -> addAll(listOf(ControlParagraph(RECUSED)) + decided(entry, number))
            }
        }
    }

    /** The decision as the recused reader reads it: outcome, number, text, conditions, validity. */
    private fun decided(
        entry: EntryRow,
        number: String?,
    ): List<ControlParagraph> {
        val lines =
            buildList {
                add(ControlParagraph("The decision: ${entry.outcomeLabel.uppercase(Locale.ENGLISH)}", bold = true))
                number?.let { add(ControlParagraph("Resolution $it")) }
                entry.text?.takeIf { it.isNotBlank() }?.let { add(ControlParagraph(MinutesTemplateData.clean(it))) }
                if (entry.conditions.isNotEmpty()) {
                    add(ControlParagraph("Conditions:"))
                    entry.conditions.forEachIndexed { i, c -> add(ControlParagraph("${i + 1}. ${MinutesTemplateData.clean(c)}")) }
                }
                entry.validUntil?.let { add(ControlParagraph("Valid until $it.")) }
            }
        return lines
    }

    /**
     * The recorded text of each redacted item that must not appear anywhere else in a copy (its leak check): the notes and
     * dissent reasons of every redacted item, its papers' titles and its actions; for an item the reader may not read at
     * all, also its decision's text and conditions and what was sought (and its title when hidden from them). Short
     * strings (under 16 characters) are not checked: they are too common to tell anything.
     */
    fun sensitive(
        source: DraftSource,
        set: RedactionSet,
    ): Map<UUID, List<String>> =
        set.redacted.associate { r ->
            val item = source.items.firstOrNull { it.id == r.itemId }
            val decision = source.decisions[r.itemId]?.entry
            val texts =
                buildList {
                    source.notes[r.itemId]?.let(::add)
                    decision?.dissent?.mapNotNull { it.reason }?.let(::addAll)
                    source.details[r.itemId]
                        ?.papers
                        ?.map { it.title }
                        ?.let(::addAll)
                    source.actions
                        .filter { it.itemId == r.itemId }
                        .map { it.action }
                        .let(::addAll)
                    if (r.kind != RedactionKind.RECUSED) {
                        decision?.text?.let(::add)
                        decision?.conditions?.let(::addAll)
                        item?.resolutionSought?.let(::add)
                        if (r.kind == RedactionKind.HIDDEN || item?.titleHidden == true) item?.title?.let(::add)
                    } else {
                        item?.resolutionSought?.takeIf { it.trim() != decision?.text?.trim() }?.let(::add)
                    }
                }
            r.itemId to texts.map(::normal).filter { it.length >= MIN_CHECKED }.distinct()
        }

    /** [text] for comparison: lower case, whitespace collapsed. */
    fun normal(text: String): String = text.lowercase(Locale.ROOT).replace(WHITESPACE, " ").trim()

    /**
     * [docx] made into the copy for [redacted] (the draft's or the approved one's, ADR-0066): no unresolved changes left
     * (else `UNRESOLVED_CHANGES`), each redacted item's control replaced by its block, the notes, comments and properties a
     * copy must not carry dropped, and checked that nothing recorded of a redacted item appears anywhere in it (else
     * `LEAK`, with the items' numbers: closed, never served).
     */
    fun redacted(
        docx: ByteArray,
        redacted: List<Redaction>,
        from: DraftSource,
        controls: DocxControls,
        packages: DocxCopies,
    ): ByteArray {
        val marks = controls.marks(docx.inputStream())
        if (!marks.clean) copyFailed("UNRESOLVED_CHANGES")
        val items = from.items.associateBy { it.id }
        val contents =
            redacted.associate { r ->
                val item = items[r.itemId] ?: copyFailed("ITEM_GONE")
                val decision = from.decisions[r.itemId]
                MinutesStructure.tag(r.itemId) to block(item, r.kind, decision, decision?.let { from.resolutions[it.decision.id] })
            }
        val replaced = if (contents.isEmpty()) docx else controls.replace(docx.inputStream(), contents)
        // The rows of meeting-level loops (the resolutions table) that belong to a redacted item go with it (E06 T7).
        val dropped = packages.dropRows(replaced, redacted.map { MinutesStructure.rowTag(it.itemId) }.toSet())
        val cleaned = packages.forCopy(dropped)
        requireNoLeak(from, RedactionSet(redacted), packages.text(cleaned.inputStream()))
        return cleaned
    }

    /** Fails `LEAK` (with the items' numbers) when [text] holds anything recorded of an item [set] redacts. */
    fun requireNoLeak(
        from: DraftSource,
        set: RedactionSet,
        text: String,
    ) {
        val normalised = normal(text)
        val leaks = sensitive(from, set).filterValues { strings -> strings.any { it in normalised } }.keys
        if (leaks.isNotEmpty()) copyFailed("LEAK", leaks.mapNotNull { id -> from.items.firstOrNull { it.id == id }?.number })
    }

    const val RECUSED = "You declared an interest in this item and took no part."
    const val RESTRICTED = "The record of this item is restricted."
    const val NO_DECISION = "No decision is recorded."
    private const val MIN_CHECKED = 16
    private val WHITESPACE = Regex("\\s+")
}

/** "DRAFT" across every page of a copy of draft minutes (flow AF2), with PDFBox in file-backed memory. */
object DraftStamp {
    fun stamp(pdf: ByteArray): ByteArray =
        Loader.loadPDF(RandomAccessReadBuffer(pdf), "", null, null, MemoryUsageSetting.setupTempFileOnly().streamCache).use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
            val faint = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = ALPHA }
            doc.pages.forEach { page ->
                val box = page.mediaBox
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.setGraphicsStateParameters(faint)
                    cs.setNonStrokingColor(GREY, GREY, GREY)
                    cs.beginText()
                    cs.setFont(font, SIZE)
                    val width = font.getStringWidth(WORD) / FONT_UNITS * SIZE
                    val angle = Math.toRadians(ANGLE)
                    val x = box.width / 2 - (width / 2) * Math.cos(angle).toFloat()
                    val y = box.height / 2 - (width / 2) * Math.sin(angle).toFloat()
                    cs.setTextMatrix(Matrix.getRotateInstance(angle, x, y))
                    cs.showText(WORD)
                    cs.endText()
                }
            }
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }

    const val WORD = "DRAFT"
    private const val SIZE = 96f
    private const val FONT_UNITS = 1000f
    private const val ANGLE = 45.0
    private const val ALPHA = 0.18f
    private const val GREY = 0.5f
}

/** Where a reader's copy stands for the copy endpoint: built (to stream), or being built (202, try again). */
sealed interface CopyLookup {
    data class Ready(
        val copy: CopyRow,
    ) : CopyLookup

    data object Building : CopyLookup
}

/**
 * The readers' copies of the minutes (ADR-0066, design E06 §1.5, §9): one PDF per distinct redaction set among the
 * readers (at most [MAX_COPIES]) and version of the draft, made by the `minutes-copies` job from the draft's current
 * version: each redacted item's control replaced by its block ([CopyBlocks]), notes and comments no longer referenced and
 * the document properties dropped ([DocxControls.forCopy]), checked that nothing recorded of a redacted item appears
 * elsewhere in it (else `LEAK`), converted by the document server ([RenditionConverter]), "DRAFT" stamped on every page,
 * and stored by the system as a `minutes.copy` file. A reader whose set has no copy yet gets one built on demand.
 */
@Service
class MinutesCopies(
    private val store: MinutesStore,
    private val copyStore: MinutesCopyStore,
    private val people: MinutesPeople,
    private val source: MinutesDraftSource,
    private val files: Files,
    private val contents: FileContents,
    private val uploads: FileUploads,
    private val controls: DocxControls,
    private val packages: DocxCopies,
    private val converter: ObjectProvider<RenditionConverter>,
    private val jobs: MinutesJobs,
    private val transactions: TenantTransactions,
    private val ids: IdGenerator,
    private val audit: AuditLog,
    private val clock: Clock,
    private val approved: ApprovedCopies,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Asks for [row]'s copies to be built (in the caller's transaction, [row] locked): now, or [delayed] after a save. */
    fun request(
        row: MinutesRow,
        delayed: Boolean = false,
    ) {
        if (row.state !in COPYING) return
        val locked = checkNotNull(store.lock(row.meetingId))
        val after = locked.copy(copiesSeq = locked.copiesSeq + 1)
        check(store.update(locked, after)) { "the locked minutes moved on" }
        jobs.copies(row.meetingId, after.copiesSeq, delayed)
    }

    /**
     * The copy for [set] of [row]'s draft (inside a transaction): the latest built one, or, when there is none, one asked
     * for now ([CopyLookup.Building]).
     */
    fun lookup(
        row: MinutesRow,
        set: RedactionSet,
    ): CopyLookup {
        val built = copyStore.latestDone(row.meetingId, CopyStage.DRAFT, set.key)
        if (built == null) current(row)?.let { version -> if (copyStore.insert(pending(row.meetingId, version, set))) request(row) }
        return built?.let { CopyLookup.Ready(it) } ?: CopyLookup.Building
    }

    /** The distinct sets of [row]'s readers today (the reviewer and recused secretaries among them), each with its readers. */
    fun sets(row: MinutesRow): Map<RedactionSet, List<UUID>> {
        val readers = people.readers(row) + listOfNotNull(row.reviewerId).associateWith { ReaderKind.OFFICER }
        val sets = people.setsOf(row, readers)
        return readers.keys.groupBy { sets.getValue(it) }
    }

    /**
     * The `minutes-copies` job for [meetingId] (outside a transaction): the readers' sets for the draft's current version
     * are recorded (refused past [MAX_COPIES]: audited, the secretaries told), then each copy not built yet is built. A
     * conversion worth retrying fails the job (db-scheduler retries it); on the [last] try, and for a final failure, the
     * copy is marked FAILED with a code.
     */
    fun build(
        meetingId: UUID,
        last: Boolean,
    ) {
        // Approved minutes: their copies are records of their own (E06 T7).
        if (transactions.readInTenant(TenantContext.current()) { store.find(meetingId)?.state } == MinutesStage.APPROVED) {
            approved.build(meetingId, last)
        } else {
            buildDraft(meetingId, last)
        }
    }

    /** The draft's copies (see [build]). */
    private fun buildDraft(
        meetingId: UUID,
        last: Boolean,
    ) {
        val tenant = TenantContext.current()
        val plan = transactions.inTenant(tenant) { plan(meetingId) }?.takeIf { it.open.isNotEmpty() } ?: return
        val docx = ByteArrayOutputStream().also { contents.write(plan.version, it) }.toByteArray()
        val from = transactions.readInTenant(tenant) { source.read(meetingId) } ?: return
        var retry: Throwable? = null
        plan.open.forEach { copy ->
            try {
                val built = render(copy, docx, from)
                transactions.inTenant(tenant) { finish(copy, built) }
            } catch (e: CopyFailed) {
                transactions.inTenant(tenant) { failed(copy, e.code, e.items) }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                log.warn("Copy {} of the minutes of {} not built: {}", copy.id, meetingId, e.toString())
                if (last) transactions.inTenant(tenant) { failed(copy, "ERROR", emptyList()) } else retry = e
            }
        }
        retry?.let { throw it }
    }

    /** What the job builds: the current version and the copies of it not built yet; null when the minutes make no copies. */
    private fun plan(meetingId: UUID): CopyPlan? {
        val (row, version) =
            store.find(meetingId)?.takeIf { it.state in COPYING }?.let { r -> current(r)?.let { r to it } } ?: return null
        val sets = sets(row).keys
        val refused = sets.size > MAX_COPIES
        if (refused) {
            log.warn("The minutes of {} need {} copies: none is built past {}", meetingId, sets.size, MAX_COPIES)
            audit.record("minutes.copies.refused", MinutesDrafts.OBJECT_TYPE, meetingId.toString(), after = mapOf("sets" to sets.size))
        } else {
            sets.forEach { copyStore.insert(pending(meetingId, version, it)) }
        }
        val open = copyStore.ofVersion(meetingId, CopyStage.DRAFT, version).filter { it.state == CopyState.PENDING }
        return if (refused) null else CopyPlan(checkNotNull(files.version(version)), open)
    }

    /** The PDF of [copy]: the controls replaced, the package cleaned, checked, converted and stamped. */
    private fun render(
        copy: CopyRow,
        docx: ByteArray,
        from: DraftSource,
    ): ByteArray {
        val cleaned = CopyBlocks.redacted(docx, copy.redacted, from, controls, packages)
        val convert = converter.ifAvailable ?: copyFailed("NO_CONVERTER")
        val pdf = ByteArrayOutputStream()
        val input = RenditionInput("minutes-copy.docx", FileType.DOCX, cleaned.size.toLong()) { cleaned.inputStream() }
        when (val outcome = convert.toPdf(input, "minutes-copy-${copy.id}", pdf)) {
            is RenditionOutcome.Written -> Unit
            is RenditionOutcome.Final -> copyFailed("CONVERSION_${outcome.code}".take(CODE_LENGTH))
            is RenditionOutcome.Retry -> throw IllegalStateException("conversion to retry: ${outcome.code}")
        }
        return if (copy.stage == CopyStage.DRAFT) DraftStamp.stamp(pdf.toByteArray()) else pdf.toByteArray()
    }

    /** Stores [pdf] as the system and marks [copy] built (if nobody did meanwhile); audited `minutes.copy.built`. */
    private fun finish(
        copy: CopyRow,
        pdf: ByteArray,
    ) {
        val locked = copyStore.lock(copy.id)?.takeIf { it.state == CopyState.PENDING } ?: return
        val versionId =
            ActorContext.asSystem("minutes-copies") {
                uploads.upload(MinutesCopyPurpose.KEY, copy.meetingId.toString(), "minutes-copy.pdf", pdf, "minutes-copy:${copy.id}")
            }
        val version = checkNotNull(files.version(versionId))
        val after =
            locked.copy(
                state = CopyState.DONE,
                fileId = version.fileId,
                fileVersionId = version.id,
                sha256 = version.sha256,
                builtAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
            )
        check(copyStore.update(locked, after)) { "the locked copy moved on" }
        audit.record(
            "minutes.copy.built",
            MinutesDrafts.OBJECT_TYPE,
            copy.meetingId.toString(),
            after =
                mapOf(
                    "copyId" to copy.id.toString(),
                    "stage" to copy.stage.name,
                    "setKey" to copy.setKey,
                    "redactedItems" to copy.redacted.size,
                    "sourceVersionId" to copy.sourceVersionId.toString(),
                    "sha256" to version.sha256,
                ),
        )
    }

    private fun failed(
        copy: CopyRow,
        code: String,
        items: List<String>,
    ) {
        val locked = copyStore.lock(copy.id)?.takeIf { it.state == CopyState.PENDING } ?: return
        check(copyStore.update(locked, locked.copy(state = CopyState.FAILED, error = code))) { "the locked copy moved on" }
        audit.record(
            "minutes.copy.failed",
            MinutesDrafts.OBJECT_TYPE,
            copy.meetingId.toString(),
            after = mapOf("copyId" to copy.id.toString(), "setKey" to copy.setKey, "error" to code, "items" to items),
        )
    }

    private fun pending(
        meetingId: UUID,
        versionId: UUID,
        set: RedactionSet,
    ) = CopyRow(
        id = ids.next(),
        meetingId = meetingId,
        stage = CopyStage.DRAFT,
        redacted = set.redacted,
        setKey = set.key,
        sourceVersionId = versionId,
        state = CopyState.PENDING,
        error = null,
        fileId = null,
        fileVersionId = null,
        sha256 = null,
        createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
        builtAt = null,
        version = 0,
    )

    private fun current(row: MinutesRow): UUID? = row.fileId?.let(files::info)?.currentVersionId

    private data class CopyPlan(
        val version: com.memo.files.FileVersionInfo,
        val open: List<CopyRow>,
    )

    companion object {
        /** At most this many distinct copies per minutes (design E06 §8). */
        const val MAX_COPIES = 50

        /** The stages whose draft is read through copies. */
        val COPYING = setOf(MinutesStage.CHAIR_REVIEW, MinutesStage.CIRCULATED, MinutesStage.AWAITING_CHAIR)
        private const val CODE_LENGTH = 100
    }
}

/** A copy that cannot be built from this version: [code] is stored, [items] (numbers) audited. */
internal class CopyFailed(
    val code: String,
    val items: List<String> = emptyList(),
) : RuntimeException(code)

internal fun copyFailed(
    code: String,
    items: List<String> = emptyList(),
): Nothing = throw CopyFailed(code, items)
