package com.memo.meeting

import com.memo.common.tenant.TenantContext
import com.memo.files.FileRenditions
import com.memo.files.RenditionKind
import com.memo.files.RenditionState
import com.memo.files.internal.RenditionWorker
import com.memo.meeting.internal.DeclarationRow
import com.memo.meeting.internal.DeclareInput
import com.memo.meeting.internal.ItemChange
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.invite.InvitationScheduler
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.Person
import com.memo.testsupport.query
import org.apache.pdfbox.pdfwriter.compress.CompressParameters
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The planted meeting of the recusal leak suite (test plan E04, T5; [RecusalLeakIT], [RecusalMailIT]): two items carrying
 * canary strings, four viewers recused from them, the controls, and the checks every probe shares (no canary in a body,
 * a header or a text; the recused line and nothing more; the differential's normal form).
 */
abstract class RecusalLeakFixture : RecusalFixture() {
    @Autowired lateinit var renditions: FileRenditions

    /** Runs a text rendition here when the scheduled task is slow or ran in another test context (see [extractedText]). */
    @Autowired internal lateinit var worker: RenditionWorker

    @Autowired lateinit var invitations: InvitationScheduler

    @BeforeEach
    fun quiet() {
        invitations.jobs = false
    }

    @AfterEach
    fun loud() {
        invitations.jobs = true
    }

    /** The probed viewers, by the matrix column that names them (`recusal:` in the authorisation matrix). */
    protected enum class Viewer(
        val column: String,
    ) {
        RECUSED("recused"),
        AWAITING("awaiting"),
        SECRETARY("recusedSecretary"),
        CHAIR("recusedChair"),
    }

    protected fun Viewer.person(): Person =
        when (this) {
            Viewer.RECUSED -> d1
            Viewer.AWAITING -> d2
            Viewer.SECRETARY -> secretary
            Viewer.CHAIR -> chair
        }

    protected val Viewer.officer: Boolean get() = this == Viewer.SECRETARY || this == Viewer.CHAIR

    /** A member who declared nothing: the control. */
    protected val control: Person get() = d3

    /**
     * A published agenda (revision 2) with an item in the consent section ([item], its title shown) and one for decision
     * ([hidden], its title hidden), around two others. With [content] the two carry the canaries (description, resolution
     * sought, papers whose titles, file names, content and extracted text are canaries) and the four viewers and an observer
     * declare on them after revision 1; revision 2 then changes their papers and text. Without it (the twin) they are bare
     * lines and nobody declares. Both: another item's paper is replaced and an item added in revision 2, the notices of
     * both revisions are sent, and the control asks to discuss [item].
     */
    inner class Planted(
        val content: Boolean,
        days: Long = DAYS_AHEAD,
    ) {
        val meeting: MeetingRow = meeting(days = days)
        val other = addItem(meeting, "Liquidity report")
        val item = addItem(meeting, TITLE, section = "Consent items")
        val hidden = addItem(meeting, CANARY_TITLE)
        val otherFile = upload(meeting, "liquidity.pdf")
        val files = mutableListOf<UUID>()
        val papers = mutableListOf<UUID>()
        val declared = mutableMapOf<Person, DeclarationRow>()
        lateinit var observed: DeclarationRow

        /** The officer kinds of in-app notification each viewer held once they had declared (none may follow). */
        val officerKindsAfterDeclaring = mutableMapOf<Person, Long>()

        init {
            val description = CANARY_DESCRIPTION.takeIf { content }
            change(meeting, item, ItemChange(TITLE, description, resolutionSought = CANARY_RESOLUTION.takeIf { content }))
            change(meeting, hidden, ItemChange(CANARY_TITLE, description, titleHidden = true))
            paper(meeting, other, otherFile, title = "Liquidity paper")
            if (content) {
                files += upload(meeting, "$CANARY_FILE-delta.pdf", canaryPdf())
                files += upload(meeting, "$CANARY_FILE-mill.pdf", canaryPdf())
                papers += paper(meeting, item, files[0], title = CANARY_PAPER)
                papers += paper(meeting, hidden, files[1], title = CANARY_PAPER)
            }
            publish(meeting)
            send(meeting, 1)
            (Viewer.entries.map { it.person() } + control).forEach { seen(it, 1) }
            if (content) declareAndRevise()
            replaceAs(secretary2, otherFile)
            addItem(meeting, "Capital plan")
            val at = version(meeting)
            asUser(secretary2.account) { publisher.publish(meeting.id, at, "Revision two", true) }
            send(meeting, 2)
            val asked = send(HttpMethod.POST, "/api/v1/meetings/${meeting.id}/agenda/items/$item/discussion-requests", control)
            assertThat(asked).hasStatus(HttpStatus.CREATED)
        }

        /** The viewers and the observer declare; the other secretary changes the two items' text and papers. */
        private fun declareAndRevise() {
            // The officers first, so that neither is told of a declaration after their own.
            listOf(secretary, chair, d1).forEach { person ->
                declared[person] = declareOn(person, item, DeclarationIntent.LEAVE)
                declareOn(person, hidden, DeclarationIntent.LEAVE)
                officerKindsAfterDeclaring[person] = officerKinds(person)
            }
            declared[d2] = declareOn(d2, item, DeclarationIntent.ASK_CHAIR)
            declareOn(d2, hidden, DeclarationIntent.ASK_CHAIR)
            officerKindsAfterDeclaring[d2] = officerKinds(d2)
            observed = declareOn(observer, item, DeclarationIntent.LEAVE)
            val revised = ItemChange(TITLE, "$CANARY_DESCRIPTION, revised", resolutionSought = CANARY_RESOLUTION)
            var at = version(meeting)
            asUser(secretary2.account) { items.update(meeting.id, item, at, revised) }
            at = version(meeting)
            val revisedHidden = ItemChange(CANARY_TITLE, "$CANARY_DESCRIPTION, revised", titleHidden = true)
            asUser(secretary2.account) { items.update(meeting.id, hidden, at, revisedHidden) }
            files.forEach { replaceWithCanary(it) }
        }

        val section: UUID get() = section(meeting, "Consent items")

        /** The two planted items. */
        val both: Set<UUID> get() = setOf(item, hidden)

        private fun declareOn(
            person: Person,
            on: UUID,
            intent: DeclarationIntent,
        ): DeclarationRow = asUser(person.account) { declarations.declare(meeting.id, on, DeclareInput(natureOf(person), intent)) }

        private fun seen(
            person: Person,
            revision: Int,
        ) {
            val result = send(HttpMethod.POST, "/api/v1/meetings/${meeting.id}/seen", person, """{"revision":$revision}""", emptyMap())
            assertThat(result).hasStatus(HttpStatus.NO_CONTENT)
        }
    }

    /** Every canary [person] must never be answered: everything planted except their own declaration's nature. */
    protected fun canaries(person: Person): List<String> =
        listOf(CANARY_TITLE, CANARY_DESCRIPTION, CANARY_RESOLUTION, CANARY_PAPER, CANARY_FILE, CANARY_CONTENT) +
            declarants().filter { it != person }.map { natureOf(it) }

    protected fun declarants() = listOf(d1, d2, secretary, chair, observer)

    protected fun natureOf(person: Person): String =
        "$CANARY_NATURE " +
            when (person) {
                d1 -> "recused"
                d2 -> "awaiting"
                secretary -> "secretary"
                chair -> "chair"
                else -> "observer"
            }

    /** [result] answered [person] below [statusBelow], with no canary of theirs in its body or headers. */
    internal fun clean(
        person: Person,
        result: MvcTestResult,
        statusBelow: Int = HttpStatus.BAD_REQUEST.value(),
    ) {
        val uri = result.mvcResult.request.requestURI
        assertThat(result.response.status).describedAs("status of $uri").isLessThan(statusBelow)
        val body = result.response.contentAsByteArray.toString(Charsets.UTF_8)
        val headers = result.response.headerNames.flatMap { name -> result.response.getHeaders(name).map { "$name: $it" } }
        canaries(person).forEach { canary ->
            assertThat(body).describedAs("a canary in $uri").doesNotContain(canary)
            assertThat(headers).describedAs("a canary in the headers of $uri").noneMatch { canary in it }
        }
    }

    /**
     * What a recused viewer reads of the planted items wherever a line is shown: the line (number, time, purpose, the title
     * unless hidden, their own declaration) and nothing else.
     */
    protected fun recusedLines(
        person: Person,
        agenda: JsonNode,
        p: Planted,
    ) {
        val lines = agenda.lines()
        assertThat(lines.getValue(p.item)["access"].asString()).isEqualTo("TITLE")
        assertThat(lines.getValue(p.hidden)["access"].asString()).isEqualTo("HIDDEN")
        p.both.forEach { id ->
            val line = lines.getValue(id)
            assertThat(line["recused"].asBoolean()).describedAs("recused on $id for ${person.id}").isTrue()
            assertThat(line.absent("description") && line.absent("resolutionSought") && line.absent("discussion")).isTrue()
            listOf("papers", "presenters", "subItems", "declarations", "changes").forEach { field ->
                assertThat(line[field]?.list().orEmpty()).describedAs("$field of a recused line").isEmpty()
            }
        }
    }

    /** The officer kinds of in-app notification [person] holds: about declarations, rulings and requests. */
    protected fun officerKinds(person: Person): Long = OFFICER_KINDS.sumOf { notified(person, it) }

    /** Every in-app notification of [person], as its kind and parameters. */
    protected fun inApp(person: Person): List<String> =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.query("select kind || ' ' || params::text from notification_item where user_id = ?", person.id) { it.getString(1) }
        }

    /**
     * [person]'s in-app agenda notices about [meeting] (publication and changes), oldest first, as kinds and parameters. The
     * officers' notices about the items themselves differ by design (a recused officer is not told of them): the IN_APP
     * channel probe checks that none reaches them.
     */
    protected fun notificationsOf(
        person: Person,
        meeting: MeetingRow,
    ): JsonNode {
        val rows =
            AppRoleJdbc.inTransaction(tenant.id) { c ->
                c.query(
                    "select kind || ' ' || params::text from notification_item " +
                        "where user_id = ? and link like ? and kind like 'MEETING_AGENDA%' order by created_at, kind",
                    person.id,
                    "%${meeting.id}%",
                ) { it.getString(1) }
            }
        return JsonNodeFactory.instance.arrayNode().also { array -> rows.forEach { array.add(it) } }
    }

    /**
     * [node] as text for the differential: the lines (and changes) of the [drop]ped items left out, every id, time, date and
     * meeting number replaced by a placeholder, and the agenda's own version (it counts the twin's fewer edits) left out.
     */
    protected fun normal(
        node: JsonNode,
        drop: Set<String>,
    ): String {
        fun walk(n: JsonNode): JsonNode? =
            when {
                n.isObject -> {
                    if (listOfNotNull(n["id"], n["itemId"]).any { it.asString() in drop }) {
                        null
                    } else {
                        JsonNodeFactory.instance.objectNode().also { out ->
                            n.properties().filter { it.key !in VOLATILE }.forEach { (key, value) -> walk(value)?.let { out.set(key, it) } }
                        }
                    }
                }

                n.isArray -> {
                    JsonNodeFactory.instance.arrayNode().also { out -> n.forEach { e -> walk(e)?.let { out.add(it) } } }
                }

                n.isString -> {
                    JsonNodeFactory.instance.stringNode(placeholders(n.asString()))
                }

                else -> {
                    n
                }
            }
        return walk(node).toString()
    }

    private fun placeholders(text: String): String =
        text
            .replace(UUID_PATTERN, "<id>")
            .replace(TIME_PATTERN, "<time>")
            .replace(DATE_PATTERN, "<date>")
            .replace(MEETING_NUMBER, "<number>")

    /** The extracted text of [file]'s current version, once the text rendition is done. */
    protected fun extractedText(file: UUID): String {
        val versions = read("/api/v1/files/$file", secretary2.session).json()["versions"].list()
        val version = UUID.fromString(versions.first()["versionId"].asString())
        val deadline = Instant.now().plus(Duration.ofSeconds(RENDITION_WAIT_SECONDS))
        while (Instant.now().isBefore(deadline)) {
            val done =
                transactions
                    .readInTenant(tenant.id) { renditions.of(version) }
                    .any { it.kind == RenditionKind.TEXT && it.state == RenditionState.DONE }
            if (done) {
                val out = ByteArrayOutputStream()
                TenantContext.call(tenant.id) { renditions.write(version, RenditionKind.TEXT, out) }
                return out.toString(Charsets.UTF_8)
            }
            // Test contexts share the scheduler table: the rendition task may have run in a context without object
            // storage and be waiting to retry. Run it here, as RenditionIT does; a claim held elsewhere makes this a no-op.
            runCatching { TenantContext.call(tenant.id) { worker.run(version, RenditionKind.TEXT) } }
            Thread.sleep(POLL_MILLIS)
        }
        error("the text of $file was not extracted")
    }

    /** A new version of [file] that carries the canaries too, uploaded by the other secretary. */
    private fun replaceWithCanary(file: UUID) {
        val current = read("/api/v1/files/$file", secretary2.session).json()
        val replaced =
            upload(
                secretary2.session,
                canaryPdf(),
                "/api/v1/files/$file/versions",
                "$CANARY_FILE-v2.pdf",
                mapOf("If-Match" to "\"${current["version"]}\""),
            )
        assertThat(replaced).hasStatus(HttpStatus.CREATED)
    }

    /** A one-page PDF whose document title, written uncompressed, is the content canary. */
    protected fun canaryPdf(): ByteArray =
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.documentInformation.title = CANARY_CONTENT
            ByteArrayOutputStream().also { doc.save(it, CompressParameters.NO_COMPRESSION) }.toByteArray()
        }

    companion object {
        const val CANARY_TITLE = "CANARY-T hidden title"
        const val CANARY_DESCRIPTION = "CANARY-D description"
        const val CANARY_RESOLUTION = "CANARY-R resolution sought"
        const val CANARY_PAPER = "CANARY-P paper title"
        const val CANARY_FILE = "CANARY-F"
        const val CANARY_CONTENT = "CANARY-X paper content"
        const val CANARY_NATURE = "CANARY-N nature of"
        private const val RENDITION_WAIT_SECONDS = 60L
        private const val POLL_MILLIS = 250L

        /** Kinds told to officers about an item, or to the ruler. */
        private val OFFICER_KINDS =
            listOf(
                "MEETING_DECLARATION_MADE",
                "MEETING_RULING_NEEDED",
                "MEETING_DISCUSSION_REQUESTED",
                "MEETING_LATE_PAPER",
                "MEETING_ITEM_WITHDRAWN",
            )

        /** Fields the twin's history changes without the items (the agenda's version counts the twin's fewer edits). */
        private val VOLATILE = setOf("version")
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val TIME_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z?")
        private val DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")
        private val MEETING_NUMBER = Regex("\\b\\d+/\\d{4}\\b")
    }
}
