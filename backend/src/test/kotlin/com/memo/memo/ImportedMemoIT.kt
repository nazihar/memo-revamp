package com.memo.memo

import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.Permission
import com.memo.memo.internal.MemoClosing
import com.memo.memo.internal.rendition.MemoRenditions
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.AppRoleJdbc.update
import com.memo.testsupport.Audit
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.query
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.sql.SQLException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Imported memos (ADR-0055; test plan E06 T3 `ImportedMemoIT` and the `memo/V19` persistence rows): a legacy row becomes
 * a final memo with its facts, outside numbering and workflow, audited in its transaction; linked people and unit readers
 * read it under clearance and nobody else does; the database keeps its shape and its legacy rows as imported; and
 * auto-close, the final-record backfill, the initiator's boxes, throughput, notes and links leave it alone.
 */
class ImportedMemoIT : ImportedMemoTest() {
    @Autowired lateinit var access: AccessAdministration

    @Autowired lateinit var closing: MemoClosing

    @Autowired lateinit var renditions: MemoRenditions

    private fun memoRow(id: UUID): Map<String, Any?> =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c
                .query(
                    """
                    select origin, initiator_id, reference, number_series, workflow_instance_id, approved_at, closed_at, status,
                           current_round, document_file_id, memo_type_version_id, subject
                    from memo where id = ?
                    """,
                    id,
                ) { rs -> (1..rs.metaData.columnCount).associate { rs.metaData.getColumnName(it) to rs.getObject(it) } }
                .single()
        }

    private fun count(
        sql: String,
        vararg params: Any,
    ): Long = AppRoleJdbc.inTransaction(tenant.id) { it.long(sql, *params) }

    @Test
    fun `an approved row with an unlinked initiator is a final memo outside numbering and workflow, audited in its transaction`() {
        val row = row(number = "CR/2019/0042")
        val id = import(row)

        val memo = memoRow(id)
        assertThat(memo["origin"]).isEqualTo("IMPORTED")
        assertThat(memo["status"]).isEqualTo("APPROVED")
        assertThat(memo["initiator_id"]).isNull()
        assertThat(memo["reference"]).isEqualTo("CR/2019/0042")
        assertThat(memo["number_series"]).isNull()
        assertThat(memo["workflow_instance_id"]).isNull()
        assertThat(memo["approved_at"]).isNull()
        assertThat(memo["document_file_id"]).isNull()
        assertThat(memo["memo_type_version_id"]).isNotNull()
        assertThat(memo["subject"]).isEqualTo("Working capital for Q2")

        assertThat(count("select count(*) from memo_legacy where memo_id = ? and legacy_number_key = 'CR/2019/0042'", id)).isEqualTo(1)
        assertThat(count("select count(*) from memo_legacy_approval where memo_id = ? and user_id is null", id)).isEqualTo(1)
        assertThat(count("select count(*) from memo_legacy_file where memo_id = ? and role = 'MAIN'", id)).isEqualTo(1)
        // Nobody was linked, and the importer is never written as a person of the memo.
        assertThat(count("select count(*) from memo_participant where memo_id = ?", id)).isZero()
        // Outside the workflow, the register, watches and notifications.
        assertThat(count("select count(*) from workflow_instance where subject_id = ?", id)).isZero()
        assertThat(count("select count(*) from memo_watch where memo_id = ?", id)).isZero()
        assertThat(count("select count(*) from notification_item where link like ?", "%$id%")).isZero()
        assertThat(count("select count(*) from memo_number_counter")).isZero()

        val audited = Audit.assertAudited(tenant.id, "memo.imported", id.toString())
        assertThat(audited.actorType).isEqualTo("SYSTEM")
        val sha256 =
            transactions
                .readInTenant(
                    tenant.id,
                ) { files.currentVersions(listOf(row.originals[0].fileId!!)) }
                .values
                .single()
                .sha256
        assertThat(audited.canonical).contains(sha256, "LEGACY-DMS", "CR/2019/0042", importer.id.toString(), row.source.fingerprint)

        // In the caller's transaction: when it fails after the create, neither the memo nor its audit entry remain.
        val failed = row(number = "CR/2019/0043")
        assertThatThrownBy {
            asJob {
                transactions.inTenant(tenant.id) {
                    legacy.create(failed)
                    error("the import job crashed before commit")
                }
            }
        }.hasMessageContaining("crashed")
        assertThat(count("select count(*) from memo where id = ?", failed.memoId)).isZero()
        assertThat(Audit.entries(tenant.id).filter { it.objectId == failed.memoId.toString() }).isEmpty()
    }

    @Test
    fun `each final status imports, and a closed memo is closed from the start of its legacy closing day`() {
        LegacyStatus.entries.forEach { status ->
            val id = import(row(status = status))
            assertThat(memoRow(id)["status"]).isEqualTo(status.name)
        }
        val closed = import(row(status = LegacyStatus.CLOSED))
        val closedAt = (memoRow(closed)["closed_at"] as java.sql.Timestamp).toInstant()
        assertThat(closedAt).isEqualTo(LocalDate.of(2019, 6, 30).atStartOfDay(zone).toInstant())
    }

    @Test
    fun `linked people and unit readers read it under clearance, nobody else does, and the view says what it is`() {
        val initiator = person(clearance = 1)
        val cleared = person(clearance = 1)
        val uncleared = person()
        val stranger = person(clearance = 2)
        val unitReader = person(clearance = 1)
        asUser(admin) { access.grantCustom(unitReader.id, "Credit reader", setOf(Permission.MEMO_READ_UNIT), unit) }
        val row =
            row(
                rank = 1,
                initiator = LegacyPerson("Initiator", email = initiator.account.email, userId = initiator.id),
                approvals =
                    listOf(
                        LegacyApproval(1, LegacyPerson("Cleared", userId = cleared.id), "Recommended"),
                        LegacyApproval(
                            2,
                            LegacyPerson("Uncleared", userId = uncleared.id),
                            "Approved",
                            decidedOn = LocalDate.of(2019, 3, 20),
                        ),
                        LegacyApproval(3, LegacyPerson("A name only"), "Noted"),
                    ),
            )
        val id = import(row).toString()

        listOf(initiator, cleared, unitReader).forEach { assertThat(get(id, it.session)).hasStatusOk() }
        listOf(uncleared, stranger, importer).forEach { assertThat(get(id, it.session)).hasStatus(HttpStatus.NOT_FOUND) }

        val view = get(id, initiator.session).json()
        assertThat(view["origin"].asString()).isEqualTo("IMPORTED")
        assertThat(view["initiator"]["id"].asString()).isEqualTo(initiator.id.toString())
        assertThat(view["readingAs"].asString()).isEqualTo("INITIATOR")
        assertThat(view["rendition"].asString()).isEqualTo("NONE")
        assertThat(view["canLink"].asBoolean()).isFalse()
        // Final and outside any workflow: no close, edit, route or workflow action, even for its linked initiator.
        assertThat((0 until view["allowedActions"].size()).map { view["allowedActions"][it].asString() }).isEmpty()
        val legacy = view["legacy"]
        assertThat(legacy["sourceCode"].asString()).isEqualTo("LEGACY-DMS")
        assertThat(legacy["legacyNumber"].asString()).isEqualTo(row.source.legacyNumber)
        assertThat(legacy["raisedOn"].asString()).isEqualTo("2019-03-14")
        assertThat(legacy["status"].asString()).isEqualTo("approved")
        assertThat(legacy["initiator"]["user"]["id"].asString()).isEqualTo(initiator.id.toString())
        assertThat((0 until legacy["approvals"].size()).map { legacy["approvals"][it]["person"]["name"].asString() })
            .containsExactly("Cleared", "Uncleared", "A name only")
        assertThat(legacy["approvals"][2].absent("user")).isTrue()
        val original = legacy["originals"][0]
        assertThat(original["restricted"].asBoolean()).isFalse()
        assertThat(original["role"].asString()).isEqualTo("MAIN")
        assertThat(original.absent("sha256")).isTrue()
        assertThat(
            read("/api/v1/files/${original["fileId"].asString()}/versions/${original["versionId"].asString()}/content", cleared.session),
        ).hasStatusOk()
        assertThat(
            read("/api/v1/files/${original["fileId"].asString()}/versions/${original["versionId"].asString()}/content", stranger.session),
        ).hasStatus(HttpStatus.FORBIDDEN)

        // Unlinked: no initiator, the legacy name only.
        val unlinked = get(import(row()).toString(), unitReader.session).json()
        assertThat(unlinked.absent("initiator")).isTrue()
        assertThat(unlinked["legacy"]["initiator"]["name"].asString()).isEqualTo("Rahim Uddin")
    }

    @Test
    fun `an original stricter than the reader's clearance is restricted`() {
        val reader = person(clearance = 1)
        val base = row(initiator = LegacyPerson("Reader", userId = reader.id))
        val secret = LegacyOriginal(LegacyFileRole.ATTACHMENT, "Annex", "memos/annex.pdf", levels()[2].id, original(base.memoId))
        val id = import(base.copy(originals = base.originals + secret)).toString()

        val originals = get(id, reader.session).json()["legacy"]["originals"]
        assertThat(originals[1]["restricted"].asBoolean()).isTrue()
        assertThat(originals[1].absent("title")).isTrue()
        assertThat(originals[1].absent("fileId")).isTrue()
    }

    @Test
    fun `the database refuses a native memo without an initiator and an imported memo off its shape`() {
        val type = generalTypeId()
        val version = asUser(admin) { memoTypes.published(type)!!.id }
        val level = levels()[0].id

        fun insert(columns: String): String? =
            try {
                AppRoleJdbc.inTransaction(tenant.id) { c ->
                    c.update(
                        """
                        insert into memo (id, tenant_id, memo_type_id, memo_type_version_id, subject, classification_id,
                                          classification_rank, created_at, updated_at, $COLUMNS)
                        values (?, ?, ?, ?, 'S', ?, 0, now(), now(), $columns)
                        """,
                        UUID.randomUUID(),
                        tenant.id.value,
                        type,
                        version,
                        level,
                    )
                }
                null
            } catch (e: SQLException) {
                e.message
            }
        val imported = "'IMPORTED', null, 'APPROVED', 'L-1', null, null, 0, null, null"
        assertThat(insert(imported)).isNull()
        assertThat(insert("'NATIVE', null, 'DRAFT', null, null, null, 0, null, null")).contains("memo_initiator_native")
        listOf(
            "'IMPORTED', null, 'APPROVED', 'L-1', null, '${UUID.randomUUID()}', 1, null, null",
            "'IMPORTED', null, 'IN_REVIEW', 'L-1', null, null, 0, null, null",
            "'IMPORTED', null, 'APPROVED', null, null, null, 0, null, null",
            "'IMPORTED', null, 'APPROVED', 'L-1', null, null, 0, now(), null",
            "'IMPORTED', null, 'APPROVED', 'L-1', null, null, 0, null, '${UUID.randomUUID()}'",
        ).forEach { assertThat(insert(it)).describedAs(it).contains("memo_imported_shape") }
        assertThat(
            insert("'IMPORTED', null, 'APPROVED', 'L-1', 'general', null, 0, null, null"),
        ).describedAs("a series").containsAnyOf("memo_imported_shape", "memo_number_parts")
    }

    @Test
    fun `an imported memo changes only by a subject correction or a higher level, and its legacy rows never change`() {
        val id = import(row(rank = 1))

        fun change(
            sql: String,
            vararg params: Any,
        ): String? =
            try {
                AppRoleJdbc.inTransaction(tenant.id) { it.update(sql, *params) }
                null
            } catch (e: SQLException) {
                e.message
            }
        assertThat(change("update memo set status = 'CLOSED', closed_at = now() where id = ?", id)).contains("was imported")
        assertThat(change("update memo set reference = 'OTHER' where id = ?", id)).isNotNull()
        assertThat(change("update memo set origin = 'NATIVE' where id = ?", id)).isNotNull()
        assertThat(change("update memo set initiator_id = ? where id = ?", importer.id, id)).contains("was imported")
        assertThat(change("update memo set classification_rank = 0 where id = ?", id)).contains("was imported")
        assertThat(change("update memo set subject = 'Corrected', version = version + 1, updated_at = now() where id = ?", id)).isNull()
        assertThat(change("update memo set classification_id = ?, classification_rank = 2 where id = ?", levels()[2].id, id)).isNull()

        listOf("memo_legacy", "memo_legacy_approval", "memo_legacy_file").forEach { table ->
            assertThat(change("update $table set memo_id = memo_id where memo_id = ?", id)).describedAs(table).contains("permission denied")
            assertThat(change("delete from $table where memo_id = ?", id)).describedAs(table).contains("permission denied")
        }
        assertThat(change("delete from memo_legacy_correction where memo_id = ?", id)).contains("permission denied")
    }

    @Test
    fun `the row key is unique in the tenant, after normalising, and an earlier import is found by it`() {
        val first = row(number = "cr/2019/0042 ")
        val id = import(first)
        val found = asJob { transactions.readInTenant(tenant.id) { legacy.existing("LEGACY-DMS", "  CR/2019/0042") } }
        assertThat(found).isEqualTo(ExistingImport(id, batch, first.source.rowNumber, first.source.fingerprint))

        // Same key under another reference (a batch prefix): refused by the unique row key.
        val again = row(number = "CR/2019/0042")
        val refused =
            catchThrowableOfType(LegacyMemoRefusedException::class.java) {
                import(again.copy(mapped = again.mapped.copy(reference = "B2-CR/2019/0042")))
            }
        assertThat(refused.problems.map { it.code }).containsExactly("ALREADY_IMPORTED")
        // The same reference as a memo's: refused by the check before anything is written.
        val taken = row(number = "CR/2019/0099")
        val inUse =
            catchThrowableOfType(LegacyMemoRefusedException::class.java) {
                import(taken.copy(mapped = taken.mapped.copy(reference = "cr/2019/0042 ")))
            }
        assertThat(inUse.problems.map { it.code }).containsExactly("NUMBER_IN_USE")
    }

    @Test
    fun `auto-close, the final-record backfill, the boxes, throughput, closing, notes and links leave it alone`() {
        val initiator = person()
        val approved = import(row(initiator = LegacyPerson("Initiator", userId = initiator.id)))
        val closed = import(row(status = LegacyStatus.CLOSED, initiator = LegacyPerson("Initiator", userId = initiator.id)))

        assertThat(asUser(admin) { closing.closeDue() }).isZero()
        renditions.jobs = false
        try {
            assertThat(asJob { transactions.inTenant(tenant.id) { renditions.backfill() } }).isZero()
        } finally {
            renditions.jobs = true
        }
        assertThat(count("select count(*) from memo_rendition where memo_id in (?, ?)", approved, closed)).isZero()

        listOf("drafts", "sent", "closed").forEach { box ->
            val items = read("/api/v1/memos?box=$box", initiator.session).json()["items"]
            assertThat(items.size()).describedAs(box).isZero()
        }
        val weeks = read("/api/v1/dashboards/approvals", initiator.session).json()["throughput"]
        assertThat((0 until weeks.size()).sumOf { weeks[it]["created"].asInt() }).isZero()

        val close = post("/api/v1/memos/$approved/close", initiator.session)
        assertThat(close).hasStatus(HttpStatus.CONFLICT)
        assertThat(close.json()["reason"].asString()).isEqualTo("IMPORTED")
        assertThat(post("/api/v1/memos/$approved/notes", initiator.session, """{"body":{"type":"doc","content":[]}}"""))
            .hasStatus(HttpStatus.FORBIDDEN)
        val link = post("/api/v1/memos/$approved/links", initiator.session, """{"targetReference":"x","kind":"REFERS_TO"}""")
        assertThat(link).hasStatus(HttpStatus.CONFLICT)
        assertThat(memoRow(approved)["status"]).isEqualTo("APPROVED")
    }

    @Test
    fun `another tenant neither sees nor writes the legacy rows`() {
        val id = import(row())
        val other = registry.aTestTenant(transactions, settings, "other", mfaRequired = false)
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        AppRoleJdbc.inTransaction(other.id) { c ->
            listOf("memo_legacy", "memo_legacy_approval", "memo_legacy_file", "memo_legacy_correction").forEach { table ->
                assertThat(c.long("select count(*) from $table where memo_id = ?", id)).describedAs(table).isZero()
            }
        }
        val written =
            runCatching {
                AppRoleJdbc.inTransaction(other.id) { c ->
                    c.update(
                        """
                        insert into memo_legacy_correction (id, tenant_id, memo_id, field, reason, corrected_by, corrected_at)
                        values (?, ?, ?, 'SUBJECT', 'r', ?, ?)
                        """,
                        UUID.randomUUID(),
                        tenant.id.value,
                        id,
                        importer.id,
                        now,
                    )
                }
            }
        assertThat(written.exceptionOrNull()).isInstanceOf(SQLException::class.java)
    }

    private companion object {
        const val COLUMNS =
            "origin, initiator_id, status, reference, number_series, workflow_instance_id, current_round, approved_at, document_file_id"
    }
}
