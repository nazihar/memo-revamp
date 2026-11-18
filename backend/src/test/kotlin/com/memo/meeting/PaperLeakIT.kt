package com.memo.meeting

import com.memo.common.crypto.DataKeys
import com.memo.meeting.PaperSurfaceRegistry.Surface
import com.memo.meeting.internal.ItemChange
import com.memo.meeting.internal.MeetingRow
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.MailOutbox
import com.memo.testsupport.Person
import com.memo.testsupport.query
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.util.UUID

/**
 * The leak-suite skeleton (test plan E03, T4; ADR-0061 and its 2026-11-18 amendment): one probe per surface of
 * [PaperSurfaceRegistry] for people outside the rule — a member not named on a restricted item or a hidden one and not
 * cleared for a confidential one, a guest outside a members-only section, and a `forum.read` holder (who reads neither
 * members-only nor confidential titles). Canary strings are planted in what they must not see (hidden, members-only and
 * confidential titles, descriptions, paper titles); no surface answers them, opens such a paper, counts its changes or
 * notifies them of it. E04 adds the recused member to each probe.
 */
class PaperLeakIT : PaperFixture() {
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    lateinit var mapping: RequestMappingHandlerMapping

    @Autowired lateinit var keys: DataKeys

    /** A published agenda (revision 2 changing only what the probed people must not see), and who must not see what. */
    private inner class Planted {
        val meeting: MeetingRow = meeting()
        val restricted = addItem(meeting, "Credit facility for Acme")
        val hidden = addItem(meeting, CANARY_HIDDEN)
        val camera = addItem(meeting, CANARY_CAMERA, sectionId = inCamera(meeting))
        val confidential = addItem(meeting, CANARY_CONFIDENTIAL)
        val consent = addItem(meeting, "Settlement with a counterparty", section = "Consent items")
        val restrictedFile = upload(meeting, "acme.pdf")
        val cameraFile = upload(meeting, "succession.pdf")
        val restrictedPaper: UUID
        val hiddenPaper: UUID
        val cameraPaper: UUID
        val confidentialPaper: UUID

        init {
            change(meeting, restricted, ItemChange("Credit facility for Acme", CANARY_DESCRIPTION, restrictedTo = listOf(d1.id)))
            change(meeting, hidden, ItemChange(CANARY_HIDDEN, restrictedTo = listOf(d1.id), titleHidden = true))
            change(meeting, confidential, ItemChange(CANARY_CONFIDENTIAL, classificationId = levels()[1].id))
            change(meeting, consent, ItemChange("Settlement with a counterparty", restrictedTo = listOf(d1.id)))
            restrictedPaper = paper(meeting, restricted, restrictedFile, title = CANARY_PAPER)
            hiddenPaper = paper(meeting, hidden, upload(meeting, "dismissal.pdf"), title = CANARY_PAPER)
            cameraPaper = paper(meeting, camera, cameraFile, title = CANARY_CAMERA)
            confidentialPaper = paper(meeting, confidential, upload(meeting, "stress.pdf"), title = CANARY_CONFIDENTIAL)
            publish(meeting)
            probed().forEach { person ->
                assertThat(send(HttpMethod.POST, "/api/v1/meetings/${meeting.id}/seen", person, """{"revision":1}""", emptyMap()))
                    .hasStatus(HttpStatus.NO_CONTENT)
            }
            // Revision 2: the restricted paper replaced, the hidden item and the confidential item retitled.
            replace(restrictedFile)
            change(meeting, hidden, ItemChange("$CANARY_HIDDEN, revised", restrictedTo = listOf(d1.id), titleHidden = true))
            change(meeting, confidential, ItemChange("$CANARY_CONFIDENTIAL, revised", classificationId = levels()[1].id))
            publish(meeting, "Revision two")
        }

        /** What [person] must never be answered: d3 is a member (members-only), the guest is cleared (confidential). */
        fun canaries(person: Person): List<String> =
            listOf(CANARY_HIDDEN, CANARY_DESCRIPTION, CANARY_PAPER) +
                (if (person != d3) listOf(CANARY_CAMERA) else emptyList()) +
                (if (person != guest) listOf(CANARY_CONFIDENTIAL) else emptyList())

        /** The items whose title [person] may not read: d3 reads the members-only one, the guest the confidential one. */
        fun untitled(person: Person): List<UUID> =
            listOf(hidden) + (if (person != d3) listOf(camera) else emptyList()) +
                (if (person != guest) listOf(confidential) else emptyList())

        /** The papers [person] may not open. */
        fun closed(person: Person): List<UUID> =
            when (person) {
                d3 -> listOf(restrictedPaper, hiddenPaper, confidentialPaper)
                guest -> listOf(restrictedPaper, hiddenPaper, cameraPaper)
                else -> listOf(restrictedPaper, hiddenPaper, cameraPaper, confidentialPaper)
            }

        /** How many lines revision 2 changed for [person]: only the guest is cleared for the retitled confidential item. */
        fun changes(person: Person): Int = if (person == guest) 1 else 0
    }

    /** The people probed: a member not named or cleared, a guest, and a `forum.read` holder. */
    private fun probed() = listOf(d3, guest, auditor)

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
                listOf(p.restrictedFile, p.cameraFile).forEach { file ->
                    assertThat(read("/api/v1/files/$file/content", person.session)).hasStatus(HttpStatus.FORBIDDEN)
                    assertThat(read("/api/v1/files/$file", person.session).response.status).isIn(403, 404)
                }
            },
            Surface.NOTIFICATIONS to { p, person ->
                send(p.meeting, 2)
                // Only someone who sees a changed item in full is told: never of a title they may not read.
                if (person != guest) assertThat(notified(person, "MEETING_AGENDA_CHANGED")).isZero()
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
                val uri = "/api/v1/meetings/${p.meeting.id}/agenda/items/${p.consent}/discussion-requests"
                assertThat(send(HttpMethod.POST, uri, person)).hasStatus(HttpStatus.NOT_FOUND)
                assertThat(send(HttpMethod.POST, "$uri/mine/withdraw", person, headers = emptyMap())).hasStatus(HttpStatus.NOT_FOUND)
            },
            Surface.MEETING to { p, person ->
                clean(p, person, read("/api/v1/meetings/${p.meeting.id}", person.session))
                // A guest has no role in the committee: its list is 404 to them.
                val list = read("/api/v1/forums/$brc/meetings", person.session)
                if (person == guest) assertThat(list).hasStatus(HttpStatus.NOT_FOUND) else clean(p, person, list)
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
        )

    @Test
    fun `no surface tells a person outside the rule of a hidden, members-only or confidential title or a restricted paper`() {
        val planted = Planted()
        // The canaries are really there: d1 reads them.
        val ofD1 = agendaOf(d1, planted.meeting).response.contentAsString
        assertThat(ofD1).contains(CANARY_HIDDEN, CANARY_DESCRIPTION, CANARY_PAPER, CANARY_CAMERA, CANARY_CONFIDENTIAL)
        // Their lines are there as "Restricted item": d3's confidential one, and both for the forum.read holder.
        assertThat(agenda(d3, planted.meeting).access(planted.confidential)).containsExactly("HIDDEN")
        assertThat(agenda(auditor, planted.meeting).access(planted.camera, planted.confidential)).containsExactly("HIDDEN", "HIDDEN")
        for (person in probed()) {
            for ((surface, probe) in probes) {
                runCatching { probe(planted, person) }.onFailure { throw AssertionError("surface $surface leaks to ${person.id}", it) }
            }
        }
    }

    @Test
    fun `every endpoint of the meeting module is in the surface registry, and every surface has a probe`() {
        val endpoints =
            mapping.handlerMethods
                .filter { (_, handler) -> handler.beanType.packageName.startsWith("com.memo.meeting") }
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
    }
}
