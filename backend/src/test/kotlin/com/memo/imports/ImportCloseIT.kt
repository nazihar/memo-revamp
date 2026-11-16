package com.memo.imports

import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.Audit
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.sendJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * Closing and abandoning (ADR-0055, design E06 §4.1, §10, §12; test plan E06 T8 `ImportCloseIT`): a completed batch is
 * closed with a reason, an unstarted one abandoned with its rows not started; either way the purge job deletes every
 * staged object (sources, originals, replaced copies) while the rows, the originals received and their hashes stay, and
 * the imported memos' own originals are untouched; only holders in the batch's tenant act, from the right states.
 */
class ImportCloseIT : ImportEndTest() {
    @Test
    fun `closing a completed batch needs a reason, purges its staged objects and keeps its rows and hashes`() {
        val id = imported(listOf(memoRow("CL/1"), memoRow("CL/2")))
        assertThat(close(id).json()["reason"].asString()).describedAs("before completion").isEqualTo("WRONG_STATE")
        completed(id)
        val staged = stagedVersions(id)
        assertThat(staged).hasSize(3)
        val stored = storedVersions(id)

        assertThat(close(id, reason = " ").errors()).containsExactly("reason:REQUIRED")
        assertThat(close(id, reason = "x".repeat(501)).errors()).containsExactly("reason:TOO_LONG")
        val closed = ok(close(id))
        assertThat(closed["state"].asString()).isEqualTo("CLOSED")
        assertThat(closed["closeReason"].asString()).isEqualTo("Migration checked")
        assertThat(closed["allowedActions"].items()).isEmpty()
        assertThat(Audit.assertAudited(tenant.id, "import.batch.closed", id, holder.toString()).canonical)
            .contains("\"stagedToPurge\":3", "Migration checked")
        assertThat(close(id).json()["reason"].asString()).isEqualTo("WRONG_STATE")

        purge(id)
        staged.forEach { version ->
            val purged = transactions.readInTenant(tenant.id) { files.version(version)!! }
            assertThat(purged.purgedAt).describedAs("purged").isNotNull()
            assertThat(purged.sha256).describedAs("hash kept").hasSize(64)
            Audit.assertAudited(tenant.id, "file.version.purged", version.toString())
        }
        stored.forEach { version ->
            assertThat(transactions.readInTenant(tenant.id) { files.version(version)!! }.purgedAt).describedAs("records stay").isNull()
        }
        val after = batch(id)
        assertThat(after.absent("purgedAt")).isFalse()
        assertThat(after["originals"]["purged"].asInt()).isEqualTo(2)
        assertThat(Audit.assertAudited(tenant.id, "import.batch.purged", id, "legacy-import").canonical).contains("\"stagedVersions\":3")
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(c.long("select count(*) from imports_row where batch_id = ?::uuid and result = 'IMPORTED'", id)).isEqualTo(2)
            assertThat(c.long("select count(*) from imports_file where batch_id = ?::uuid and sha256 is not null", id)).isEqualTo(2)
            assertThat(c.long("select count(*) from imports_staged where batch_id = ?::uuid and purged_at is null", id)).isZero()
        }
        assertThat(ok(report(id))["state"].asString()).describedAs("the report stays").isEqualTo("CLOSED")
        assertThat(purge(id)).describedAs("nothing more").isOne()
    }

    @Test
    fun `abandoning an unstarted batch leaves its rows not started and purges its staged objects, replaced ones too`() {
        val id = validated(listOf(memoRow("AB/1"), memoRow("AB/2")))
        // The workbook is uploaded again: the first copy is replaced, and goes too.
        attach(
            id,
            workbook(listOf(memoRow("AB/1"), memoRow("AB/2")), listOf(fileRow("AB/1", pathOf("AB/1")), fileRow("AB/2", pathOf("AB/2")))),
        )
        val staged = stagedVersions(id)
        assertThat(staged).hasSize(4)

        val abandoned = ok(abandon(id))
        assertThat(abandoned["state"].asString()).isEqualTo("ABANDONED")
        assertThat(rows(id).values.map { it["result"].asString() }).containsOnly("NOT_STARTED")
        Audit.assertAudited(tenant.id, "import.batch.abandoned", id, holder.toString())
        listOf("abandon", "close").forEach { action ->
            val again = post("/api/v1/admin/imports/$id/$action", session, """{"reason":"again"}""")
            assertThat(again.json()["reason"].asString()).describedAs(action).isEqualTo("WRONG_STATE")
        }
        assertThat(post("/api/v1/admin/imports/$id/dry-run", session).json()["reason"].asString()).isEqualTo("WRONG_STATE")

        purge(id)
        staged.forEach { assertThat(transactions.readInTenant(tenant.id) { files.version(it)!! }.purgedAt).isNotNull() }
        assertThat(batch(id).absent("purgedAt")).isFalse()

        val running = imported(listOf(memoRow("AB/3")))
        assertThat(abandon(running).json()["reason"].asString()).describedAs("imported rows").isEqualTo("WRONG_STATE")
        assertThat(close(running).json()["reason"].asString()).isEqualTo("WRONG_STATE")
    }

    @Test
    fun `without memo import closing and abandoning are forbidden, and another tenant's holder finds no batch`() {
        val open = newBatch()
        val stranger = person(CONFIDENTIAL).session
        listOf("close", "abandon").forEach { action ->
            assertThat(post("/api/v1/admin/imports/$open/$action", stranger, """{"reason":"x"}""")).hasStatus(HttpStatus.FORBIDDEN)
        }
        val other = registry.aTestTenant(transactions, settings, "close-other", mfaRequired = false)
        val (_, otherSession) = imports.importer(mvc, other)
        listOf("close", "abandon").forEach { action ->
            val result = mvc.sendJson(HttpMethod.POST, other, "/api/v1/admin/imports/$open/$action", otherSession, """{"reason":"x"}""")
            assertThat(result).describedAs(action).hasStatus(HttpStatus.NOT_FOUND)
        }
        assertThat(batch(open)["state"].asString()).isEqualTo("OPEN")
        // The purge of another tenant does nothing to this batch: it is not there.
        assertThat(
            com.memo.common.tenant.TenantContext
                .call(other.id) { closing.purgeStep(UUID.fromString(open)) },
        ).isNull()
    }

    /** Every staged version of [batch] (sources and originals, replaced ones included). */
    private fun stagedVersions(batch: String): List<UUID> =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.prepareStatement("select version_id from imports_staged where batch_id = ?::uuid").use { s ->
                s.setString(1, batch)
                s.executeQuery().use { r -> generateSequence { if (r.next()) r.getObject(1, UUID::class.java) else null }.toList() }
            }
        }
}
