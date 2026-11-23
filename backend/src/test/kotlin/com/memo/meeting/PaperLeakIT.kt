package com.memo.meeting

import com.memo.actions.ActionPlanting
import com.memo.actions.internal.ActionMemoStore
import com.memo.actions.internal.ActionStore
import com.memo.actions.internal.ItemCopy
import com.memo.actions.internal.MemoLinkKind
import com.memo.common.context.ActorContext
import com.memo.common.crypto.DataKeys
import com.memo.common.tenant.TenantContext
import com.memo.forum.internal.ForumMemberStore
import com.memo.forum.internal.MemberEdit
import com.memo.meeting.PaperSurfaceRegistry.Surface
import com.memo.meeting.internal.ItemChange
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.invite.InvitationReconciler
import com.memo.meeting.internal.quorum.LiveQuorum
import com.memo.meeting.internal.quorum.SnapshotRow
import com.memo.meeting.internal.quorum.SnapshotStore
import com.memo.minutes.DecisionPlanting
import com.memo.minutes.internal.DecisionReads
import com.memo.minutes.internal.DecisionStore
import com.memo.minutes.internal.Dissent
import com.memo.minutes.internal.NoteStore
import com.memo.minutes.internal.ResolutionNumbers
import com.memo.notification.Mail
import com.memo.search.internal.decision.DecisionIndex
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.MailOutbox
import com.memo.testsupport.Person
import com.memo.testsupport.query
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The leak-suite skeleton (test plan E03, T4; ADR-0061 and its 2026-11-18 amendment): one probe per surface of
 * [PaperSurfaceRegistry] for people outside the rule — a member not named on a restricted item or a hidden one and not
 * cleared for a confidential one, a guest outside a members-only section, and a `forum.read` holder (who reads neither
 * members-only nor confidential titles). Canary strings are planted in what they must not see (hidden, members-only and
 * confidential titles, descriptions, paper titles); no surface answers them, opens such a paper, counts its changes or
 * notifies them of it. E04 T2 adds a recused member (d1, recused from one item with its title shown and one with it
 * hidden, one of them waiting for the chair's ruling) to every probe: they see those items' lines and nothing else of them.
 * E05 T5 adds d1's alternate, standing in for them (d1 sent apologies): they read as a cleared member of d1's seat, are
 * named on nothing, and are recused with d1; and the E05 surfaces (the invitation files and emails, the replies, the
 * register, the quorum per meeting and per item, the forecast and a snapshot whose override reason is a canary). E06 T3
 * adds the decisions: each planted item carries one whose text is its canary, the recused item's with a dissent reason
 * that is a canary for everyone (d1 included) and notes that nobody probed may read. E07 T3 adds the actions: each planted
 * item carries an issued action whose title is the item's canary, which only those who see the item in full read.
 */
class PaperLeakIT : RecusalFixture() {
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    lateinit var mapping: RequestMappingHandlerMapping

    @Autowired lateinit var keys: DataKeys

    @Autowired lateinit var memberStore: ForumMemberStore

    @Autowired lateinit var reconciler: InvitationReconciler

    @Autowired lateinit var live: LiveQuorum

    @Autowired lateinit var snapshotStore: SnapshotStore

    @Autowired lateinit var decisionStore: DecisionStore

    @Autowired lateinit var noteStore: NoteStore

    @Autowired lateinit var actionStore: ActionStore

    @Autowired lateinit var memoLinks: ActionMemoStore

    @Autowired lateinit var meetingItems: MeetingItems

    @Autowired lateinit var decisionReads: DecisionReads

    @Autowired lateinit var numbers: ResolutionNumbers

    @Autowired lateinit var decisionIndex: DecisionIndex

    /** d1's alternate, standing in for them at the planted meeting. */
    lateinit var alt: Person

    @BeforeEach
    fun alternateSeat() {
        alt = person(clearance = 1)
        val seat = transactions.readInTenant(tenant.id) { memberStore.members(brc).single { it.userId == d1.id } }
        asUser(admin) {
            members.edit(brc, seat.id, seat.version, MemberEdit(seat.voting, seat.attributes, alt.id, seat.termEndsOn, null))
        }
    }

    /**
     * A published agenda (revision 2 changing only what the probed people must not see), and who must not see what. d1,
     * named on the restricted items, is recused (E04 T2) from two items only they are named on: one with its title shown,
     * in the consent section, and one with its title hidden.
     */
    private inner class Planted {
        val meeting: MeetingRow = meeting()
        val restricted = addItem(meeting, "Credit facility for Acme")
        val hidden = addItem(meeting, CANARY_HIDDEN)
        val camera = addItem(meeting, CANARY_CAMERA, sectionId = inCamera(meeting))
        val confidential = addItem(meeting, CANARY_CONFIDENTIAL)
        val consent = addItem(meeting, "Settlement with a counterparty", section = "Consent items")
        val recused = addItem(meeting, RECUSED_TITLE, section = "Consent items")
        val recusedHidden = addItem(meeting, CANARY_RECUSED_HIDDEN)
        val restrictedFile = upload(meeting, "acme.pdf")
        val cameraFile = upload(meeting, "succession.pdf")
        val recusedFile = upload(meeting, "delta.pdf")
        val restrictedPaper: UUID
        val hiddenPaper: UUID
        val cameraPaper: UUID
        val confidentialPaper: UUID
        val recusedPaper: UUID
        val recusedHiddenPaper: UUID

        /** A decision's snapshot on the recused item, overridden with a canary for a reason. */
        val snapshot: UUID

        /** E07 T3: an issued action on each planted item, its title the item's canary (owned by the other secretary). */
        val actions = mutableMapOf<UUID, String>()

        /** E07 T7: the committee's next meeting, its agenda published after the actions: its report lists them. */
        val next: MeetingRow by lazy { meeting(days = DAYS_AHEAD + 7).also { publish(it) } }

        /** E06 T4: each planted decision's resolution number, by item. */
        val resolutions = linkedMapOf<UUID, UUID>()

        /** E08 T2: each planted decision, by item (in the register). */
        val decisions = linkedMapOf<UUID, UUID>()

        /** E07 follow-up: the follow-up memo of the restricted and in-camera items' actions, read by its initiator only. */
        val followUpMemo: UUID

        init {
            // d1 sends apologies and their alternate attends: the alternate is in the audience from now on.
            val asked =
                send(
                    HttpMethod.PUT,
                    "/api/v1/meetings/${meeting.id}/rsvp/mine",
                    d1,
                    """{"answer":"APOLOGIES","alternateAttends":true}""",
                )
            assertThat(asked).hasStatusOk()
            change(meeting, restricted, ItemChange("Credit facility for Acme", CANARY_DESCRIPTION, restrictedTo = listOf(d1.id)))
            change(meeting, hidden, ItemChange(CANARY_HIDDEN, restrictedTo = listOf(d1.id), titleHidden = true))
            change(meeting, confidential, ItemChange(CANARY_CONFIDENTIAL, classificationId = levels()[1].id))
            change(meeting, consent, ItemChange("Settlement with a counterparty", restrictedTo = listOf(d1.id)))
            change(meeting, recused, ItemChange(RECUSED_TITLE, CANARY_RECUSED_DESCRIPTION, restrictedTo = listOf(d1.id)))
            change(meeting, recusedHidden, ItemChange(CANARY_RECUSED_HIDDEN, restrictedTo = listOf(d1.id), titleHidden = true))
            restrictedPaper = paper(meeting, restricted, restrictedFile, title = CANARY_PAPER)
            hiddenPaper = paper(meeting, hidden, upload(meeting, "dismissal.pdf"), title = CANARY_PAPER)
            cameraPaper = paper(meeting, camera, cameraFile, title = CANARY_CAMERA)
            confidentialPaper = paper(meeting, confidential, upload(meeting, "stress.pdf"), title = CANARY_CONFIDENTIAL)
            recusedPaper = paper(meeting, recused, recusedFile, title = CANARY_RECUSED_PAPER)
            recusedHiddenPaper = paper(meeting, recusedHidden, upload(meeting, "mill.pdf"), title = CANARY_RECUSED_PAPER)
            publish(meeting)
            probed().forEach { person ->
                assertThat(send(HttpMethod.POST, "/api/v1/meetings/${meeting.id}/seen", person, """{"revision":1}""", emptyMap()))
                    .hasStatus(HttpStatus.NO_CONTENT)
            }
            // d1 declares on both (after reading revision 1): from now on, every revision withholds them.
            declare(d1, meeting, recused)
            declare(d1, meeting, recusedHidden, DeclarationIntent.ASK_CHAIR)
            // Revision 2: the restricted and the recused papers replaced, the hidden, confidential and recused hidden items
            // retitled.
            replace(restrictedFile)
            replace(recusedFile)
            change(meeting, hidden, ItemChange("$CANARY_HIDDEN, revised", restrictedTo = listOf(d1.id), titleHidden = true))
            change(meeting, confidential, ItemChange("$CANARY_CONFIDENTIAL, revised", classificationId = levels()[1].id))
            change(
                meeting,
                recusedHidden,
                ItemChange("$CANARY_RECUSED_HIDDEN, revised", restrictedTo = listOf(d1.id), titleHidden = true),
            )
            publish(meeting, "Revision two")
            // The invitations go out (the reconciler as its job runs it).
            ActorContext.asSystem("meeting-invitations") {
                TenantContext.call(tenant.id) { reconciler.run(meeting.id, Instant.now().plus(Duration.ofMinutes(3))) }
            }
            // Nobody is present 40 days ahead, so a snapshot is set up through the store: not quorate, overridden.
            snapshot = UUID.randomUUID()
            transactions.inTenant(tenant.id) {
                val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
                val inputs = QuorumSnapshotInputs.of(meeting.id, live.evaluate(meeting, now, recused), UUID.randomUUID(), 1, now)
                snapshotStore.insert(
                    SnapshotRow(
                        snapshot,
                        meeting.id,
                        recused,
                        now,
                        inputs,
                        inputs.evaluate(),
                        CANARY_OVERRIDE,
                        inputs.sha256(),
                        secretary.id,
                        now,
                    ),
                )
            }
            // E06 T3: a decision on each planted item, its text the item's canary; on the recused item a named dissent
            // whose reason no viewer may read (d1 sees that decision's recused view), and notes.
            val planting = DecisionPlanting(transactions, decisionStore, noteStore)
            val decided =
                listOf(
                    restricted to CANARY_DESCRIPTION,
                    hidden to CANARY_HIDDEN,
                    camera to CANARY_CAMERA,
                    confidential to CANARY_CONFIDENTIAL,
                ).associate { (item, text) -> item to planting.decision(tenant.id, meeting.id, brc, item, secretary.id, text) }
                    .toMutableMap()
            decided[recused] =
                planting.decision(
                    tenant.id,
                    meeting.id,
                    brc,
                    recused,
                    secretary.id,
                    "Approved as presented.",
                    dissent = Dissent(secretary2.id, CANARY_RECUSED_DESCRIPTION),
                    tookNoPart = listOf(d1.id),
                )
            decided[recusedHidden] = planting.decision(tenant.id, meeting.id, brc, recusedHidden, secretary.id, CANARY_RECUSED_HIDDEN)
            // E06 T4: every planted decision numbered, as the meeting's end numbers them.
            decided.forEach { (item, decision) -> resolutions[item] = planting.number(tenant.id, decision, decisionReads, numbers) }
            // E08 T2: every planted decision in the register, as the indexer writes it in the recording transaction.
            transactions.inTenant(tenant.id) { decided.values.forEach { check(decisionIndex.put(it)) } }
            decisions.putAll(decided)
            planting.note(tenant.id, meeting.id, recused, secretary.id, CANARY_RECUSED_PAPER)
            val agenda = transactions.readInTenant(tenant.id) { meetingItems.agenda(meeting.id) }
            val actionPlanting = ActionPlanting(transactions, actionStore, tenantDsl)
            listOf(
                restricted to CANARY_DESCRIPTION,
                hidden to CANARY_HIDDEN,
                camera to CANARY_CAMERA,
                confidential to CANARY_CONFIDENTIAL,
                recused to CANARY_RECUSED_DESCRIPTION,
                recusedHidden to CANARY_RECUSED_HIDDEN,
            ).forEach { (id, title) ->
                val item = checkNotNull(agenda.item(id))
                val copy = ItemCopy(item.parentId, item.classificationId, item.restrictedTo, item.titleHidden, item.membersOnly)
                val action =
                    actionPlanting.action(tenant.id, brc, meeting.id, id, copy, item.classificationId, secretary.id, title, secretary2.id)
                actions[action] = title
            }
            // E07 follow-up: a numbered follow-up memo that only the other secretary (its initiator) reads, on the actions d1
            // and d3 see on the next meeting's report. The report joins it for those who may read it, never for the others.
            val level = levels().first().id
            followUpMemo =
                UUID.fromString(
                    create(
                        secretary2.session,
                        """{"subject":"$CANARY_MEMO","typeId":"${generalType(secretary2.session)}","classificationId":"$level"}""",
                    ).json()["id"]
                        .asString(),
                )
            transactions.inTenant(tenant.id) {
                tenantDsl.current().execute("update memo set reference = ? where id = ?", CANARY_MEMO_NUMBER, followUpMemo)
                val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
                actions.filterValues { it == CANARY_DESCRIPTION || it == CANARY_CAMERA }.keys.forEach { action ->
                    check(memoLinks.insert(action, followUpMemo, MemoLinkKind.LINKED, secretary2.id, now))
                }
            }
        }

        /**
         * What [person] must never be answered: d3 is a member (members-only), the guest is cleared (confidential); d1 reads
         * all but the items they are recused from (of which the shown title is not a canary).
         */
        fun canaries(person: Person): List<String> =
            if (person == d1) {
                RECUSED_CANARIES + MEMO_CANARIES
            } else {
                listOf(CANARY_HIDDEN, CANARY_DESCRIPTION, CANARY_PAPER) + RECUSED_CANARIES + MEMO_CANARIES +
                    (if (person != d3 && person != alt) listOf(CANARY_CAMERA) else emptyList()) +
                    (if (person != guest && person != alt) listOf(CANARY_CONFIDENTIAL) else emptyList())
            }

        /**
         * The items whose title [person] may not read: d3 reads the members-only one, the guest the confidential one, d1's
         * alternate (a cleared member of d1's seat, named on nothing) both.
         */
        fun untitled(person: Person): List<UUID> =
            if (person == d1) {
                listOf(recusedHidden)
            } else {
                listOf(hidden, recusedHidden) + (if (person != d3 && person != alt) listOf(camera) else emptyList()) +
                    (if (person != guest && person != alt) listOf(confidential) else emptyList())
            }

        /** The papers [person] may not open. */
        fun closed(person: Person): List<UUID> =
            listOf(recusedPaper, recusedHiddenPaper) +
                when (person) {
                    d1 -> emptyList()
                    d3 -> listOf(restrictedPaper, hiddenPaper, confidentialPaper)
                    guest -> listOf(restrictedPaper, hiddenPaper, cameraPaper)
                    alt -> listOf(restrictedPaper, hiddenPaper)
                    else -> listOf(restrictedPaper, hiddenPaper, cameraPaper, confidentialPaper)
                }

        /** The consent items [person] may not ask to discuss: d1 is named on both, and recused from one. */
        fun undiscussable(person: Person): List<UUID> = if (person == d1) listOf(recused) else listOf(consent, recused)

        /**
         * How many lines revision 2 changed for [person]: only the guest is cleared for the retitled confidential item; d1
         * sees three changes, none on the items they are recused from.
         */
        fun changes(person: Person): Int =
            when (person) {
                guest, alt -> 1
                d1 -> 3
                else -> 0
            }

        /**
         * E08 T2: how [person] (who has a register: d1 and d3 hold seats, the auditor forum.read) sees each planted decision
         * in it; absent is not returned. d1 is named on the restricted items and cleared, and recused from two; d3 is a member
         * neither named nor cleared; forum.read reads no restricted, title-hidden or members-only item in full, nor above
         * their clearance.
         */
        fun registerLevels(person: Person): Map<UUID, String> =
            when (person) {
                d1 -> {
                    mapOf(
                        restricted to "FULL",
                        hidden to "FULL",
                        camera to "FULL",
                        confidential to "FULL",
                        recused to "LINE",
                        recusedHidden to "LINE",
                    )
                }

                d3 -> {
                    mapOf(restricted to "LINE", hidden to "LINE", camera to "FULL", recused to "LINE", recusedHidden to "LINE")
                }

                else -> {
                    mapOf(restricted to "LINE", hidden to "LINE", camera to "LINE", recused to "LINE", recusedHidden to "LINE")
                }
            }
    }

    /**
     * The people probed: a member not named or cleared, a guest, a `forum.read` holder, a recused member (E04 T2), and the
     * recused member's alternate standing in for them (E05 T5).
     */
    private fun probed() = listOf(d3, guest, auditor, d1, alt)

    /** One probe per surface, for a person outside the rule. */
    private val probes: Map<Surface, (Planted, Person) -> Unit> =
        mapOf(
            Surface.MEMBERS_AGENDA to { p, person ->
                clean(p, person, agendaOf(person, p.meeting))
                clean(p, person, agendaOf(person, p.meeting, revision = 1))
            },
            Surface.WHAT_CHANGED to { p, person ->
                val changed = agenda(person, p.meeting)["whatChanged"]
                assertThat(changed["count"].asInt()).describedAs("changes counted for a person outside them").isEqualTo(p.changes(person))
            },
            Surface.REVISION_LIST to
                { p, person -> clean(p, person, read("/api/v1/meetings/${p.meeting.id}/agenda/revisions", person.session)) },
            Surface.PREVIEW to { p, person ->
                val preview = read("/api/v1/meetings/${p.meeting.id}/agenda/preview?as=${person.id}", secretary.session)
                // The forum.read holder is not in the audience: there is no view of theirs to preview.
                if (person == auditor) assertThat(preview).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT) else clean(p, person, preview)
            },
            Surface.PAPER_CONTENT to { p, person ->
                p.closed(person).forEach { paper ->
                    assertThat(open(person, p.meeting, paper)).hasStatus(HttpStatus.NOT_FOUND)
                    assertThat(open(person, p.meeting, paper, "?revision=1")).hasStatus(HttpStatus.NOT_FOUND)
                    assertThat(open(person, p.meeting, paper, "?kind=ORIGINAL")).hasStatus(HttpStatus.NOT_FOUND)
                }
            },
            Surface.FILES_ENDPOINT to { p, person ->
                listOf(p.restrictedFile, p.cameraFile, p.recusedFile).forEach { file ->
                    assertThat(read("/api/v1/files/$file/content", person.session)).hasStatus(HttpStatus.FORBIDDEN)
                    assertThat(read("/api/v1/files/$file", person.session).response.status).isIn(403, 404)
                }
            },
            Surface.NOTIFICATIONS to { p, person ->
                send(p.meeting, 2)
                // Only someone who sees a changed item in full is told (d1, of other items): never of a title they may not read.
                assertThat(notified(person, "MEETING_AGENDA_CHANGED")).isEqualTo(if (p.changes(person) > 0) 1L else 0L)
                val params =
                    AppRoleJdbc.inTransaction(tenant.id) { c ->
                        c.query("select params::text from notification_item where user_id = ?", person.id) { it.getString(1) }
                    }
                val mails = MailOutbox(keys, json).all(tenant).flatMap { it.params.values }
                (params + mails).forEach { text -> p.canaries(person).forEach { assertThat(text).doesNotContain(it) } }
            },
            Surface.MY_MEETINGS to { p, person ->
                val mine = read("/api/v1/me/meetings", person.session)
                clean(p, person, mine)
                // forum.read is not in the audience: the meeting is not theirs.
                val row = mine.json().list().singleOrNull { it["id"].asString() == p.meeting.id.toString() }
                if (person == auditor) {
                    assertThat(row).isNull()
                } else {
                    assertThat(row?.get("changesSinceSeen")?.asInt()).isEqualTo(p.changes(person))
                }
            },
            Surface.CONSENT_REQUESTS to { p, person ->
                p.undiscussable(person).forEach { item ->
                    val uri = "/api/v1/meetings/${p.meeting.id}/agenda/items/$item/discussion-requests"
                    assertThat(send(HttpMethod.POST, uri, person)).hasStatus(HttpStatus.NOT_FOUND)
                    assertThat(send(HttpMethod.POST, "$uri/mine/withdraw", person, headers = emptyMap())).hasStatus(HttpStatus.NOT_FOUND)
                }
            },
            Surface.MEETING to { p, person ->
                clean(p, person, read("/api/v1/meetings/${p.meeting.id}", person.session))
                // A guest and an alternate have no role in the committee: its list is 404 to them.
                val list = read("/api/v1/forums/$brc/meetings", person.session)
                if (person == guest || person == alt) assertThat(list).hasStatus(HttpStatus.NOT_FOUND) else clean(p, person, list)
            },
            Surface.DECLARATIONS to { p, person ->
                val base = "/api/v1/meetings/${p.meeting.id}"
                // No declaration on an item whose title the person may not read: 404, naming nothing.
                p.untitled(person).forEach { item ->
                    val declared = send(HttpMethod.POST, "$base/items/$item/declarations", person, """{"nature":"An interest"}""")
                    assertThat(declared).hasStatus(HttpStatus.NOT_FOUND)
                    p.canaries(person).forEach { assertThat(declared.response.contentAsString).doesNotContain(it) }
                }
                val stranger = UUID.randomUUID()
                listOf("intent" to """{"intent":"LEAVE"}""", "ruling" to """{"outcome":"LEAVE"}""", "withdraw" to """{"reason":"x"}""")
                    .forEach { (verb, body) ->
                        val changed =
                            send(HttpMethod.POST, "$base/declarations/$stranger/$verb", person, body, mapOf("If-Match" to "\"0\""))
                        assertThat(changed).hasStatus(HttpStatus.NOT_FOUND)
                    }
                // The declarant's own view and the review: the items to review name no title the person may not read.
                val mine = read("$base/declarations/mine", person.session)
                val review = send(HttpMethod.POST, "$base/declarations/review", person, """{"revision":2}""", emptyMap())
                if (person == auditor) {
                    assertThat(mine).hasStatus(HttpStatus.FORBIDDEN)
                    assertThat(review).hasStatus(HttpStatus.FORBIDDEN)
                } else {
                    clean(p, person, mine)
                    clean(p, person, review)
                }
            },
            Surface.REGISTERS to { p, person ->
                // The meeting register and the reminder are the officers': 403 to the audience and to forum.read.
                val base = "/api/v1/meetings/${p.meeting.id}/declarations"
                assertThat(read(base, person.session)).hasStatus(HttpStatus.FORBIDDEN)
                assertThat(send(HttpMethod.POST, "$base/remind", person, "{}", emptyMap())).hasStatus(HttpStatus.FORBIDDEN)
                // The register across committees and its export: forum.read reads titles and natures by the title rule only.
                val register = read("/api/v1/declarations", person.session)
                val export = read("/api/v1/declarations/export?forumId=$brc", person.session)
                if (person == auditor) {
                    clean(p, person, register)
                    assertThat(export).hasStatusOk()
                    val cells = workbookText(export)
                    // d1's declarations are listed, their items restricted or hidden: no title, no nature.
                    assertThat(cells).contains("Restricted item", "(restricted item)")
                    (p.canaries(person) + NATURE).forEach { assertThat(cells).doesNotContain(it) }
                    assertThat(register.response.contentAsString).doesNotContain(NATURE)
                } else {
                    assertThat(register).hasStatus(HttpStatus.FORBIDDEN)
                    assertThat(export).hasStatus(HttpStatus.FORBIDDEN)
                }
            },
            Surface.LATE_REQUESTS to { p, person ->
                // Nobody probed decides for an item (no chair or vice-chair seat): an empty list, and every decision 403,
                // naming nothing. What the decider is answered, by the rule and with their recusals, is LateRequestsIT.
                val base = "/api/v1/meetings/${p.meeting.id}/agenda/late-requests"
                val mine = read(base, person.session)
                clean(p, person, mine)
                assertThat(mine.json()["requests"].list()).isEmpty()
                listOf(p.restricted, p.hidden, p.recused, p.recusedHidden).forEach { item ->
                    val body = """{"itemId":"$item","agree":true}"""
                    val decided = send(HttpMethod.POST, "$base/decisions", person, body, mapOf("If-Match" to "\"0\""))
                    assertThat(decided).hasStatus(HttpStatus.FORBIDDEN)
                    p.canaries(person).forEach { assertThat(decided.response.contentAsString).doesNotContain(it) }
                }
            },
            Surface.INVITATIONS to { p, person ->
                val base = "/api/v1/meetings/${p.meeting.id}"
                val reads = listOf("$base/calendar.ics", "$base/rsvp/mine", "$base/rsvps").map { read(it, person.session) }
                // forum.read is not in the audience: no invitation and no replies.
                if (person == auditor) {
                    reads.forEach { assertThat(it.response.status).isIn(403, 404) }
                } else {
                    reads.forEach { clean(p, person, it) }
                }
                // The invitation files and emails (sent when planted) name no item: the calendar parts, decrypted.
                val calendars =
                    AppRoleJdbc.inTransaction(tenant.id) { c ->
                        c.query("select id, calendar_enc from notification_mail_outbox where calendar_enc is not null") {
                            it.getObject(1, UUID::class.java) to it.getBytes(2)
                        }
                    }
                assertThat(calendars).describedAs("invitations sent").isNotEmpty()
                calendars.forEach { (id, sealed) ->
                    val text = String(keys.decrypt(sealed, Mail.calendarAad(tenant.id.toString(), id.toString())), Charsets.UTF_8)
                    p.canaries(person).forEach { assertThat(text).doesNotContain(it) }
                }
            },
            Surface.QUORUM to { p, person ->
                val base = "/api/v1/meetings/${p.meeting.id}"
                val register = read("$base/attendance", person.session)
                if (person == auditor) assertThat(register).hasStatus(HttpStatus.FORBIDDEN) else clean(p, person, register)
                // Members and the alternate read the numbers, for the meeting and for any item (no seats); the rest 403.
                listOf("", "?itemId=${p.recused}", "?itemId=${p.recusedHidden}", "?itemId=${p.hidden}").forEach { query ->
                    val quorum = read("$base/quorum$query", person.session)
                    if (person == d1 || person == d3 || person == alt) {
                        clean(p, person, quorum)
                        assertThat(quorum.json().absent("seats")).isTrue()
                    } else {
                        assertThat(quorum).hasStatus(HttpStatus.FORBIDDEN)
                    }
                }
                assertThat(read("$base/quorum/forecast", person.session)).hasStatus(HttpStatus.FORBIDDEN)
                // The snapshot: forum.read reads it without the override's reason; the audience is 403.
                val snapshot = read("$base/quorum/snapshots/${p.snapshot}", person.session)
                if (person == auditor) clean(p, person, snapshot) else assertThat(snapshot).hasStatus(HttpStatus.FORBIDDEN)
            },
            // E06 T3: the decisions of the items a person sees in full only; d1 the recused view of the item they declared on
            // (no vote, no names); the history and the notes of the planted items never.
            Surface.DECISIONS to { p, person ->
                val base = "/api/v1/meetings/${p.meeting.id}"
                val list = read("$base/decisions", person.session)
                clean(p, person, list)
                val decided = list.json()["decisions"].list().associateBy { UUID.fromString(it["itemId"].asString()) }
                if (person == d1) {
                    val recused = decided.getValue(p.recused)
                    assertThat(recused["viewerRecused"].asBoolean()).isTrue()
                    listOf("vote", "dissent", "abstentions", "tookNoPart", "snapshotId").forEach { assertThat(recused.absent(it)).isTrue() }
                    assertThat(decided.keys).doesNotContain(p.recusedHidden)
                } else {
                    assertThat(decided.keys).doesNotContain(p.recused, p.recusedHidden, p.restricted, p.hidden)
                }
                listOf(p.restricted, p.hidden, p.camera, p.confidential, p.recused, p.recusedHidden).forEach { item ->
                    listOf("$base/items/$item/decision/entries", "$base/items/$item/notes").forEach { uri ->
                        val answer = read(uri, person.session)
                        assertThat(answer.response.status).describedAs(uri).isIn(403, 404)
                        p.canaries(person).forEach { assertThat(answer.response.contentAsString).doesNotContain(it) }
                    }
                }
            },
            // E07 T3: an action is read only by those who see its item in full (or are involved): the meeting's actions, "My
            // actions" and each action answer no canary; d1 reads the actions of the items they are named on.
            Surface.ACTIONS to { p, person ->
                clean(p, person, read("/api/v1/meetings/${p.meeting.id}/actions", person.session))
                clean(p, person, read("/api/v1/me/actions", person.session))
                p.actions.forEach { (id, title) ->
                    val one = read("/api/v1/actions/$id", person.session)
                    if (title in p.canaries(person)) {
                        assertThat(one).describedAs("$title for ${person.id}").hasStatus(HttpStatus.NOT_FOUND)
                    } else {
                        assertThat(one.response.status).isIn(200, 404)
                    }
                    p.canaries(person).forEach { assertThat(one.response.contentAsString).doesNotContain(it) }
                    // E07 T4: the timeline answers as the action does.
                    val timeline = read("/api/v1/actions/$id/entries", person.session)
                    assertThat(timeline.response.status).isEqualTo(one.response.status)
                    p.canaries(person).forEach { assertThat(timeline.response.contentAsString).doesNotContain(it) }
                }
                if (person == d1) {
                    val listed = read("/api/v1/meetings/${p.meeting.id}/actions", person.session).response.contentAsString
                    assertThat(listed).contains(CANARY_DESCRIPTION, CANARY_HIDDEN)
                }
            },
            // E07 T8: the committee's tracker lists and counts only the actions of the items the person sees in full (members
            // and forum.read; a guest or an alternate standing in has none: 404); only forum.read exports, and its workbook
            // holds no canary.
            Surface.TRACKER to { p, person ->
                val base = "/api/v1/forums/$brc/actions"
                val page = read(base, person.session)
                assertThat(page.response.status).isIn(200, 404)
                p.canaries(person).forEach { assertThat(page.response.contentAsString).doesNotContain(it) }
                if (page.response.status == 200) {
                    val listed = page.json()["items"].list().map { UUID.fromString(it["id"].asString()) }
                    val unseen = p.actions.filterValues { it in p.canaries(person) }.keys
                    assertThat(listed).describedAs("tracker rows for ${person.id}").doesNotContainAnyElementsOf(unseen)
                    assertThat(page.json()["totals"]["all"].asInt()).isEqualTo(listed.size)
                }
                if (person == d1) assertThat(page.response.contentAsString).contains(CANARY_DESCRIPTION, CANARY_HIDDEN)
                val export = read("$base/export", person.session)
                if (person == auditor) {
                    assertThat(export).hasStatusOk()
                    val cells = workbookText(export)
                    p.canaries(person).forEach { assertThat(cells).doesNotContain(it) }
                } else {
                    assertThat(export.response.status).isIn(403, 404)
                    p.canaries(person).forEach { assertThat(export.response.contentAsString).doesNotContain(it) }
                }
            },
            // E06 T4: the register and its check are forum.read's (a member 403, a guest and an alternate 404), each row of an
            // item they do not see in full "restricted"; one resolution only for whoever sees its item's decision (d1 the
            // recused view of the item they declared on), 404 to everyone else.
            Surface.RESOLUTIONS to { p, person ->
                listOf("/api/v1/forums/$brc/resolutions", "/api/v1/forums/$brc/resolutions/check").forEach { uri ->
                    val answer = read(uri, person.session)
                    if (person == auditor) clean(p, person, answer) else assertThat(answer.response.status).describedAs(uri).isIn(403, 404)
                    p.canaries(person).forEach { assertThat(answer.response.contentAsString).doesNotContain(it) }
                }
                if (person == auditor) {
                    val rows = read("/api/v1/forums/$brc/resolutions", person.session).json()["entries"].list()
                    val shown = rows.filter { !it["restricted"].asBoolean() }.map { UUID.fromString(it["id"].asString()) }
                    assertThat(shown)
                        .describedAs("forum.read reads no restricted or hidden item's row in full")
                        .doesNotContainAnyElementsOf(
                            listOf(p.restricted, p.hidden, p.recused, p.recusedHidden).map { p.resolutions.getValue(it) },
                        )
                }
                p.resolutions.forEach { (item, resolution) ->
                    val answer = read("/api/v1/resolutions/$resolution", person.session)
                    assertThat(answer.response.status).describedAs("resolution of $item").isIn(200, 404)
                    p.canaries(person).forEach { assertThat(answer.response.contentAsString).doesNotContain(it) }
                    val expected =
                        when {
                            item == p.recused && person == d1 -> 200
                            item in listOf(p.recused, p.recusedHidden, p.restricted, p.hidden) && person != d1 -> 404
                            item == p.recusedHidden -> 404
                            else -> answer.response.status
                        }
                    assertThat(answer.response.status).describedAs("resolution of $item").isEqualTo(expected)
                }
            },
            // E08 T2: the register is for seats in office and oversight: the guest and the alternate (no seat of their own) are
            // 403; for the others each planted decision is FULL, a restricted line or absent by the rule, a line never carries
            // its content, and no word of a canary finds anything.
            Surface.REGISTER to { p, person ->
                val scope = read("/api/v1/decision-register/scope", person.session)
                val all = post("/api/v1/decision-register/search", person.session, """{"forumIds":["$brc"]}""")
                if (person == guest || person == alt) {
                    listOf(scope, all).forEach { answer ->
                        assertThat(answer).hasStatus(HttpStatus.FORBIDDEN)
                        p.canaries(person).forEach { assertThat(answer.response.contentAsString).doesNotContain(it) }
                    }
                } else {
                    clean(p, person, scope)
                    clean(p, person, all)
                    val rows = all.json()["rows"].list()
                    val levels = rows.associate { UUID.fromString(it["itemId"].asString()) to it["level"].asString() }
                    assertThat(levels.filterKeys { it in p.decisions.keys }).isEqualTo(p.registerLevels(person))
                    rows.filter { it["level"].asString() == "LINE" }.forEach { line ->
                        listOf("title", "outcomeKind", "text", "conditions", "memoNumber", "hasDissent", "decidedAt").forEach {
                            assertThat(line.absent(it)).describedAs("$it on a line").isTrue()
                        }
                    }
                    p.canaries(person).forEach { term ->
                        val found = post("/api/v1/decision-register/search", person.session, """{"text":"$term"}""")
                        clean(p, person, found)
                        assertThat(found.json()["total"].asInt()).describedAs("register hits for $term").isZero()
                    }
                }
            },
            // E06 T5: the minutes page is the secretaries' while drafting: nobody probed has it (404), and nobody may draft.
            Surface.MINUTES to { p, person ->
                val base = "/api/v1/meetings/${p.meeting.id}/minutes"
                val page = read(base, person.session)
                assertThat(page).hasStatus(HttpStatus.NOT_FOUND)
                p.canaries(person).forEach { assertThat(page.response.contentAsString).doesNotContain(it) }
                listOf("$base/draft", "$base/items/${p.recused}/regenerate", "$base/items/${p.hidden}/regenerate").forEach { uri ->
                    val asked = send(HttpMethod.POST, uri, person, "{}", mapOf("Idempotency-Key" to UUID.randomUUID().toString()))
                    assertThat(asked.response.status).describedAs(uri).isIn(403, 404)
                    p.canaries(person).forEach { assertThat(asked.response.contentAsString).doesNotContain(it) }
                }
                // E06 T6: the review, the confirmation and the stamps: nothing on a meeting not held (404), or refused (403).
                val versioned = mapOf("Idempotency-Key" to UUID.randomUUID().toString(), "If-Match" to "\"0\"")
                listOf(
                    send(HttpMethod.POST, "$base/review", person, """{"decision":"AGREE"}""", versioned),
                    send(HttpMethod.POST, "$base/confirm", person, "{}", versioned),
                    send(HttpMethod.POST, "$base/approvals/${UUID.randomUUID()}/check", person),
                    read("$base/approvals", person.session),
                    // E06 T8: the approver's answer to a correction.
                    send(HttpMethod.POST, "$base/corrections/${UUID.randomUUID()}/decide", person, """{"decision":"APPROVE"}"""),
                ).forEach { answer ->
                    assertThat(answer.response.status).isIn(403, 404)
                    p.canaries(person).forEach { assertThat(answer.response.contentAsString).doesNotContain(it) }
                }
            },
            // E06 T6: the reader's copy and the comments: the readers of circulated minutes only; nothing on a meeting not held.
            // E06 T7: the approved minutes' record, its versions and its check: their readers only, once approved.
            Surface.MINUTES_COPIES to { p, person ->
                val base = "/api/v1/meetings/${p.meeting.id}/minutes"
                listOf(
                    read("$base/document", person.session),
                    read("$base/comments", person.session),
                    send(HttpMethod.POST, "$base/comments", person, """{"kind":"SUGGESTION","itemId":"${p.recused}","text":"x"}"""),
                    read("$base/record", person.session),
                    read("$base/records", person.session),
                    send(HttpMethod.POST, "$base/records/1/check", person),
                    // E06 T8: the corrections to the approved minutes.
                    read("$base/corrections", person.session),
                ).forEach { answer ->
                    assertThat(answer.response.status).isIn(403, 404)
                    p.canaries(person).forEach { assertThat(answer.response.contentAsString).doesNotContain(it) }
                }
            },
            // E07 T7: the next meeting's action taken report, on screen and as a PDF: rows by the one rule, so none of an item
            // the person does not see in full (or is recused from), and no canary in the PDF's text; 404 to the guest. E07
            // follow-up: the rows' follow-up memos are joined per reader by the memo's read rule, so the planted memo (on the
            // rows d1 and d3 see) shows to none of them, by number or subject (MEMO_CANARIES).
            Surface.ACTION_REPORT to { p, person ->
                val uri = "/api/v1/meetings/${p.next.id}/action-report"
                listOf(uri, "$uri/pdf").forEach { path ->
                    val answer = read(path, person.session)
                    when (person) {
                        guest -> assertThat(answer).describedAs(path).hasStatus(HttpStatus.NOT_FOUND)

                        d3, d1 -> assertThat(answer).describedAs(path).hasStatusOk()

                        // forum.read reads it by the rule; the alternate is no member of the committee today.
                        else -> assertThat(answer.response.status).describedAs(path).isIn(200, 404)
                    }
                    val text =
                        if (path.endsWith("/pdf") && answer.response.status == HttpStatus.OK.value()) {
                            pdfText(answer.response.contentAsByteArray)
                        } else {
                            answer.response.contentAsString
                        }
                    p.canaries(person).forEach { assertThat(text).describedAs("a canary in $path").doesNotContain(it) }
                }
                // The memo's row is there for d1 (the restricted item's action) and d3 (the in-camera one), without the memo.
                if (person == d1 || person == d3) {
                    val title = if (person == d1) CANARY_DESCRIPTION else CANARY_CAMERA
                    val action =
                        p.actions.entries
                            .single { it.value == title }
                            .key
                            .toString()
                    val rows = read(uri, person.session).json()["sections"].list().flatMap { it["rows"].list() }
                    assertThat(rows.single { it["actionId"].asString() == action }["memos"].list()).isEmpty()
                }
            },
            // E08 T4: analytics are counts, never an item: members (d1, d3) have their committee's, forum.read every
            // committee's and the attendance export (whose workbook names no item); a guest and an alternate standing in hold
            // no seat, so analytics are not theirs (403). The planted meeting is not held: what a recused or restricted viewer
            // counts is AnalyticsLeakIT's differential.
            Surface.ANALYTICS to { p, person ->
                val base = "/api/v1/analytics/committees"
                val answers = listOf(read(base, person.session), read("$base/$brc", person.session))
                val export = read("$base/$brc/attendance/export", person.session)
                (answers + export).forEach { answer ->
                    p.canaries(person).forEach { assertThat(answer.response.contentAsString).doesNotContain(it) }
                }
                when (person) {
                    guest, alt -> {
                        (answers + export).forEach { assertThat(it).hasStatus(HttpStatus.FORBIDDEN) }
                    }

                    auditor -> {
                        answers.forEach { clean(p, person, it) }
                        assertThat(export).hasStatusOk()
                        val cells = workbookText(export)
                        p.canaries(person).forEach { assertThat(cells).doesNotContain(it) }
                    }

                    else -> {
                        answers.forEach { clean(p, person, it) }
                        assertThat(answers[1].json()["attendance"]["ownRowOnly"].asBoolean()).isTrue()
                        assertThat(answers[1].json()["papers"]["summary"].absent("lateItems")).isTrue()
                        assertThat(export).hasStatus(HttpStatus.FORBIDDEN)
                    }
                }
            },
            // E06 T8: the certified extracts of each planted resolution: forum.read reads the list of a resolution it sees (none
            // issued), members, the guest and the alternate are refused (403 for one they see, 404 otherwise); nobody reads an
            // extract that is not there, and no answer names a canary.
            Surface.EXTRACTS to { p, person ->
                p.resolutions.forEach { (item, resolution) ->
                    val list = read("/api/v1/resolutions/$resolution/extracts", person.session)
                    val content = read("/api/v1/resolutions/$resolution/extracts/${UUID.randomUUID()}/content", person.session)
                    if (person == auditor) {
                        assertThat(list.response.status).describedAs("extracts of $item").isIn(200, 403, 404)
                        if (list.response.status == 200) assertThat(list.json().size()).isZero()
                    } else {
                        assertThat(list.response.status).describedAs("extracts of $item").isIn(403, 404)
                    }
                    assertThat(content.response.status).isIn(403, 404)
                    listOf(list, content).forEach { answer ->
                        p.canaries(person).forEach { assertThat(answer.response.contentAsString).doesNotContain(it) }
                    }
                }
            },
        )

    @Test
    fun `no surface tells a person outside the rule of a hidden, members-only or confidential title or a restricted paper`() {
        val planted = Planted()
        // The canaries are really there: d1 reads them.
        val ofD1 = agendaOf(d1, planted.meeting).response.contentAsString
        assertThat(ofD1).contains(CANARY_HIDDEN, CANARY_DESCRIPTION, CANARY_PAPER, CANARY_CAMERA, CANARY_CONFIDENTIAL)
        // E07 follow-up: the follow-up memo is on the report, number and subject, for the one who reads it (its initiator).
        val report = "/api/v1/meetings/${planted.next.id}/action-report"
        assertThat(read(report, secretary2.session).response.contentAsString).contains(CANARY_MEMO, CANARY_MEMO_NUMBER)
        assertThat(pdfText(read("$report/pdf", secretary2.session).response.contentAsByteArray)).contains(CANARY_MEMO, CANARY_MEMO_NUMBER)
        // Their lines are there as "Restricted item": d3's confidential one, and both for the forum.read holder.
        assertThat(agenda(d3, planted.meeting).access(planted.confidential)).containsExactly("HIDDEN")
        assertThat(agenda(auditor, planted.meeting).access(planted.camera, planted.confidential)).containsExactly("HIDDEN", "HIDDEN")
        // The recused member's canaries are there for an officer who is not recused; d1 has the recused lines only.
        val ofSecretary = agendaOf(secretary2, planted.meeting).response.contentAsString
        assertThat(ofSecretary).contains(CANARY_RECUSED_DESCRIPTION, CANARY_RECUSED_PAPER, CANARY_RECUSED_HIDDEN)
        assertThat(agenda(d1, planted.meeting).access(planted.recused, planted.recusedHidden)).containsExactly("TITLE", "HIDDEN")
        // The alternate reads the members-only and confidential titles (d1's seat, cleared), and the snapshot's reason is
        // there for an officer who is not recused.
        assertThat(agendaOf(alt, planted.meeting).response.contentAsString).contains(CANARY_CAMERA, CANARY_CONFIDENTIAL)
        val reason = read("/api/v1/meetings/${planted.meeting.id}/quorum/snapshots/${planted.snapshot}", secretary2.session)
        assertThat(reason.response.contentAsString).contains(CANARY_OVERRIDE)
        for (person in probed()) {
            for ((surface, probe) in probes) {
                runCatching { probe(planted, person) }.onFailure { throw AssertionError("surface $surface leaks to ${person.id}", it) }
            }
        }
    }

    @Test
    fun `every endpoint of the meeting and minutes modules is in the surface registry, and every surface has a probe`() {
        val endpoints =
            mapping.handlerMethods
                .filter { (_, handler) -> MODULES.any { handler.beanType.packageName.startsWith(it) } }
                .flatMap { (info, _) ->
                    val methods = info.methodsCondition.methods.map { it.name }
                    info.patternValues.flatMap { path -> methods.map { "$it $path" } }
                }.toSet()
        assertThat(endpoints).isNotEmpty()
        val registered = PaperSurfaceRegistry.endpoints.keys + PaperSurfaceRegistry.officersOnly.keys
        assertThat(endpoints - registered).describedAs("meeting endpoints missing from PaperSurfaceRegistry").isEmpty()
        assertThat(registered - endpoints).describedAs("registry entries without an endpoint").isEmpty()
        assertThat(probes.keys).describedAs("surfaces without a probe").containsExactlyInAnyOrder(*Surface.entries.toTypedArray())
    }

    /** A PDF's text with one space between words, so a phrase the page wrapped is still found whole (or caught leaking). */
    private fun pdfText(bytes: ByteArray): String = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }.replace(Regex("\\s+"), " ")

    /** Every cell of an exported workbook, as one text. */
    private fun workbookText(result: MvcTestResult): String =
        XSSFWorkbook(ByteArrayInputStream(result.response.contentAsByteArray)).use { workbook ->
            workbook
                .getSheetAt(0)
                .flatMap { row -> row.map { it.stringCellValue } }
                .joinToString(" | ")
        }

    private fun clean(
        planted: Planted,
        person: Person,
        result: MvcTestResult,
    ) {
        assertThat(result.response.status).isLessThan(HttpStatus.BAD_REQUEST.value())
        val body = result.response.contentAsString
        planted.canaries(person).forEach {
            assertThat(body).describedAs("a canary in ${result.mvcResult.request.requestURI}").doesNotContain(it)
        }
    }

    private companion object {
        const val CANARY_HIDDEN = "CANARY hidden title"
        const val CANARY_DESCRIPTION = "CANARY description"
        const val CANARY_PAPER = "CANARY paper title"
        const val CANARY_CAMERA = "CANARY in camera"
        const val CANARY_CONFIDENTIAL = "CANARY confidential title"
        const val RECUSED_TITLE = "Term loan, Delta Textiles"
        const val CANARY_RECUSED_DESCRIPTION = "CANARY recused description"
        const val CANARY_RECUSED_PAPER = "CANARY recused paper title"
        const val CANARY_RECUSED_HIDDEN = "CANARY recused hidden title"

        /** The override's reason on the recused item's snapshot: free text, for officers not recused only. */
        const val CANARY_OVERRIDE = "CANARY override reason"
        val RECUSED_CANARIES = listOf(CANARY_RECUSED_DESCRIPTION, CANARY_RECUSED_PAPER, CANARY_RECUSED_HIDDEN, CANARY_OVERRIDE)

        /** E07 follow-up: a follow-up memo's subject and number, which only its initiator (the other secretary) reads. */
        const val CANARY_MEMO = "CANARY-MEMO follow-up subject"
        const val CANARY_MEMO_NUMBER = "CANARY-MNO/2026/0412"
        val MEMO_CANARIES = listOf(CANARY_MEMO, CANARY_MEMO_NUMBER)

        /** The modules whose endpoints are all in [PaperSurfaceRegistry.endpoints] or [PaperSurfaceRegistry.officersOnly]. */
        val MODULES =
            listOf("com.memo.meeting", "com.memo.minutes", "com.memo.actions", "com.memo.search.internal.decision", "com.memo.analytics")
    }
}
