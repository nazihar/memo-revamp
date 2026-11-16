package com.memo.memo

import com.memo.memo.internal.rendition.RecordFixity
import com.memo.testsupport.Audit
import com.memo.testsupport.OwnerJdbc
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The weekly fixity check over imported originals (ADR-0053, ADR-0055, design E06 §11; test plan E06 T4 `LegacyFixityIT`):
 * every original is read back, decrypted and re-hashed against the SHA-256 it was imported with; the run is audited with
 * its counts, and an original that no longer matches is audited on its own.
 */
class LegacyFixityIT : ImportedMemoTest() {
    @Autowired lateinit var fixity: RecordFixity

    @Test
    fun `the weekly run verifies imported originals and names one whose hash no longer matches`() {
        val first = import(row())
        val second = import(row())

        val clean = asJob { fixity.verify() }
        assertThat(clean.originalsChecked).isEqualTo(2)
        assertThat(clean.originalsMismatched).isZero()
        val verified = Audit.assertAudited(tenant.id, "memo.legacy.verified", null)
        assertThat(verified.canonical).contains("\"checked\":2", "\"mismatched\":0")

        // The stored original no longer has the hash it was imported with (as if its object had been altered).
        OwnerJdbc.connect { c ->
            c.autoCommit = false
            c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                it.setString(1, tenant.id.value.toString())
                it.execute()
            }
            c.prepareStatement("update memo_legacy_file set sha256 = decode(repeat('00', 32), 'hex') where memo_id = ?").use {
                it.setObject(1, second)
                it.executeUpdate()
            }
            c.commit()
        }
        val tampered = asJob { fixity.verify() }
        assertThat(tampered.originalsChecked).isEqualTo(2)
        assertThat(tampered.originalsMismatched).isEqualTo(1)
        val mismatch = Audit.assertAudited(tenant.id, "memo.legacy.mismatch", second.toString())
        assertThat(mismatch.canonical).contains("0".repeat(64))
        assertThat(Audit.entries(tenant.id).filter { it.action == "memo.legacy.mismatch" && it.objectId == first.toString() }).isEmpty()
        // Final records are checked in the same run, apart.
        assertThat(tampered.mismatched).isZero()
    }
}
