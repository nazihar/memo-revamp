package com.memo.meeting

import com.memo.actions.ActionPlanting
import com.memo.actions.internal.ActionEvidencePurpose
import com.memo.actions.internal.ActionStore
import com.memo.actions.internal.ItemCopy
import com.memo.testsupport.Person
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.util.UUID

/**
 * E07 T3's part of the recusal leak suite ([RecusalLeakIT]): actions planted on the planted meeting's items, and the probe
 * of the ACTIONS surface (one action, a meeting's actions and "My actions") for each recused viewer.
 */
abstract class RecusalActionsFixture : RecusalLeakFixture() {
    @Autowired lateinit var actionStore: ActionStore

    @Autowired lateinit var meetingItems: MeetingItems

    /**
     * E07 T3: the actions planted on a [Planted] meeting, once per meeting: a draft and an issued action of the item and an
     * issued one of the hidden item, their titles a canary and owned by the control; and one of the hidden item owned by d1
     * (recused from it), whose title is not a canary: they see their own action, never the item's hidden title.
     */
    protected inner class PlantedActions(
        p: Planted,
    ) {
        private val planting = ActionPlanting(transactions, actionStore, tenantDsl)
        private val copies =
            transactions.readInTenant(tenant.id) {
                val agenda = meetingItems.agenda(p.meeting.id)
                p.both.associateWith { id ->
                    val item = checkNotNull(agenda.item(id))
                    ItemCopy(item.parentId, item.classificationId, item.restrictedTo, item.titleHidden, item.membersOnly)
                }
            }

        private fun plant(
            p: Planted,
            item: java.util.UUID,
            title: String,
            owner: Person,
            issued: Boolean = true,
        ) = planting.action(
            tenant.id,
            brc,
            p.meeting.id,
            item,
            copies.getValue(item),
            copies.getValue(item).classificationId,
            secretary2.id,
            title,
            owner.id,
            issued,
            context = "$CANARY_ACTION context".takeIf { owner != d1 },
        )

        val draft = plant(p, p.item, "$CANARY_ACTION draft", control, issued = false)
        val issued = plant(p, p.item, "$CANARY_ACTION issued", control)
        val ofHidden = plant(p, p.hidden, "$CANARY_ACTION of the hidden item", control)
        val d1s = plant(p, p.hidden, "Confirm the covenant waiver", d1)
        val hiddenToAll = listOf(draft, issued, ofHidden)

        /**
         * E07 T4: an evidence file on the issued action, uploaded by its owner (the control) through the whole pipeline and
         * attached to an update; its content and the update are canaries.
         */
        val evidence: UUID by lazy {
            val uri = "/api/v1/files?purpose=${ActionEvidencePurpose.KEY}&owner=$issued"
            val uploaded = upload(control.session, "$CANARY_ACTION evidence".toByteArray(), uri, "evidence.txt")
            assertThat(uploaded).hasStatus(HttpStatus.CREATED)
            val file = UUID.fromString(uploaded.json()["fileId"].asString())
            val version = read("/api/v1/actions/$issued", control.session).json()["version"].asLong()
            val body = """{"text":"$CANARY_ACTION update","fileIds":["$file"]}"""
            val headers = mapOf("If-Match" to "\"$version\"", "Idempotency-Key" to UUID.randomUUID().toString())
            assertThat(send(HttpMethod.POST, "/api/v1/actions/$issued/updates", control, body, headers)).hasStatus(HttpStatus.CREATED)
            file
        }
    }

    private val plantedActions = mutableMapOf<java.util.UUID, PlantedActions>()

    protected fun actionsOf(p: Planted): PlantedActions = plantedActions.getOrPut(p.meeting.id) { PlantedActions(p) }

    /**
     * E07 T3's surface: none of the item's actions, nor a count of them, for a viewer recused from it and not involved
     * (404 to the action, absent from the meeting's actions and "My actions"); d1 sees their own action of the hidden item,
     * marked recused, without its title. The control (the owner) reads the canaries.
     */
    protected fun actionsProbe(
        p: Planted,
        v: Viewer,
    ) {
        val planted = actionsOf(p)
        val person = v.person()
        assertThat(read("/api/v1/actions/${planted.issued}", control.session).response.contentAsString).contains(CANARY_ACTION)
        planted.hiddenToAll.forEach { id ->
            val one = read("/api/v1/actions/$id", person.session)
            assertThat(one).hasStatus(HttpStatus.NOT_FOUND)
            assertThat(one.response.contentAsString).doesNotContain(CANARY_ACTION)
            // E07 T4: the timeline too.
            val timeline = read("/api/v1/actions/$id/entries", person.session)
            assertThat(timeline).hasStatus(HttpStatus.NOT_FOUND)
            assertThat(timeline.response.contentAsString).doesNotContain(CANARY_ACTION)
        }
        val list = read("/api/v1/meetings/${p.meeting.id}/actions", person.session)
        clean(person, list)
        assertThat(list.response.contentAsString).doesNotContain(CANARY_ACTION)
        val listed = list.json()["actions"].list().map { java.util.UUID.fromString(it["id"].asString()) }
        assertThat(listed).doesNotContainAnyElementsOf(planted.hiddenToAll)
        assertThat(list.json()["drafts"].asInt()).isZero()
        evidenceProbe(planted, person)
        val mine = read("/api/v1/me/actions", person.session)
        clean(person, mine)
        assertThat(mine.response.contentAsString).doesNotContain(CANARY_ACTION)
        // E07 T5: the other tabs of "My actions" and their counts.
        listOf("TO_VERIFY", "CONTRIBUTING").forEach { tab ->
            val other = read("/api/v1/me/actions?tab=$tab", person.session)
            clean(person, other)
            assertThat(other.response.contentAsString).doesNotContain(CANARY_ACTION)
            assertThat(other.json()["items"].list().map { java.util.UUID.fromString(it["id"].asString()) })
                .doesNotContainAnyElementsOf(planted.hiddenToAll)
        }
        if (v == Viewer.RECUSED) {
            val own = read("/api/v1/actions/${planted.d1s}", person.session)
            clean(person, own)
            clean(person, read("/api/v1/actions/${planted.d1s}/entries", person.session))
            assertThat(own.json()["viewerRecused"].asBoolean()).isTrue()
            assertThat(own.json()["source"].absent("itemTitle")).isTrue()
            assertThat(listed).containsExactly(planted.d1s)
            assertThat(mine.json()["counts"]["mine"].asInt()).isEqualTo(1)
        } else {
            assertThat(listed).doesNotContain(planted.d1s)
        }
    }

    /**
     * E07 T8's surface: the committee's tracker answers none of the item's actions, nor counts them, for a viewer recused
     * from it and not involved (d1 has their own action of the hidden item only); filtered to the item or searched for the
     * canary, nothing and a count of 0; the export is 403 to a recused member, and a recused secretary's or chair's workbook
     * holds the rows of their tracker only, no canary. The control (the owner) reads the canaries in theirs.
     */
    protected fun trackerProbe(
        p: Planted,
        v: Viewer,
    ) {
        val planted = actionsOf(p)
        val person = v.person()
        val base = "/api/v1/forums/$brc/actions"
        assertThat(read(base, control.session).response.contentAsString).contains(CANARY_ACTION)
        val page = read(base, person.session)
        clean(person, page)
        assertThat(page.response.contentAsString).doesNotContain(CANARY_ACTION)
        val listed = page.json()["items"].list().map { UUID.fromString(it["id"].asString()) }
        assertThat(listed).doesNotContainAnyElementsOf(planted.hiddenToAll)
        assertThat(page.json()["totals"]["all"].asInt()).describedAs("totals of what is listed").isEqualTo(listed.size)
        if (v == Viewer.RECUSED) assertThat(listed).containsExactly(planted.d1s) else assertThat(listed).doesNotContain(planted.d1s)
        listOf("?itemId=${p.item}", "?text=CANARY-ACT", "?rag=GREEN&itemId=${p.hidden}").forEach { query ->
            val narrowed = read(base + query, person.session)
            clean(person, narrowed)
            val ids = narrowed.json()["items"].list().map { UUID.fromString(it["id"].asString()) }
            assertThat(ids).describedAs(query).doesNotContainAnyElementsOf(planted.hiddenToAll)
            assertThat(narrowed.json()["totals"]["all"].asInt()).describedAs(query).isEqualTo(ids.size)
        }
        val export = read("$base/export", person.session)
        if (v.officer) {
            assertThat(export).hasStatusOk()
            val rows = workbookRows(export)
            assertThat(rows.joinToString("\n")).doesNotContain(CANARY_ACTION)
            assertThat(rows.drop(1)).describedAs("the rows of their tracker").hasSize(listed.size)
        } else {
            assertThat(export).hasStatus(HttpStatus.FORBIDDEN)
            clean(person, export, statusBelow = 500)
        }
    }

    /** Each row of an exported workbook, its cells joined. */
    protected fun workbookRows(result: MvcTestResult): List<String> =
        org.apache.poi.xssf.usermodel
            .XSSFWorkbook(java.io.ByteArrayInputStream(result.response.contentAsByteArray))
            .use { workbook -> workbook.getSheetAt(0).map { row -> row.joinToString(" | ") { it.stringCellValue } } }

    /**
     * E07 T4: the evidence through the generic files endpoints: the owner reads it, a recused viewer gets 403 or 404 for
     * the file, its content and each version, naming nothing.
     */
    private fun evidenceProbe(
        planted: PlantedActions,
        person: Person,
    ) {
        val file = planted.evidence
        assertThat(read("/api/v1/files/$file/content", control.session).response.contentAsString).contains(CANARY_ACTION)
        val versions = read("/api/v1/files/$file", control.session).json()["versions"].list().map { it["versionId"].asString() }
        (listOf("/api/v1/files/$file", "/api/v1/files/$file/content") + versions.map { "/api/v1/files/$file/versions/$it/content" })
            .forEach { uri ->
                val result = read(uri, person.session)
                assertThat(result.response.status).describedAs(uri).isIn(403, 404)
                clean(person, result, statusBelow = 500)
                assertThat(result.response.contentAsString).doesNotContain(CANARY_ACTION)
            }
    }

    /**
     * The officer probes of the actions endpoints that name no item, made by the recused secretary: issuing the meeting's
     * drafts skips those of items they are recused from; a direct action names no item.
     */
    protected fun actionOfficerProbes(): Array<Pair<String, (Planted) -> MvcTestResult>> {
        val key = { mapOf("Idempotency-Key" to UUID.randomUUID().toString()) }
        return arrayOf(
            "POST /api/v1/meetings/{meetingId}/actions/issue" to { p ->
                actionsOf(p)
                send(HttpMethod.POST, "/api/v1/meetings/${p.meeting.id}/actions/issue", secretary, "{}", key())
            },
            "POST /api/v1/forums/{forumId}/actions" to { _ ->
                val body = """{"title":"Circulate the revised policy","sourceNote":"Chair's instruction"}"""
                send(HttpMethod.POST, "/api/v1/forums/$brc/actions", secretary, body, key())
            },
            // E07 T5: closing the item's actions at the meeting, which the recused secretary does not see: refused naming nothing.
            "POST /api/v1/meetings/{meetingId}/actions/close-agreed" to { p ->
                val planted = actionsOf(p)
                val body = """{"actionIds":["${planted.issued}","${planted.ofHidden}"]}"""
                send(HttpMethod.POST, "/api/v1/meetings/${p.meeting.id}/actions/close-agreed", secretary, body, key())
            },
        )
    }

    protected companion object {
        /** E07 T3: the planted actions' titles and context, which no recused viewer who is not involved may read. */
        const val CANARY_ACTION = "CANARY-ACT action"
    }
}
