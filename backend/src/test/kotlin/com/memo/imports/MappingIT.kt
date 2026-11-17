package com.memo.imports

import com.memo.identity.UserLifecycle
import com.memo.testsupport.Audit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode

/**
 * A batch's mappings (ADR-0055, design E06 §6; test plan E06 T6 `MappingIT`): what the dry run proposes for each kind,
 * confirmation kept across dry runs, the classification order, the batch's default level for blanks, and the refusals.
 */
class MappingIT : DryRunTest() {
    @Autowired lateinit var lifecycle: UserLifecycle

    private fun byKind(batch: String): Map<String, Map<String, JsonNode>> =
        mappings(batch)["mappings"].items().groupBy { it["kind"].asString() }.mapValues { (_, list) ->
            list.associateBy { it["legacyValue"].asString() }
        }

    /** The targets, named for the screen (as built T9): a holder of memo.import may not list types, units or users. */
    private fun targets(
        id: String,
        generalName: String,
        linked: List<String>,
    ) {
        val targets = mappings(id)["targets"].items().groupBy { it["kind"].asString() }
        assertThat(targets.getValue("TYPE").single { it["id"].asString() == generalTypeId().toString() }["label"].asString())
            .isEqualTo(generalName)
        assertThat(targets.getValue("UNIT").single { it["id"].asString() == unit.toString() }["detail"].asString())
            .isEqualToIgnoringCase("crd")
        assertThat(targets.getValue("CLASSIFICATION").map { it["id"].asString() })
            .containsExactlyElementsOf(levels().map { it.id.toString() })
        assertThat(targets.getValue("PERSON").map { it["id"].asString() })
            .describedAs("only the users already linked")
            .containsExactlyInAnyOrderElementsOf(linked)
        assertThat(targets).doesNotContainKey("STATUS")
        val onlyTypes = read("/api/v1/admin/imports/$id/mappings?kind=TYPE", session).json()["targets"].items()
        assertThat(onlyTypes.map { it["kind"].asString() }.toSet()).containsExactly("TYPE")
    }

    private fun JsonNode.target(): String? = get("target")?.takeUnless { it.isNull }?.asString()

    @Test
    fun `the dry run proposes a status, type, unit, level and person only where the value says so exactly`() {
        val active = person()
        val leaver = person()
        asUser(admin) { lifecycle.deactivate(leaver.account.id, accounts.find(leaver.account.id)!!.version, admin.id) }
        val generalName = asUser(admin) { legacy.types().single { it.id == generalTypeId() }.name }
        val id = newBatch()
        attach(
            id,
            workbook(
                memos =
                    listOf(
                        memoRow("M/1", status = "Approved", email = active.account.email.uppercase()),
                        memoRow("M/2", status = "closed", type = generalName.lowercase(), unit = "crd", email = leaver.account.email),
                        memoRow("M/3", status = "Pending", type = "Circular", unit = "Credit Division", classification = "Secret"),
                        memoRow("M/4", legacyId = "EMP-7", name = "Only By Id"),
                    ),
                files = listOf(fileRow("M/1", "m1.txt", classification = "Confidential")),
                approvals = listOf(approvalRow("M/1", email = active.account.email), approvalRow("M/2", name = "Named Only")),
            ),
        )
        dryRun(id)
        val kinds = byKind(id)

        val status = kinds.getValue("STATUS")
        assertThat(status.getValue("Approved").target()).isEqualTo("APPROVED")
        assertThat(status.getValue("closed").target()).isEqualTo("CLOSED")
        assertThat(status.getValue("Pending").target()).isNull()
        assertThat(status.getValue("Approved")["rowCount"].asInt()).isEqualTo(2)
        assertThat(status.getValue("Approved")["proposed"].asBoolean()).isTrue()
        assertThat(status.getValue("Approved")["confirmed"].asBoolean()).isFalse()

        val types = kinds.getValue("TYPE")
        assertThat(types.getValue(typeCode()).target()).describedAs("by code").isEqualTo(generalTypeId().toString())
        assertThat(types.getValue(generalName.lowercase()).target()).describedAs("by name").isEqualTo(generalTypeId().toString())
        assertThat(types.getValue("Circular").target()).isNull()

        val units = kinds.getValue("UNIT")
        assertThat(units.getValue("crd").target()).describedAs("by code, any case").isEqualTo(unit.toString())
        assertThat(units.getValue("Credit Division").target()).describedAs("never by name").isNull()

        val levels = kinds.getValue("CLASSIFICATION")
        assertThat(levels.getValue("Internal").target()).isEqualTo(levels()[1].id.toString())
        assertThat(levels.getValue("Internal")["legacyOrder"].asInt()).isEqualTo(2)
        assertThat(levels.getValue("Confidential")["rowCount"].asInt()).describedAs("a Files row's level").isEqualTo(1)
        assertThat(levels.getValue("Secret").target()).isNull()

        val people = kinds.getValue("PERSON")
        val activeKey = "email:${active.account.email.lowercase()}"
        assertThat(people.getValue(activeKey).target()).isEqualTo(active.id.toString())
        assertThat(people.getValue(activeKey)["rowCount"].asInt()).describedAs("initiator and approver").isEqualTo(2)
        assertThat(people.getValue("email:${leaver.account.email.lowercase()}").target()).describedAs("a leaver links too").isEqualTo(
            leaver.id.toString(),
        )
        assertThat(people.getValue("id:EMP-7").target()).describedAs("a legacy id is never proposed").isNull()
        assertThat(people.keys).describedAs("a name alone has no mapping").hasSize(3)

        targets(id, generalName, listOf(active.id.toString(), leaver.id.toString()))
    }

    @Test
    fun `confirmed mappings are kept across dry runs, unconfirmed ones follow the sheets, and saving is audited`() {
        val other = person()
        val id = newBatch()
        attach(
            id,
            workbook(listOf(memoRow("K/1"), memoRow("K/2", status = "Done")), listOf(fileRow("K/1", "k1.txt"), fileRow("K/2", "k2.txt"))),
        )
        originals(id, mapOf("k1.txt" to text("k1"), "k2.txt" to text("k2")))
        dryRun(id)
        val doneTarget = listOf(mapOf("legacyValue" to "Done", "target" to "CLOSED", "confirmed" to false))
        assertThat(saveMappings(id, "STATUS", doneTarget)).hasStatus(HttpStatus.OK)
        val approved = listOf(mapOf("legacyValue" to "Approved", "target" to "REJECTED", "confirmed" to true))
        val saved = saveMappings(id, "STATUS", approved)
        assertThat(saved).hasStatus(HttpStatus.OK)
        assertThat(
            saved
                .json()["mappings"]
                .items()
                .single { it["legacyValue"].asString() == "Approved" }["proposed"]
                .asBoolean(),
        ).isFalse()
        val audited = Audit.assertAudited(tenant.id, "import.mapping.changed", id, holder.toString())
        assertThat(audited.canonical).contains("STATUS", "REJECTED", "hash")
        val person = listOf(mapOf("legacyValue" to "email:nobody@legacy.example", "target" to other.id.toString(), "confirmed" to true))
        assertThat(saveMappings(id, "PERSON", person).errors()).containsExactly("mappings[0].legacyValue:MAPPING_INVALID")

        // The next workbook has only "Approved" and a new value: the confirmed choice stays, the unconfirmed value goes.
        attach(
            id,
            workbook(
                listOf(memoRow("K/1"), memoRow("K/3", status = "Cancelled")),
                listOf(fileRow("K/1", "k1.txt"), fileRow("K/3", "k2.txt")),
            ),
        )
        dryRun(id)
        val status = byKind(id).getValue("STATUS")
        assertThat(status.getValue("Approved").target()).describedAs("the admin's choice, not the proposal").isEqualTo("REJECTED")
        assertThat(status.getValue("Approved")["confirmed"].asBoolean()).isTrue()
        assertThat(status.getValue("Approved")["rowCount"].asInt()).isEqualTo(1)
        assertThat(status.getValue("Cancelled").target()).isEqualTo("CANCELLED")
        assertThat(status).doesNotContainKey("Done")

        // A confirmed value the sheets no longer use is kept with no rows.
        attach(id, workbook(listOf(memoRow("K/3", status = "Cancelled")), listOf(fileRow("K/3", "k2.txt"))))
        dryRun(id)
        assertThat(byKind(id).getValue("STATUS").getValue("Approved")["rowCount"].asInt()).isZero()
    }

    @Test
    fun `a classification mapping must keep the legacy order, targets must exist, and the batch version must match`() {
        val id = newBatch()
        attach(
            id,
            workbook(
                listOf(memoRow("O/1", classification = "Restricted"), memoRow("O/2", classification = "Secret")),
                listOf(fileRow("O/1", "o1.txt"), fileRow("O/2", "o2.txt")),
            ),
        )
        dryRun(id)
        val (public, internal, confidential) = levels().take(3).map { it.id.toString() }

        fun entry(
            value: String,
            target: String?,
            order: Int?,
        ) = mapOf("legacyValue" to value, "target" to target, "legacyOrder" to order, "confirmed" to true)
        val lowered = saveMappings(id, "CLASSIFICATION", listOf(entry("Restricted", confidential, 1), entry("Secret", internal, 2)))
        assertThat(lowered).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(lowered.errors()).containsExactly("mappings[1].target:CLASSIFICATION_ORDER")
        assertThat(saveMappings(id, "CLASSIFICATION", listOf(entry("Restricted", internal, 1), entry("Secret", confidential, 2))))
            .hasStatus(HttpStatus.OK)
        // Equal markings may share a level; one change alone is checked against the saved rest.
        assertThat(saveMappings(id, "CLASSIFICATION", listOf(entry("Secret", public, 2))).errors())
            .containsExactly("mappings[0].target:CLASSIFICATION_ORDER")

        val invalid =
            saveMappings(
                id,
                "CLASSIFICATION",
                listOf(
                    entry("Restricted", "no-such-level", 1),
                    mapOf(
                        "legacyValue" to "Secret",
                        "target" to confidential,
                        "confirmed" to true,
                    ),
                ),
            )
        assertThat(invalid.errors()).containsExactly("mappings[0].target:MAPPING_INVALID", "mappings[1].legacyOrder:MAPPING_INVALID")
        assertThat(
            saveMappings(id, "STATUS", listOf(mapOf("legacyValue" to "Approved", "target" to "PENDING", "confirmed" to true))).errors(),
        ).containsExactly("mappings[0].target:MAPPING_INVALID")
        assertThat(saveMappings(id, "STATUS", listOf(mapOf("legacyValue" to "Approved", "legacyOrder" to 1, "confirmed" to true))).errors())
            .containsExactly("mappings[0].legacyOrder:MAPPING_INVALID")

        val stale = put("/api/v1/admin/imports/$id/mappings/STATUS", session, """[{"legacyValue":"Approved","confirmed":true}]""", 0)
        assertThat(stale).hasStatus(HttpStatus.PRECONDITION_FAILED)
        setState(id, "IMPORTING")
        assertThat(saveMappings(id, "STATUS", listOf(mapOf("legacyValue" to "Approved", "confirmed" to true))).json()["reason"].asString())
            .isEqualTo("WRONG_STATE")
    }

    @Test
    fun `a blank classification takes the batch's default level with a warning, and is refused without one`() {
        val id = newBatch()
        attach(id, workbook(listOf(memoRow("B/1", classification = "")), listOf(fileRow("B/1", "b.txt"))))
        originals(id, mapOf("b.txt" to text("b")))
        confirmedDryRun(id)
        assertThat(rows(id).getValue("B/1").codes()).contains("CLASSIFICATION_BLANK")

        val level = levels()[1].id
        val settings = """{"name":"Legacy","sourceCode":"LEGACY-DMS","timeZone":"Asia/Dhaka","defaultClassificationId":"$level"}"""
        assertThat(put("/api/v1/admin/imports/$id", session, settings, batch(id)["version"].asLong())).hasStatus(HttpStatus.OK)
        dryRun(id)
        val row = rows(id).getValue("B/1")
        assertThat(row["result"].asString()).isEqualTo("READY")
        assertThat(row.codes()).contains("CLASSIFICATION_DEFAULTED").doesNotContain("CLASSIFICATION_BLANK")
        assertThat(problems(id, "code=CLASSIFICATION_DEFAULTED").single()["level"].asString()).isEqualTo("WARNING")
    }
}
