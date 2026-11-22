package com.memo.meeting

import com.memo.common.authz.Permission
import com.memo.minutes.DecisionPlanting
import com.memo.minutes.internal.DecisionReads
import com.memo.minutes.internal.DecisionStore
import com.memo.minutes.internal.Dissent
import com.memo.minutes.internal.NoteStore
import com.memo.minutes.internal.ResolutionNumbers
import com.memo.search.RegisterFiles
import com.memo.search.RegisterTable
import com.memo.search.internal.decision.DecisionIndex
import com.memo.testsupport.Person
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.util.UUID

/**
 * The recusal leak suite's REGISTER surface with decisions (E08 T2; test plan E08 §4; [RecusalLeakIT] probes the same
 * surface in its shared scenario, where nothing is decided). Both planted items of [RecusalLeakFixture.Planted] are decided
 * and numbered, with canaries in each decision's text and conditions (and a dissent's reason), and indexed as the indexer
 * writes them. The four recused viewers (a member recused, one awaiting the chair's ruling, a recused secretary and a
 * recused chair) get each as a restricted line (number, meeting, item number; "RECUSED"), never its title, outcome, text,
 * conditions, memo, flags or decision time; no words, outcome or exception filter finds it and no outcome facet counts it;
 * its number finds the line only. The `forum.read` holder reads the shown item in full and the hidden one as a line; the
 * observer, a guest and someone outside are 403. Every search's audit holds the words as a hash, so no canary reaches
 * the audit viewer either. E08 T3, the leak suite's EXPORTS channel for the register's files: the recused secretary and
 * chair export both items as restricted lines (every cell of the spreadsheet and the PDF's text read: no canary, the line's
 * words and number), the recused members and the awaiting one are members (403), `forum.read` exports the shown item in
 * full and the hidden one as a line, and the export's audit holds no canary.
 */
class RegisterLeakIT : RecusalLeakFixture() {
    @Autowired lateinit var decisionStore: DecisionStore

    @Autowired lateinit var noteStore: NoteStore

    @Autowired lateinit var decisionReads: DecisionReads

    @Autowired lateinit var numbers: ResolutionNumbers

    @Autowired lateinit var decisionIndex: DecisionIndex

    /** The planted meeting with both items decided, numbered and in the register; the shown item's number. */
    private inner class Decided {
        val p = Planted(content = true)
        val decisions: Map<UUID, UUID>
        val number: String

        init {
            val planting = DecisionPlanting(transactions, decisionStore, noteStore)
            decisions =
                linkedMapOf(
                    p.item to
                        planting.decision(
                            tenant.id,
                            p.meeting.id,
                            brc,
                            p.item,
                            secretary2.id,
                            "$CANARY_DEC_TEXT for the term loan",
                            dissent = Dissent(d3.id, CANARY_DEC_DISSENT),
                            conditions = listOf(CANARY_DEC_CONDITION),
                        ),
                    p.hidden to
                        planting.decision(
                            tenant.id,
                            p.meeting.id,
                            brc,
                            p.hidden,
                            secretary2.id,
                            CANARY_DEC_HIDDEN,
                            conditions = listOf(CANARY_DEC_HIDDEN_CONDITION),
                        ),
                )
            decisions.values.forEach { planting.number(tenant.id, it, decisionReads, numbers) }
            transactions.inTenant(tenant.id) { decisions.values.forEach { check(decisionIndex.put(it)) } }
            number = checkNotNull(RegisterTable.row(tenant.id.value, decisions.getValue(p.item))?.get("resolution_number")) as String
        }

        val ids: Set<String> get() = decisions.values.map { it.toString() }.toSet()
    }

    private fun register(
        person: Person,
        body: String,
    ): MvcTestResult = post("/api/v1/decision-register/search", person.session, body)

    private val all get() = """{"forumIds":["$brc"]}"""

    @Test
    fun `the register gives a recused viewer restricted lines only, and no canary of the decisions' text or conditions`() {
        val d = Decided()
        // The canaries are there: the control (a member who declared nothing) reads both decisions in full, and finds them.
        assertThat(register(control, all).response.contentAsString)
            .contains(CANARY_DEC_TEXT, CANARY_DEC_CONDITION, CANARY_DEC_HIDDEN, CANARY_DEC_HIDDEN_CONDITION)
        assertThat(register(control, """{"text":"$CANARY_DEC_WORD"}""").json()["total"].asInt()).isEqualTo(2)
        for (viewer in Viewer.entries) {
            runCatching { recusedViewer(d, viewer.person()) }.onFailure { throw AssertionError("the register leaks to $viewer", it) }
        }
        oversightAndOutsiders(d)
        // The audit of the searches: performed, with the words hashed; no canary in any entry.
        val entries = read("/api/v1/admin/audit?action=search.decisions.performed&limit=200", auditReader().session)
        assertThat(entries).hasStatusOk()
        assertThat(entries.json()["items"].list()).isNotEmpty()
        (REGISTER_CANARIES + d.number).forEach { assertThat(entries.response.contentAsString).doesNotContain(it) }
    }

    @Test
    fun `the register's files give a recused officer restricted lines only, members none, and no canary in either format`() {
        val d = Decided()
        // The canaries are in the files: the other secretary, who declared nothing, exports both decisions in full.
        RegisterFiles.formats.forEach { format ->
            assertThat(RegisterFiles.text(exported(secretary2, format)))
                .contains(CANARY_DEC_TEXT, CANARY_DEC_CONDITION, CANARY_DEC_HIDDEN, CANARY_DEC_HIDDEN_CONDITION, CANARY_TITLE)
        }
        for (viewer in Viewer.entries) {
            runCatching { exportedBy(d, viewer) }.onFailure { throw AssertionError("the register's files leak to $viewer", it) }
        }
        // forum.read: the shown item in full, the hidden one as a line; the observer, a guest and someone outside: 403.
        RegisterFiles.formats.forEach { format ->
            val text = RegisterFiles.text(exported(auditor, format))
            assertThat(text).contains(CANARY_DEC_TEXT, "Restricted item: restricted to named members")
            assertThat(text).doesNotContain(CANARY_DEC_HIDDEN, CANARY_DEC_HIDDEN_CONDITION, CANARY_TITLE)
            listOf(observer, guest, outsider).forEach { person ->
                val refused = post(EXPORT, person.session, """{"format":"$format","forumIds":["$brc"]}""")
                assertThat(refused).hasStatus(HttpStatus.FORBIDDEN)
                REGISTER_CANARIES.forEach { assertThat(refused.response.contentAsString).doesNotContain(it) }
            }
        }
        val entries = read("/api/v1/admin/audit?action=search.decisions.exported&limit=200", auditReader().session)
        assertThat(entries.json()["items"].list()).hasSize(RegisterFiles.formats.size * 4)
        (REGISTER_CANARIES + CANARY_TITLE).forEach { assertThat(entries.response.contentAsString).doesNotContain(it) }
    }

    /** [viewer]'s files: an officer's hold both items as RECUSED lines with their numbers and nothing else; a member's 403. */
    private fun exportedBy(
        d: Decided,
        viewer: Viewer,
    ) {
        val person = viewer.person()
        RegisterFiles.formats.forEach { format ->
            val result = post(EXPORT, person.session, """{"format":"$format","forumIds":["$brc"]}""")
            if (!viewer.officer) {
                assertThat(result).hasStatus(HttpStatus.FORBIDDEN)
                clean(person, result, statusBelow = 500)
                return@forEach
            }
            assertThat(result).hasStatusOk()
            val text = RegisterFiles.text(result)
            (REGISTER_CANARIES + canaries(person)).forEach { assertThat(text).describedAs("$format: $it").doesNotContain(it) }
            assertThat(text).contains("Restricted item: you declared an interest in this item", d.number)
            assertThat(text).doesNotContain("term loan", "Security perfected")
        }
    }

    private fun exported(
        person: Person,
        format: String,
    ): MvcTestResult = post(EXPORT, person.session, """{"format":"$format","forumIds":["$brc"]}""").also { assertThat(it).hasStatusOk() }

    /** [person], recused from both items: two RECUSED lines with no content, found by nothing but the number. */
    private fun recusedViewer(
        d: Decided,
        person: Person,
    ) {
        val listed = register(person, all)
        registerClean(person, listed)
        val rows = listed.json()["rows"].list()
        val lines = rows.filter { it["decisionId"].asString() in d.ids }
        assertThat(lines).hasSize(2).allSatisfy { line ->
            assertThat(line["level"].asString() to line["reason"].asString()).isEqualTo("LINE" to "RECUSED")
            LINE_ABSENT.forEach { assertThat(line.absent(it)).describedAs("$it on a line").isTrue() }
            assertThat(line["meetingId"].asString()).isEqualTo(
                d.p.meeting.id
                    .toString(),
            )
            assertThat(line.absent("resolutionNumber")).isFalse()
        }
        val full = rows.count { it["level"].asString() == "FULL" }
        val outcomes = listed.json()["facets"]["outcomes"].list().sumOf { it["count"].asInt() }
        assertThat(outcomes).describedAs("outcome facets count FULL rows only").isEqualTo(full)
        assertThat(listed.json()["total"].asInt()).isEqualTo(rows.size)
        listOf(
            """{"text":"$CANARY_DEC_WORD"}""",
            """{"text":"term loan"}""",
            """{"forumIds":["$brc"],"outcomes":["APPROVED"]}""",
            """{"forumIds":["$brc"],"flags":["WITH_CONDITIONS"]}""",
            """{"forumIds":["$brc"],"flags":["WITH_DISSENT"]}""",
        ).forEach { body ->
            val found = register(person, body)
            registerClean(person, found)
            val returned = found.json()["rows"].list().map { it["decisionId"].asString() }
            assertThat(returned).describedAs(body).doesNotContainAnyElementsOf(d.ids)
            assertThat(found.json()["total"].asInt()).describedAs(body).isEqualTo(returned.size)
        }
        val byNumber = register(person, """{"text":"${d.number}"}""")
        registerClean(person, byNumber)
        val line = byNumber.json()["rows"].list().single()
        assertThat(line["decisionId"].asString() to line["level"].asString())
            .isEqualTo(d.decisions.getValue(d.p.item).toString() to "LINE")
    }

    /** forum.read reads the shown item in full and the hidden one as a line; the register is not for everyone else. */
    private fun oversightAndOutsiders(d: Decided) {
        val audited = register(auditor, all)
        assertThat(audited).hasStatusOk()
        assertThat(audited.response.contentAsString).doesNotContain(CANARY_DEC_HIDDEN, CANARY_DEC_HIDDEN_CONDITION)
        val levels = audited.json()["rows"].list().associate { it["decisionId"].asString() to it["level"].asString() }
        assertThat(levels)
            .containsEntry(d.decisions.getValue(d.p.item).toString(), "FULL")
            .containsEntry(d.decisions.getValue(d.p.hidden).toString(), "LINE")
        assertThat(register(auditor, """{"text":"$CANARY_DEC_WORD"}""").json()["total"].asInt()).isEqualTo(1)
        listOf(observer, guest, outsider).forEach { person ->
            listOf(read("/api/v1/decision-register/scope", person.session), register(person, all)).forEach { answer ->
                assertThat(answer).hasStatus(HttpStatus.FORBIDDEN)
                REGISTER_CANARIES.forEach { assertThat(answer.response.contentAsString).doesNotContain(it) }
            }
        }
    }

    /** [result] answered [person] with none of their canaries nor the register's. */
    private fun registerClean(
        person: Person,
        result: MvcTestResult,
    ) {
        clean(person, result)
        REGISTER_CANARIES.forEach { assertThat(result.response.contentAsString).doesNotContain(it) }
    }

    /** A person with `audit.read`. */
    private fun auditReader(): Person =
        person().also { reader -> asUser(admin) { access.grantCustom(reader.id, "Auditors", setOf(Permission.AUDIT_READ)) } }

    private companion object {
        const val EXPORT = "/api/v1/decision-register/export"

        /** One word in both planted decisions' text, and their texts, conditions and a dissent's reason. */
        const val CANARY_DEC_WORD = "Karnaphulicanary"
        const val CANARY_DEC_TEXT = "CANARY-DT $CANARY_DEC_WORD decision text"
        const val CANARY_DEC_CONDITION = "CANARY-DC decision condition"
        const val CANARY_DEC_HIDDEN = "CANARY-DH $CANARY_DEC_WORD hidden decision"
        const val CANARY_DEC_HIDDEN_CONDITION = "CANARY-DHC hidden decision condition"
        const val CANARY_DEC_DISSENT = "CANARY-DDS dissent reason"
        val REGISTER_CANARIES =
            listOf(
                CANARY_DEC_TEXT,
                CANARY_DEC_CONDITION,
                CANARY_DEC_HIDDEN,
                CANARY_DEC_HIDDEN_CONDITION,
                CANARY_DEC_DISSENT,
                CANARY_DEC_WORD,
            )

        /** What a restricted line never carries (design E08 §1.3). */
        val LINE_ABSENT =
            listOf(
                "title",
                "outcomeKind",
                "outcomeLabel",
                "resolution",
                "text",
                "conditions",
                "validUntil",
                "hasDissent",
                "voteMode",
                "ratificationRequired",
                "ratifiedByDecisionId",
                "corrected",
                "decidedAt",
                "finalAt",
                "submissionId",
                "memoId",
                "memoTypeId",
                "memoTypeName",
                "memoNumber",
            )
    }
}
