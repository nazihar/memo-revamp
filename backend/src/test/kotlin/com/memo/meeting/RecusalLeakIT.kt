package com.memo.meeting

import com.memo.audit.AuditExport
import com.memo.audit.AuditFilter
import com.memo.common.authz.Permission
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.meeting.PaperSurfaceRegistry.Channel
import com.memo.meeting.PaperSurfaceRegistry.Surface
import com.memo.meeting.internal.RulingInput
import com.memo.minutes.DecisionPlanting
import com.memo.minutes.internal.DecisionReads
import com.memo.minutes.internal.DecisionStore
import com.memo.minutes.internal.Dissent
import com.memo.minutes.internal.NoteStore
import com.memo.minutes.internal.ResolutionNumbers
import com.memo.testsupport.AuthzMatrix
import com.memo.testsupport.Person
import com.memo.testsupport.RecusalRow
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * The recusal leak suite (test plan E04, T5 `RecusalLeakIT`; ADR-0062, design E04 §1.6–§1.7). Canary strings are planted
 * ([RecusalLeakFixture.Planted]) in two items of a published agenda — one with its title shown, one with it hidden — in
 * what a person recused from them must never be told: the hidden title, the description, the resolution sought, the papers'
 * titles, file names, content and extracted text, the natures of other people's declarations, a request to discuss and (in
 * [RecusalMailIT]) a late paper. Four viewers are probed: a member recused (leaving the room), a member awaiting the chair's
 * ruling, a recused secretary and a recused chair. A second secretary, the vice-chair, a member without a declaration and,
 * at the end, the member the chair lets take part are the controls that do read the canaries.
 *
 * Three assertions per surface and viewer:
 * 1. **Canary:** no canary in any response body or header, in-app notification, export (the register's workbook, the
 *    calendar file, the audit export) or search result; email is [RecusalMailIT].
 * 2. **Differential:** a viewer's view equals their view of a twin meeting whose two items carry no papers and no text,
 *    apart from the items' own lines: counts, change totals and notifications cannot tell the content was there.
 * 3. **Status:** item-specific reads answer 404 and an officer's item changes 403 `RECUSED`: the recusal columns of the
 *    authorisation matrix (`recusal:` in `p4-e03-*.yaml` and `p4-e04-declarations.yaml`), never 200 with partial data.
 *
 * The meta-test fails the build for a controller method of [PaperSurfaceRegistry.modules] that is not registered, a
 * surface or channel without its probe here, an item or paper endpoint without recusal columns, an officer endpoint without
 * columns or an officer probe, and a placeholder of a later epic ([PaperSurfaceRegistry.pending]) whose endpoints exist.
 */
class RecusalLeakIT : RecusalActionsFixture() {
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    lateinit var mapping: RequestMappingHandlerMapping

    @Autowired lateinit var auditExport: AuditExport

    @Autowired lateinit var decisionStore: DecisionStore

    @Autowired lateinit var noteStore: NoteStore

    @Autowired lateinit var decisionReads: DecisionReads

    @Autowired lateinit var numbers: ResolutionNumbers

    // 1. Canaries and status, surface by surface

    @Test
    fun `no surface or channel answers a recused or awaiting member, a recused secretary or a recused chair any canary`() {
        val p = Planted(content = true)
        controlsReadTheCanaries(p)
        for (viewer in Viewer.entries) {
            for ((surface, probe) in surfaceProbes) {
                runCatching { probe(p, viewer) }.onFailure { throw AssertionError("surface $surface leaks to $viewer", it) }
            }
            for ((channel, probe) in channelProbes) {
                runCatching { probe(p, viewer) }.onFailure { throw AssertionError("channel $channel leaks to $viewer", it) }
            }
        }
        // A ruling to take part lifts the rule at once: the member who waited reads the item in full (the last control).
        val waited = checkNotNull(p.declared[d2])
        asUser(vice.account) { rulings.rule(p.meeting.id, waited.id, waited.version, RulingInput(RulingOutcome.TAKE_PART)) }
        val taking = agenda(d2, p.meeting).lines().getValue(p.item)
        assertThat(taking["access"].asString() to taking["recused"].asBoolean()).isEqualTo("FULL" to false)
        assertThat(taking.toString()).contains(CANARY_DESCRIPTION, CANARY_RESOLUTION, CANARY_PAPER)
        assertThat(open(d2, p.meeting, p.papers[0])).hasStatusOk()
    }

    /** The canaries are really there: the other secretary, the vice-chair and the member without a declaration read them. */
    private fun controlsReadTheCanaries(p: Planted) {
        val ofControl = agendaOf(control, p.meeting).response.contentAsString
        assertThat(ofControl).contains(CANARY_TITLE, CANARY_DESCRIPTION, CANARY_RESOLUTION, CANARY_PAPER)
        val content = open(control, p.meeting, p.papers[0]).response.contentAsByteArray
        assertThat(content.toString(Charsets.ISO_8859_1)).contains(CANARY_CONTENT)
        val register = read("/api/v1/meetings/${p.meeting.id}/declarations", secretary2.session)
        assertThat(register).hasStatusOk()
        assertThat(register.response.contentAsString).contains(*declarants().map { natureOf(it) }.toTypedArray())
        assertThat(read("/api/v1/files/${p.files[0]}", secretary2.session).response.contentAsString).contains(CANARY_FILE)
        assertThat(extractedText(p.files[0])).contains(CANARY_FILE)
        assertThat(notified(vice, "MEETING_RULING_NEEDED")).isEqualTo(2)
        assertThat(notified(secretary2, "MEETING_DISCUSSION_REQUESTED")).isEqualTo(1)
    }

    /** One probe per [Surface] of the registry, for a recused [Viewer]. */
    private val surfaceProbes: Map<Surface, (Planted, Viewer) -> Unit> =
        mapOf(
            Surface.MEMBERS_AGENDA to { p, v ->
                listOf(null, 1, 2).forEach { revision ->
                    val agenda = agendaOf(v.person(), p.meeting, revision)
                    clean(v.person(), agenda)
                    recusedLines(v.person(), agenda.json(), p)
                }
            },
            Surface.WHAT_CHANGED to { p, v ->
                val changed = agenda(v.person(), p.meeting)["whatChanged"]
                val ids =
                    changed
                        ?.get("items")
                        ?.list()
                        .orEmpty()
                        .map { UUID.fromString(it["id"].asString()) }
                assertThat(ids).describedAs("changes to the recused items").doesNotContainAnyElementsOf(p.both)
            },
            Surface.REVISION_LIST to
                { p, v -> clean(v.person(), read("/api/v1/meetings/${p.meeting.id}/agenda/revisions", v.person().session)) },
            Surface.PREVIEW to { p, v ->
                // The other secretary previews the viewer (a secretary has no view of their own to preview); the recused
                // secretary previews the control, and sees the items as their own recused lines.
                val (officer, target) = if (v == Viewer.SECRETARY) secretary to control else secretary2 to v.person()
                val preview = read("/api/v1/meetings/${p.meeting.id}/agenda/preview?as=${target.id}", officer.session)
                clean(v.person(), preview)
                recusedLines(v.person(), preview.json(), p)
            },
            Surface.PAPER_CONTENT to { p, v ->
                p.papers.forEach { paper ->
                    listOf("", "?revision=1", "?revision=2", "?kind=ORIGINAL").forEach { query ->
                        val opened = open(v.person(), p.meeting, paper, query)
                        assertThat(opened).hasStatus(HttpStatus.NOT_FOUND)
                        clean(v.person(), opened, statusBelow = 500)
                    }
                }
            },
            Surface.FILES_ENDPOINT to { p, v -> p.files.forEach { file -> filesEndpoints(file, v.person()) } },
            Surface.NOTIFICATIONS to { _, v ->
                // Revisions 1 and 2 were sent while planting: the viewer's items name no canary (and the differential
                // proves they count nothing of the items).
                inApp(v.person()).forEach { text -> canaries(v.person()).forEach { assertThat(text).doesNotContain(it) } }
            },
            Surface.MY_MEETINGS to { _, v -> clean(v.person(), read("/api/v1/me/meetings", v.person().session)) },
            Surface.CONSENT_REQUESTS to { p, v ->
                val uri = "/api/v1/meetings/${p.meeting.id}/agenda/items/${p.item}/discussion-requests"
                listOf(
                    send(HttpMethod.POST, uri, v.person()),
                    send(HttpMethod.POST, "$uri/mine/withdraw", v.person(), headers = emptyMap()),
                ).forEach { result ->
                    assertThat(result.response.status).isIn(403, 404)
                    clean(v.person(), result, statusBelow = 500)
                }
            },
            Surface.MEETING to { p, v ->
                clean(v.person(), read("/api/v1/meetings/${p.meeting.id}", v.person().session))
                clean(v.person(), read("/api/v1/forums/$brc/meetings", v.person().session))
            },
            // The replies and "Add to my calendar" (E05 T2, T3): never an item (the invitation emails are RecusalMailIT's).
            Surface.INVITATIONS to { p, v ->
                listOf("rsvp/mine", "rsvps", "calendar.ics").forEach { path ->
                    clean(v.person(), read("/api/v1/meetings/${p.meeting.id}/$path", v.person().session), statusBelow = 500)
                }
            },
            // Attendance, the quorum per meeting and per item, the forecast and snapshots (E05 T4, T5): people, states and
            // numbers, never an item's content; an officer recused from the item gets no seats and no `declared` count.
            Surface.QUORUM to { p, v ->
                val base = "/api/v1/meetings/${p.meeting.id}"
                listOf("attendance", "quorum", "quorum?itemId=${p.item}", "quorum/forecast", "quorum/snapshots/${UUID.randomUUID()}")
                    .forEach { path -> clean(v.person(), read("$base/$path", v.person().session), statusBelow = 500) }
                if (v == Viewer.SECRETARY || v == Viewer.CHAIR) {
                    val item = read("$base/quorum?itemId=${p.item}", v.person().session)
                    assertThat(item).hasStatusOk()
                    assertThat(item.json().absent("seats")).describedAs("no seats for an item the officer is recused from").isTrue()
                    val forecast = read("$base/quorum/forecast", v.person().session).json()["items"].list()
                    forecast.filter { UUID.fromString(it["itemId"].asString()) in p.both }.forEach {
                        assertThat(it.absent("declared") to it["viewerRecused"].asBoolean()).isEqualTo(true to true)
                    }
                }
            },
            Surface.DECLARATIONS to { p, v -> declarationsProbe(p, v.person()) },
            Surface.REGISTERS to { p, v -> registersProbe(p, v) },
            Surface.LATE_REQUESTS to { p, v ->
                // The decider's late requests: never the item a viewer is recused from (the recused chair decides nothing
                // there; the vice-chair does). The decision is the matrix's recusal columns.
                val result = read("/api/v1/meetings/${p.meeting.id}/agenda/late-requests", v.person().session)
                clean(v.person(), result)
                assertThat(result.json()["requests"].list().map { it["itemId"].asString() }).doesNotContain(p.item.toString())
            },
            Surface.DECISIONS to { p, v -> decisionsProbe(p, v.person()) },
            Surface.ACTIONS to { p, v -> actionsProbe(p, v) },
            // E07 T8: the committee's tracker, its totals and its export (the workbook's cells checked for canaries).
            Surface.TRACKER to { p, v -> trackerProbe(p, v) },
            // E06 T4: the register and its check (officers; members 403), and one resolution; the planted ones are numbered in
            // the decided item's test.
            Surface.RESOLUTIONS to { _, v -> resolutionReads(brc, v.person()).forEach { clean(v.person(), it, statusBelow = 500) } },
            // E08 T2: the register is every viewer's (each is in office); nothing of the planted items can be found or counted
            // by its words. The items decided, with canaries in the decisions' text and conditions, are RegisterLeakIT.
            Surface.REGISTER to { _, v -> registerProbe(v.person()) },
            // E06 T5: the minutes page, the secretaries' while drafting; nothing on a meeting not held (404), never a canary.
            Surface.MINUTES to { p, v ->
                val calls = minutesCalls(p, v.person())
                assertThat(calls.first()).describedAs("the page: nothing on a meeting not held").hasStatus(HttpStatus.NOT_FOUND)
                calls.forEach { clean(v.person(), it, statusBelow = 500) }
            },
            // E06 T6: the reader's copy and the comments: the copy of a recused reader is MinutesRedactionIT's; here nothing.
            Surface.MINUTES_COPIES to { p, v -> minutesCopyCalls(p, v.person()).forEach { clean(v.person(), it, statusBelow = 500) } },
        )

    /** The generic files endpoints for every version of a paper's [file]: 403 or 404, naming nothing. */
    private fun filesEndpoints(
        file: UUID,
        person: Person,
    ) {
        val versions = read("/api/v1/files/$file", secretary2.session).json()["versions"].list().map { it["versionId"].asString() }
        val uris =
            listOf("/api/v1/files/$file", "/api/v1/files/$file/content") +
                versions.flatMap {
                    listOf(
                        "/api/v1/files/$file/versions/$it/content",
                        "/api/v1/files/$file/versions/$it/renditions",
                        "/api/v1/files/$file/versions/$it/renditions/pdf",
                    )
                }
        uris.forEach { uri ->
            val result = read(uri, person.session)
            assertThat(result.response.status).describedAs(uri).isIn(403, 404)
            clean(person, result, statusBelow = 500)
        }
    }

    /** The declarant's own view and every change of someone else's declaration on the item. */
    private fun declarationsProbe(
        p: Planted,
        person: Person,
    ) {
        val base = "/api/v1/meetings/${p.meeting.id}"
        val mine = read("$base/declarations/mine", person.session)
        clean(person, mine)
        assertThat(mine.response.contentAsString).contains(natureOf(person))
        p.both.forEach { item ->
            val again = send(HttpMethod.POST, "$base/items/$item/declarations", person, """{"nature":"Again"}""")
            // ONE_OPEN on the line they see; a member gets 404 on the hidden one (less than the rule allows, never more).
            assertThat(again.response.status).isIn(404, 409)
            clean(person, again, statusBelow = 500)
        }
        listOf("intent" to """{"intent":"LEAVE"}""", "ruling" to """{"outcome":"TAKE_PART"}""", "withdraw" to """{"reason":"x"}""")
            .forEach { (verb, body) ->
                val headers = mapOf("If-Match" to "\"${p.observed.version}\"")
                val other = send(HttpMethod.POST, "$base/declarations/${p.observed.id}/$verb", person, body, headers)
                assertThat(other.response.status).describedAs("$verb on another's declaration").isIn(403, 404)
                clean(person, other, statusBelow = 500)
            }
    }

    /** The meeting register (an officer's own rows on the items), the register across committees and its export. */
    private fun registersProbe(
        p: Planted,
        v: Viewer,
    ) {
        val register = read("/api/v1/meetings/${p.meeting.id}/declarations", v.person().session)
        val across = read("/api/v1/declarations", v.person().session)
        val export = read("/api/v1/declarations/export?forumId=$brc", v.person().session)
        if (!v.officer) {
            listOf(register, across, export).forEach { assertThat(it).hasStatus(HttpStatus.FORBIDDEN) }
            return
        }
        clean(v.person(), register)
        val rows =
            register
                .json()["items"]
                .list()
                .filter { UUID.fromString(it["itemId"].asString()) in p.both }
                .flatMap { it["declarations"].list() }
        assertThat(rows.map { it["user"]["id"].asString() }.toSet()).containsExactly(v.person().id.toString())
        clean(v.person(), across)
        assertThat(export).hasStatusOk()
        val cells = workbookText(export)
        canaries(v.person()).forEach { assertThat(cells).doesNotContain(it) }
        assertThat(cells).contains(natureOf(v.person()))
    }

    /**
     * E06 T3's surface, in the shared scenario: the decisions list answers no canary and names no decision of the hidden
     * item, and the items' history and notes are 404. The planted decisions have a test of their
     * own (a decided item freezes its declarations, which the other probes and the last ruling of the scenario change).
     */
    private fun decisionsProbe(
        p: Planted,
        person: Person,
        planted: Boolean = false,
    ) {
        val base = "/api/v1/meetings/${p.meeting.id}"
        val list = read("$base/decisions", person.session)
        clean(person, list)
        assertThat(list.response.contentAsString).doesNotContain(CANARY_DISSENT, CANARY_NOTE)
        val decisions = list.json()["decisions"].list().associateBy { UUID.fromString(it["itemId"].asString()) }
        assertThat(decisions.keys).describedAs("the hidden item's decision").doesNotContain(p.hidden)
        if (planted) {
            val mine = decisions.getValue(p.item)
            assertThat(
                mine["viewerRecused"].asBoolean() to mine["text"].asString(),
            ).isEqualTo(true to "It was resolved that the term loan be approved.")
            listOf("vote", "dissent", "abstentions", "tookNoPart", "snapshotId", "recordedBy").forEach {
                assertThat(mine.absent(it)).describedAs("$it of a recused view").isTrue()
            }
        }
        p.both.forEach { item ->
            listOf("$base/items/$item/decision/entries", "$base/items/$item/notes").forEach { uri ->
                val answer = read(uri, person.session)
                assertThat(answer).hasStatus(HttpStatus.NOT_FOUND)
                assertThat(answer.response.contentAsString).doesNotContain(CANARY_DISSENT, CANARY_NOTE, CANARY_TITLE)
            }
        }
    }

    /**
     * E06 T4: the planted item's [resolution] is read by every recused viewer as their recused view (number, outcome, text,
     * no vote or names), the hidden item's [hidden] not at all (404); in the register both are "restricted" rows for the
     * recused officers (members are not given the register).
     */
    private fun resolutionsProbe(
        p: Planted,
        v: Viewer,
        resolution: UUID,
        hidden: UUID,
    ) {
        val one = read("/api/v1/resolutions/$resolution", v.person().session)
        clean(v.person(), one)
        assertThat(one.response.contentAsString).doesNotContain(CANARY_DISSENT, CANARY_NOTE)
        assertThat(one.json()["viewerRecused"].asBoolean() to one.json()["full"].asBoolean()).isEqualTo(true to false)
        assertThat(one.json()["text"].asString()).isEqualTo("It was resolved that the term loan be approved.")
        val unseen = read("/api/v1/resolutions/$hidden", v.person().session)
        assertThat(unseen).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(unseen.response.contentAsString).doesNotContain(CANARY_TITLE)
        val register = read("/api/v1/forums/$brc/resolutions", v.person().session)
        if (!v.officer) {
            assertThat(register).hasStatus(HttpStatus.FORBIDDEN)
            return
        }
        clean(v.person(), register)
        assertThat(register.response.contentAsString).doesNotContain(CANARY_TITLE, CANARY_DISSENT)
        val rows = register.json()["entries"].list()
        assertThat(rows.map { it["id"].asString() }).contains(resolution.toString(), hidden.toString())
        assertThat(
            rows.filter { it["id"].asString() in setOf(resolution.toString(), hidden.toString()) }.map { it["restricted"].asBoolean() },
        ).containsOnly(true)
        assertThat(p.meeting.id.toString()).isIn(rows.map { it["meetingId"].asString() })
    }

    /**
     * E06 T3: the planted item's decision (a vote, a named dissent whose reason is a canary, the vice-chair abstaining) and
     * the hidden item's (its text a canary), with notes on the item. Each recused viewer reads the item's decision as their
     * recused view (outcome, text, conditions; no vote, names, snapshot or recorder), nothing of the hidden item's, and
     * neither history nor notes of either (404); the control, who declared nothing, reads the vote and the names.
     */
    @Test
    fun `a recused viewer reads a decided item's recused view only, and nothing of a hidden one's`() {
        val p = Planted(content = true)
        val planting = DecisionPlanting(transactions, decisionStore, noteStore)
        val decided =
            planting.decision(
                tenant.id,
                p.meeting.id,
                brc,
                p.item,
                secretary2.id,
                "It was resolved that the term loan be approved.",
                dissent = Dissent(d3.id, CANARY_DISSENT),
                abstaining = vice.id,
                tookNoPart = listOf(d1.id, d2.id),
            )
        val hiddenDecision = planting.decision(tenant.id, p.meeting.id, brc, p.hidden, secretary2.id, "$CANARY_TITLE approved")
        // E06 T4: both numbered, as the meeting's end numbers them.
        val resolution = planting.number(tenant.id, decided, decisionReads, numbers)
        val hiddenResolution = planting.number(tenant.id, hiddenDecision, decisionReads, numbers)
        planting.note(tenant.id, p.meeting.id, p.item, secretary2.id, CANARY_NOTE)
        assertThat(read("/api/v1/meetings/${p.meeting.id}/decisions", control.session).response.contentAsString).contains(CANARY_DISSENT)
        Viewer.entries.forEach { viewer ->
            runCatching { decisionsProbe(p, viewer.person(), planted = true) }
                .onFailure { throw AssertionError("decisions leak to $viewer", it) }
            runCatching { resolutionsProbe(p, viewer, resolution, hiddenResolution) }
                .onFailure { throw AssertionError("resolutions leak to $viewer", it) }
        }
    }

    /** One probe per [Channel], for a recused [Viewer]. */
    private val channelProbes: Map<Channel, (Planted, Viewer) -> Unit> =
        mapOf(
            Channel.IN_APP to { p, v ->
                clean(v.person(), read("/api/v1/me/notifications?limit=100", v.person().session))
                // Nobody told them of anything on the items once they had declared: no declaration of someone else, no
                // ruling asked, no request to discuss.
                assertThat(officerKinds(v.person())).isEqualTo(p.officerKindsAfterDeclaring.getValue(v.person()))
            },
            // Delivered through SMTP and read back from Mailpit: RecusalMailIT.
            Channel.MAIL to { _, _ -> },
            Channel.EXPORTS to { p, v ->
                // The register's workbook is in the REGISTERS probe, the tracker's in TRACKER; the meeting's calendar file:
                clean(v.person(), read("/api/v1/meetings/${p.meeting.id}/calendar.ics", v.person().session), statusBelow = 500)
            },
            Channel.SEARCH to { _, v ->
                listOf(CANARY_FILE, CANARY_PAPER, CANARY_DESCRIPTION, "Delta Textiles").forEach { term ->
                    val found = post("/api/v1/search", v.person().session, """{"q":"$term"}""")
                    assertThat(found).hasStatusOk()
                    assertThat(found.json()["count"].asInt()).describedAs("hits for $term").isZero()
                    clean(v.person(), found)
                    clean(v.person(), post("/api/v1/search/suggest", v.person().session, """{"q":"$term"}"""))
                }
            },
            Channel.AUDIT to { p, v -> audited(p, v.person()) },
            Channel.FORUM_QUEUE to { _, v ->
                val queue = read("/api/v1/forums/$brc/submissions", v.person().session)
                if (v.officer) clean(v.person(), queue) else assertThat(queue.response.status).isIn(403, 404)
                clean(v.person(), read("/api/v1/forums/$brc/submissions/ready", v.person().session), statusBelow = 500)
            },
        )

    /**
     * The audit viewer and the verifiable export (design E04 §5, threat I4): no nature anywhere, and the declaration entries
     * carry ids and hashes, never an item's text.
     */
    private fun audited(
        p: Planted,
        viewer: Person,
    ) {
        val page = read("/api/v1/admin/audit?objectId=${p.meeting.id}&limit=200", auditReader.session)
        assertThat(page).hasStatusOk()
        val declarationEntries = page.json()["items"].list().filter { it["action"].asString().startsWith("meeting.declaration") }
        assertThat(declarationEntries).isNotEmpty().allSatisfy { entry ->
            canaries(viewer).forEach { assertThat(entry.toString()).doesNotContain(it) }
        }
        val natures = declarants().map { natureOf(it) }
        natures.forEach { assertThat(page.response.contentAsString).doesNotContain(it) }
        val zip = ByteArrayOutputStream()
        val hour = Duration.ofHours(1)
        ActorContext.call(ActorScope(Actor.User(admin.id.value), null)) {
            TenantContext.call(
                tenant.id,
            ) { auditExport.write(AuditFilter(Instant.now().minus(hour), Instant.now().plus(hour)), admin.id.value, zip) }
        }
        val exported =
            ZipInputStream(ByteArrayInputStream(zip.toByteArray())).use { entries ->
                generateSequence { entries.nextEntry }.joinToString("\n") { entries.readAllBytes().toString(Charsets.UTF_8) }
            }
        assertThat(exported).contains("meeting.declaration.made")
        natures.forEach { assertThat(exported).doesNotContain(it) }
    }

    private val auditReaders = mutableMapOf<String, Person>()

    /** A person of the current tenant with `audit.read` (one per tenant). */
    private val auditReader: Person
        get() =
            auditReaders.getOrPut(tenant.id.toString()) {
                person().also { reader -> asUser(admin) { access.grantCustom(reader.id, "Auditors", setOf(Permission.AUDIT_READ)) } }
            }

    private fun workbookText(result: MvcTestResult): String =
        XSSFWorkbook(ByteArrayInputStream(result.response.contentAsByteArray)).use { workbook ->
            workbook
                .getSheetAt(0)
                .flatMap { row -> row.map { it.stringCellValue } }
                .joinToString(" | ")
        }

    // 2. The differential

    @Test
    fun `a recused view equals the same person's view of a twin meeting whose items carry no papers and no text`() {
        val planted = Planted(content = true)
        val twin = Planted(content = false, days = DAYS_AHEAD + 1)
        val drop = (planted.both + twin.both).map { it.toString() }.toSet()
        // The comparison sees the items' content when it is there: the control, who declared nothing, was told of their
        // changes in revision 2, so their views of the two meetings differ.
        assertThat(normal(agenda(control, planted.meeting), drop)).isNotEqualTo(normal(agenda(control, twin.meeting), drop))
        for (viewer in Viewer.entries) {
            val person = viewer.person()
            val same = { what: String, of: (Planted) -> JsonNode ->
                assertThat(normal(of(planted), drop)).describedAs("$what of $viewer").isEqualTo(normal(of(twin), drop))
            }
            listOf(null, 1, 2).forEach { revision -> same("agenda revision $revision") { agenda(person, it.meeting, revision) } }
            same("revisions") { read("/api/v1/meetings/${it.meeting.id}/agenda/revisions", person.session).json() }
            same("my meetings") { p ->
                read("/api/v1/me/meetings", person.session).json().list().single { it["id"].asString() == p.meeting.id.toString() }
            }
            same("items to review") { p ->
                val mine = read("/api/v1/meetings/${p.meeting.id}/declarations/mine", person.session).json()
                JsonNodeFactory.instance.objectNode().also { it.set("toReview", mine["toReview"]) }
            }
            same("notifications") { p -> notificationsOf(person, p.meeting) }
            if (viewer.officer) {
                same("draft") { draftOf(person, it.meeting).json() }
                same("publish preview") { read("/api/v1/meetings/${it.meeting.id}/agenda/publish-preview", person.session).json() }
            }
            if (viewer == Viewer.SECRETARY) {
                same("preview of the control") {
                    read("/api/v1/meetings/${it.meeting.id}/agenda/preview?as=${control.id}", person.session).json()
                }
            }
        }
    }

    // 3. The recusal columns of the matrix, and the officers' other endpoints

    @Test
    fun `every recusal column of the authorisation matrix holds, with no canary in any answer`() {
        val p = Planted(content = true)
        val failures =
            AuthzMatrix.recusal.flatMap { row ->
                Viewer.entries.mapNotNull { viewer ->
                    val result = call(p, row, viewer)
                    val status = result.response.status
                    val leak = runCatching { clean(viewer.person(), result, statusBelow = 500) }.exceptionOrNull()
                    val expected = row.expected.getValue(viewer.column)
                    when {
                        leak != null -> "${row.endpoint} as ${viewer.column}: $leak"
                        status != expected -> "${row.endpoint} as ${viewer.column}: $status, expected $expected"
                        else -> null
                    }
                }
            }
        assertThat(failures).describedAs("recusal cells").isEmpty()
        // Then every officer endpoint without columns (no item in its path): by the recused chair for the chair's part, by
        // the recused secretary for the rest. Last, as some change the meeting.
        for ((endpoint, probe) in officerProbes) {
            val result = probe(p)
            val actor = if (endpoint in BY_THE_CHAIR) chair else secretary
            runCatching { clean(actor, result, statusBelow = 500) }
                .onFailure { throw AssertionError("$endpoint leaks to a recused officer", it) }
        }
    }

    /** [row]'s request for [viewer], on [p]'s recused item, its paper and another person's declaration on it. */
    private fun call(
        p: Planted,
        row: RecusalRow,
        viewer: Viewer,
    ): MvcTestResult {
        val path =
            row.path
                .replace("{meetingId}", p.meeting.id.toString())
                .replace("{itemId}", p.item.toString())
                .replace("{paperId}", p.papers[0].toString())
                .replace("{sectionId}", p.section.toString())
                .replace("{declarationId}", p.observed.id.toString())
                .replace("{forumId}", brc.toString())
                .let { if ("{actionId}" in it) it.replace("{actionId}", actionsOf(p).draft.toString()) else it }
                .replace("{requestId}", UUID.randomUUID().toString())
        val query = row.query?.let { "?" + substituted(p, it) }.orEmpty()
        if (row.method == "GET") return read(path + query, viewer.person().session)
        val body = row.body?.let { substituted(p, json.writeValueAsString(it)) } ?: "{}"
        val ifMatch =
            when {
                // Every cell is refused, so the declaration keeps its version.
                "{declarationId}" in row.path -> p.observed.version

                "/agenda" in row.path || "/items/" in row.path || "/papers/" in row.path -> version(p.meeting)

                else -> meetingVersion(p)
            }
        val headers = mapOf("If-Match" to "\"$ifMatch\"", "Idempotency-Key" to UUID.randomUUID().toString())
        return send(HttpMethod.valueOf(row.method), path + query, viewer.person(), body, headers)
    }

    private fun substituted(
        p: Planted,
        text: String,
    ): String {
        var out = text.replace("{item}", p.item.toString()).replace("{control}", control.id.toString())
        if ("{file}" in out) out = out.replace("{file}", upload(p.meeting, "extra.pdf").toString())
        return out.replace("{random}", UUID.randomUUID().toString())
    }

    private fun meetingVersion(p: Planted): Long = asUser(secretary2.account) { reads.get(p.meeting.id) }.version

    internal fun versioned(version: Long) = mapOf("If-Match" to "\"$version\"", "Idempotency-Key" to UUID.randomUUID().toString())

    /**
     * The officers' endpoints that name no item (sections, publishing, the chair's part, running the meeting, invitations,
     * replies), called by the recused secretary or chair: answered without a canary. Item and paper endpoints have recusal
     * columns instead.
     */
    private val officerProbes: Map<String, (Planted) -> MvcTestResult> by lazy {
        val base = { p: Planted -> "/api/v1/meetings/${p.meeting.id}" }
        val day = { days: Long -> LocalDate.now().plusDays(DAYS_AHEAD + days) }
        linkedMapOf(
            "POST /api/v1/meetings/{meetingId}/agenda" to
                { p -> send(HttpMethod.POST, "${base(p)}/agenda", secretary, """{"from":"TEMPLATE"}""") },
            "POST /api/v1/meetings/{meetingId}/agenda/sections" to { p ->
                send(
                    HttpMethod.POST,
                    "${base(p)}/agenda/sections",
                    secretary,
                    """{"title":"Other business"}""",
                    versioned(version(p.meeting)),
                )
            },
            "PATCH /api/v1/meetings/{meetingId}/agenda/sections/{sectionId}" to { p ->
                val uri = "${base(p)}/agenda/sections/${p.section}"
                send(HttpMethod.PATCH, uri, secretary, """{"title":"Consent items"}""", versioned(version(p.meeting)))
            },
            "POST /api/v1/meetings/{meetingId}/agenda/sections/{sectionId}/remove" to { p ->
                send(HttpMethod.POST, "${base(p)}/agenda/sections/${p.section}/remove", secretary, "{}", versioned(version(p.meeting)))
            },
            "POST /api/v1/meetings/{meetingId}/agenda/chair-agreement" to { p ->
                send(HttpMethod.POST, "${base(p)}/agenda/chair-agreement", chair, """{"onBehalf":false}""", versioned(version(p.meeting)))
            },
            "POST /api/v1/meetings/{meetingId}/agenda/chair-comments" to
                { p -> send(HttpMethod.POST, "${base(p)}/agenda/chair-comments", chair, """{"text":"Please add the capital plan"}""") },
            "POST /api/v1/meetings/{meetingId}/agenda/publish" to { p ->
                val body = """{"notify":true,"note":"Three"}"""
                send(HttpMethod.POST, "${base(p)}/agenda/publish", secretary, body, versioned(version(p.meeting)))
            },
            "GET /api/v1/meetings/{meetingId}/invitations" to { p -> read("${base(p)}/invitations", secretary.session) },
            "POST /api/v1/meetings/{meetingId}/invitations/resend" to
                { p -> send(HttpMethod.POST, "${base(p)}/invitations/resend", secretary, """{"userIds":["${d1.id}"]}""") },
            "GET /api/v1/meetings/{meetingId}/rsvps" to { p -> read("${base(p)}/rsvps", chair.session) },
            "PUT /api/v1/meetings/{meetingId}/rsvps/{userId}" to
                { p -> send(HttpMethod.PUT, "${base(p)}/rsvps/${d1.id}", secretary, """{"answer":"APOLOGIES","how":"PHONE"}""") },
            "POST /api/v1/forums/{forumId}/meetings" to { _ ->
                val body =
                    """{"startsAt":"${day(5)}T10:00:00","endsAt":"${day(5)}T12:00:00","timeZone":"Asia/Dhaka",""" +
                        """"mode":"IN_PERSON","location":"Annex"}"""
                send(HttpMethod.POST, "/api/v1/forums/$brc/meetings", secretary, body)
            },
            "PATCH /api/v1/meetings/{meetingId}" to { p ->
                send(
                    HttpMethod.PATCH,
                    base(p),
                    secretary,
                    """{"mode":"IN_PERSON","location":"Board room 2"}""",
                    versioned(meetingVersion(p)),
                )
            },
            "POST /api/v1/meetings/{meetingId}/guests" to
                { p -> send(HttpMethod.POST, "${base(p)}/guests", secretary, """{"userId":"${outsider.id}"}""") },
            "POST /api/v1/meetings/{meetingId}/guests/{userId}/remove" to
                { p -> send(HttpMethod.POST, "${base(p)}/guests/${outsider.id}/remove", secretary, "{}") },
            "POST /api/v1/meetings/{meetingId}/reschedule" to { p ->
                val body = """{"startsAt":"${day(2)}T10:00:00","endsAt":"${day(2)}T13:00:00","reason":"Room"}"""
                send(HttpMethod.POST, "${base(p)}/reschedule", secretary, body, versioned(meetingVersion(p)))
            },
            "POST /api/v1/meetings/{meetingId}/schedule" to
                { p -> send(HttpMethod.POST, "${base(p)}/schedule", secretary, "{}", versioned(meetingVersion(p))) },
            "POST /api/v1/meetings/{meetingId}/start" to
                { p -> send(HttpMethod.POST, "${base(p)}/start", secretary, "{}", versioned(meetingVersion(p))) },
            // Attendance and the item in progress (E05 T4), while the meeting runs: answered with the register and the quorum.
            "POST /api/v1/meetings/{meetingId}/attendance/arrivals" to { p ->
                send(
                    HttpMethod.POST,
                    "${base(p)}/attendance/arrivals",
                    secretary,
                    """{"userIds":["${d1.id}","${d2.id}"],"mode":"IN_PERSON"}""",
                )
            },
            "POST /api/v1/meetings/{meetingId}/attendance/events" to { p ->
                val body = """{"userId":"${d1.id}","kind":"STEPPED_OUT","itemId":"${p.item}"}"""
                send(HttpMethod.POST, "${base(p)}/attendance/events", secretary, body)
            },
            "POST /api/v1/meetings/{meetingId}/attendance/events/{eventId}/void" to
                { p ->
                    send(HttpMethod.POST, "${base(p)}/attendance/events/${UUID.randomUUID()}/void", secretary, """{"reason":"Wrong"}""")
                },
            "POST /api/v1/meetings/{meetingId}/progress" to
                { p -> send(HttpMethod.POST, "${base(p)}/progress", secretary, """{"itemId":"${p.item}"}""") },
            "GET /api/v1/meetings/{meetingId}/quorum/changes" to { p -> read("${base(p)}/quorum/changes", secretary.session) },
            "GET /api/v1/meetings/{meetingId}/quorum/forecast" to { p -> read("${base(p)}/quorum/forecast", secretary.session) },
            // The committee's decisions to be ratified (E06 T3): ids, numbers and outcomes only.
            "GET /api/v1/forums/{forumId}/decisions/to-be-ratified" to
                { _ -> read("/api/v1/forums/$brc/decisions/to-be-ratified", secretary.session) },
            *actionOfficerProbes(),
            // The starting resolution number (E06 T4): forums.manage only, so the recused secretary is refused, naming nothing.
            "PUT /api/v1/forums/{forumId}/resolution-counters/{period}" to
                { _ -> send(HttpMethod.PUT, "/api/v1/forums/$brc/resolution-counters/2026", secretary, """{"next":14}""") },
            // Making the minutes' draft (E06 T5): the recused secretary is refused (403 `RECUSED`), naming nothing.
            "POST /api/v1/meetings/{meetingId}/minutes/draft" to
                { p -> send(HttpMethod.POST, "${base(p)}/minutes/draft", secretary, """{"replaceEdits":true}""") },
            // The minutes' circulation and the preview "as" (E06 T6): the recused secretary is refused or finds nothing.
            *minutesOfficerProbes().toList().toTypedArray(),
            "POST /api/v1/meetings/{meetingId}/end" to
                { p -> send(HttpMethod.POST, "${base(p)}/end", secretary, "{}", versioned(meetingVersion(p))) },
            "POST /api/v1/meetings/{meetingId}/cancel" to
                { p -> send(HttpMethod.POST, "${base(p)}/cancel", secretary, """{"reason":"Too late"}""", versioned(meetingVersion(p))) },
        )
    }

    // The meta-test

    @Test
    fun `every controller of the meeting, forum and later modules is registered with a recusal probe, and placeholders wait`() {
        val all = handlerEndpoints(mapping)
        val ours = all.filter { (_, _, pkg) -> PaperSurfaceRegistry.modules.any { pkg.startsWith(it) } }.map { it.first }.toSet()
        val registered = PaperSurfaceRegistry.registered
        assertThat(ours - registered).describedAs("controller methods without a recusal probe (PaperSurfaceRegistry)").isEmpty()
        assertThat(registered - ours).describedAs("registry entries without an endpoint").isEmpty()
        assertThat(
            surfaceProbes.keys,
        ).describedAs("surfaces without a recusal probe").containsExactlyInAnyOrder(*Surface.entries.toTypedArray())
        assertThat(
            channelProbes.keys,
        ).describedAs("channels without a recusal probe").containsExactlyInAnyOrder(*Channel.entries.toTypedArray())

        // Every endpoint naming an item, a paper or a declaration, and every read of the agenda, has recusal columns; every
        // officer endpoint without them has an officer probe.
        val columns = AuthzMatrix.recusal.map { it.endpoint }.toSet()
        val itemScoped =
            ours.filter { endpoint ->
                endpoint.startsWith("GET /api/v1/meetings/{meetingId}/agenda") ||
                    listOf("{itemId}", "{paperId}", "{declarationId}").any { it in endpoint } ||
                    endpoint.endsWith("/agenda/moves") ||
                    endpoint.endsWith("/agenda/items")
            }
        assertThat(itemScoped - columns).describedAs("item and paper endpoints without recusal columns in the matrix").isEmpty()
        assertThat(columns - ours).describedAs("recusal columns without an endpoint").isEmpty()
        val officers = PaperSurfaceRegistry.officersOnly.keys - columns
        assertThat(officers - officerProbes.keys).describedAs("officer endpoints without columns or an officer probe").isEmpty()

        // The surfaces of later epics: a placeholder whose endpoints exist must become a probe.
        PaperSurfaceRegistry.pending.forEach { pending ->
            val built = all.map { it.second }.filter { path -> pending.paths.any { path.startsWith(it) } }
            assertThat(built).describedAs("${pending.epic} built \"${pending.what}\": replace its placeholder with a probe").isEmpty()
        }
    }

    private companion object {
        /** E06 T3: a dissent's reason and the secretary's notes on the planted item, which no recused viewer may read. */
        const val CANARY_DISSENT = "CANARY-DS dissent reason"
        const val CANARY_NOTE = "CANARY-NT secretary notes"

        /** The officer probes the recused chair makes; the recused secretary makes the others. */
        val BY_THE_CHAIR =
            setOf(
                "POST /api/v1/meetings/{meetingId}/agenda/chair-agreement",
                "POST /api/v1/meetings/{meetingId}/agenda/chair-comments",
                "GET /api/v1/meetings/{meetingId}/rsvps",
            )
    }
}
